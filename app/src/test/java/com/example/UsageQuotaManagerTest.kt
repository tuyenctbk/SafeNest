package com.example

import com.example.data.AiCoachPreferences
import com.example.data.AiEngineMode
import com.example.data.AiFeatureChannel
import com.example.data.AiModelConfig
import com.example.data.AtomicQuotaOutcome
import com.example.data.ByokGeminiModel
import com.example.data.QuotaLocalMirror
import com.example.data.QuotaPeriod
import com.example.data.QuotaRemoteLedger
import com.example.data.QuotaReservationResult
import com.example.data.RemoteReserveResult
import com.example.data.UsageQuotaLedger
import com.example.data.UsageQuotaManager
import com.example.engine.AiFallbackReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class UsageQuotaManagerTest {

    private val testUid = "parent_alice_123"
    private val validKey = "AIzaSyTestKeyForQuotaUnitTests123456789"

    private fun epochForDate(isoDate: String): Long {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        return sdf.parse("${isoDate}T12:00:00")!!.time
    }

    private class FakeLocalMirror(
        initial: AiCoachPreferences = AiCoachPreferences()
    ) : QuotaLocalMirror {
        private val state = MutableStateFlow(initial)
        override val preferencesFlow: Flow<AiCoachPreferences> = state

        override suspend fun getPreferences(): AiCoachPreferences = state.value

        override suspend fun saveConfiguration(
            mode: AiEngineMode,
            apiKey: String,
            model: ByokGeminiModel,
            dailyCloudLimit: Int,
            ecoLiteMode: Boolean
        ) {
            state.value = state.value.copy(
                mode = mode,
                byokApiKey = apiKey.trim(),
                model = model,
                dailyCloudLimit = dailyCloudLimit.coerceIn(5, 50),
                ecoLiteMode = ecoLiteMode
            )
        }

        override suspend fun setEcoLiteMode(enabled: Boolean) {
            state.value = state.value.copy(ecoLiteMode = enabled)
        }

        override suspend fun switchToOnDeviceFreeMode() {
            state.value = state.value.copy(mode = AiEngineMode.ON_DEVICE_ONLY)
        }

        override suspend fun clearByokKey() {
            state.value = state.value.copy(byokApiKey = "", mode = AiEngineMode.ON_DEVICE_ONLY)
        }

        override suspend fun applyPeriodRolloverIfNeeded(periodIso: String): AiCoachPreferences {
            if (state.value.usageDayKey != periodIso) {
                state.value = state.value.copy(
                    usageDayKey = periodIso,
                    cloudRequestsToday = 0,
                    bonusGraceCredits = 0,
                    graceClaimedToday = false,
                    chatUsedToday = 0,
                    riskChecksUsedToday = 0,
                    briefsUsedToday = 0,
                    studioUsedToday = 0,
                    audioUsedToday = 0,
                    fallbackCascadeCount = 0
                )
            }
            return state.value
        }

        override suspend fun applyRemoteQuotaSnapshot(
            ledger: UsageQuotaLedger,
            currentPeriodIso: String
        ): AiCoachPreferences {
            val active = ledger.rolledOverFor(currentPeriodIso)
            state.value = state.value.copy(
                dailyCloudLimit = active.dailyCloudLimit,
                ecoLiteMode = active.ecoLiteMode,
                usageDayKey = currentPeriodIso,
                cloudRequestsToday = active.cloudRequestsUsed,
                chatUsedToday = active.chatUsed,
                riskChecksUsedToday = active.visionUsed,
                briefsUsedToday = active.briefsUsed,
                studioUsedToday = active.studioUsed,
                audioUsedToday = active.audioUsed,
                bonusGraceCredits = active.bonusGraceCredits,
                graceClaimedToday = active.graceClaimed,
                fallbackCascadeCount = active.fallbackCascadeCount
            )
            return state.value
        }

        override suspend fun reserveCloudCreditLocally(periodIso: String): Pair<Boolean, AiCoachPreferences> {
            val current = applyPeriodRolloverIfNeeded(periodIso)
            return if (current.remainingCloudToday > 0) {
                state.value = current.copy(cloudRequestsToday = current.cloudRequestsToday + 1)
                true to state.value
            } else {
                false to current
            }
        }

        override suspend fun refundCloudCreditLocally(periodIso: String): AiCoachPreferences {
            if (state.value.usageDayKey == periodIso) {
                state.value = state.value.copy(
                    cloudRequestsToday = (state.value.cloudRequestsToday - 1).coerceAtLeast(0)
                )
            }
            return state.value
        }

        override suspend fun recordFeatureCompletionLocally(
            channel: AiFeatureChannel,
            cascadedTier: Boolean,
            periodIso: String
        ): AiCoachPreferences {
            val base = applyPeriodRolloverIfNeeded(periodIso)
            val updated = when (channel) {
                AiFeatureChannel.COACH_CHAT -> base.copy(chatUsedToday = base.chatUsedToday + 1)
                AiFeatureChannel.RISK_CHECK -> base.copy(riskChecksUsedToday = base.riskChecksUsedToday + 1)
                AiFeatureChannel.WEEKLY_BRIEF -> base.copy(briefsUsedToday = base.briefsUsedToday + 1)
                AiFeatureChannel.STUDIO -> base.copy(studioUsedToday = base.studioUsedToday + 1)
                AiFeatureChannel.AUDIO -> base.copy(audioUsedToday = base.audioUsedToday + 1)
            }.let {
                if (cascadedTier) it.copy(fallbackCascadeCount = it.fallbackCascadeCount + 1) else it
            }
            state.value = updated
            return updated
        }

        override suspend fun claimDailyGraceCredits(periodIso: String): Boolean {
            val current = applyPeriodRolloverIfNeeded(periodIso)
            if (current.graceClaimedToday) return false
            state.value = current.copy(
                bonusGraceCredits = current.bonusGraceCredits + AiModelConfig.GRACE_CREDITS_GRANT,
                graceClaimedToday = true
            )
            return true
        }
    }

    private class FakeRemoteLedger(
        initial: UsageQuotaLedger? = null,
        var offline: Boolean = false
    ) : QuotaRemoteLedger {
        val remoteFlow = MutableStateFlow(initial)
        var refundCalls = 0
        var reserveCalls = 0

        override fun observeQuotaLedger(userId: String): Flow<UsageQuotaLedger?> = remoteFlow

        override suspend fun reserveCloudCredit(
            userId: String,
            periodIso: String,
            fallbackDailyLimit: Int,
            fallbackEcoLite: Boolean
        ): Result<RemoteReserveResult> {
            reserveCalls++
            if (offline) return Result.failure(IllegalStateException("Simulated offline"))
            val existing = remoteFlow.value
            val active = existing?.rolledOverFor(periodIso) ?: UsageQuotaLedger.initial(
                userId = userId,
                dateIso = periodIso,
                dailyCloudLimit = fallbackDailyLimit,
                ecoLiteMode = fallbackEcoLite
            )
            return if (active.remainingCloudCredits <= 0) {
                remoteFlow.value = active
                Result.success(RemoteReserveResult.Exhausted(active))
            } else {
                val updated = active.copy(cloudRequestsUsed = active.cloudRequestsUsed + 1)
                remoteFlow.value = updated
                Result.success(RemoteReserveResult.Reserved(updated))
            }
        }

        override suspend fun commitFeatureUsage(
            userId: String,
            periodIso: String,
            channel: AiFeatureChannel,
            cascadedTier: Boolean,
            refundReservedCloudCredit: Boolean,
            fallbackDailyLimit: Int,
            fallbackEcoLite: Boolean
        ): Result<UsageQuotaLedger> {
            if (offline) return Result.failure(IllegalStateException("Simulated offline"))
            val existing = remoteFlow.value
            val active = existing?.rolledOverFor(periodIso) ?: UsageQuotaLedger.initial(
                userId = userId,
                dateIso = periodIso,
                dailyCloudLimit = fallbackDailyLimit,
                ecoLiteMode = fallbackEcoLite
            )
            val adjustedCloud = if (refundReservedCloudCredit && existing?.dateIso == periodIso) {
                (active.cloudRequestsUsed - 1).coerceAtLeast(0)
            } else {
                active.cloudRequestsUsed
            }
            val next = active.copy(
                cloudRequestsUsed = adjustedCloud,
                fallbackCascadeCount = if (cascadedTier) active.fallbackCascadeCount + 1 else active.fallbackCascadeCount
            ).incrementChannel(channel)
            remoteFlow.value = next
            return Result.success(next)
        }

        override suspend fun refundCloudCredit(
            userId: String,
            periodIso: String
        ): Result<UsageQuotaLedger?> {
            refundCalls++
            if (offline) return Result.failure(IllegalStateException("Simulated offline"))
            val existing = remoteFlow.value ?: return Result.success(null)
            if (existing.dateIso != periodIso) return Result.success(existing)
            val updated = existing.copy(
                cloudRequestsUsed = (existing.cloudRequestsUsed - 1).coerceAtLeast(0)
            )
            remoteFlow.value = updated
            return Result.success(updated)
        }

        override suspend fun claimGraceCredits(
            userId: String,
            periodIso: String,
            fallbackDailyLimit: Int,
            fallbackEcoLite: Boolean
        ): Result<Pair<Boolean, UsageQuotaLedger>> {
            if (offline) return Result.failure(IllegalStateException("Simulated offline"))
            val active = remoteFlow.value?.rolledOverFor(periodIso) ?: UsageQuotaLedger.initial(
                userId = userId,
                dateIso = periodIso,
                dailyCloudLimit = fallbackDailyLimit,
                ecoLiteMode = fallbackEcoLite
            )
            if (active.graceClaimed) {
                remoteFlow.value = active
                return Result.success(false to active)
            }
            val updated = active.copy(
                bonusGraceCredits = active.bonusGraceCredits + AiModelConfig.GRACE_CREDITS_GRANT,
                graceClaimed = true
            )
            remoteFlow.value = updated
            return Result.success(true to updated)
        }

        override suspend fun updatePreferences(
            userId: String,
            periodIso: String,
            dailyCloudLimit: Int,
            ecoLiteMode: Boolean
        ): Result<Unit> {
            if (offline) return Result.failure(IllegalStateException("Simulated offline"))
            val current = remoteFlow.value ?: UsageQuotaLedger.initial(
                userId = userId,
                dateIso = periodIso,
                dailyCloudLimit = dailyCloudLimit,
                ecoLiteMode = ecoLiteMode
            )
            remoteFlow.value = current.copy(
                dailyCloudLimit = dailyCloudLimit,
                ecoLiteMode = ecoLiteMode
            )
            return Result.success(Unit)
        }
    }

    @Test
    fun atomicDeductWithRefund_commitsOnSuccessAndRefundsOnTimeoutOrFailure() = runBlocking {
        val nowMs = epochForDate("2026-11-22")
        val local = FakeLocalMirror(
            AiCoachPreferences(
                mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
                byokApiKey = validKey,
                dailyCloudLimit = 10,
                usageDayKey = "2026-11-22"
            )
        )
        val remote = FakeRemoteLedger()
        val manager = UsageQuotaManager(local, remote, clockMs = { nowMs })

        // 1. Successful cloud execution deducts 1 credit and records chatUsed + cascade
        val answer = manager.executeWithAtomicQuota(
            userId = testUid,
            channel = AiFeatureChannel.COACH_CHAT,
            requiresCloudCredit = true
        ) { _, _ ->
            AtomicQuotaOutcome(value = "Calm guidance", chargedCloudCredit = true, cascadedTier = true)
        }
        assertEquals("Calm guidance", answer)
        assertEquals(1, local.getPreferences().cloudRequestsToday)
        assertEquals(1, local.getPreferences().chatUsedToday)
        assertEquals(1, local.getPreferences().fallbackCascadeCount)
        assertEquals(1, remote.remoteFlow.value?.cloudRequestsUsed)

        // 2. Network timeout/failure automatically refunds reserved credit in both local & Firestore
        var caught = false
        try {
            manager.executeWithAtomicQuota<String>(
                userId = testUid,
                channel = AiFeatureChannel.RISK_CHECK,
                requiresCloudCredit = true
            ) { _, _ ->
                // Mid-flight, quota was reserved (2)
                assertEquals(2, remote.remoteFlow.value?.cloudRequestsUsed)
                throw RuntimeException("SocketTimeoutException")
            }
        } catch (_: RuntimeException) {
            caught = true
        }
        assertTrue(caught)
        assertEquals(1, remote.refundCalls)
        assertEquals(1, local.getPreferences().cloudRequestsToday)
        assertEquals(1, remote.remoteFlow.value?.cloudRequestsUsed)
        assertEquals(0, local.getPreferences().riskChecksUsedToday)
    }

    @Test
    fun atomicDeductWithRefund_refundsOnCachedZeroTokenHitWhileKeepingValueReceipt() = runBlocking {
        val nowMs = epochForDate("2026-11-22")
        val local = FakeLocalMirror(
            AiCoachPreferences(
                mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
                byokApiKey = validKey,
                dailyCloudLimit = 10,
                usageDayKey = "2026-11-22"
            )
        )
        val remote = FakeRemoteLedger()
        val manager = UsageQuotaManager(local, remote, clockMs = { nowMs })

        // Reserved 1 credit before call, but engine returned cached 0-token hit -> auto-refunded!
        val result = manager.executeWithAtomicQuota(
            userId = testUid,
            channel = AiFeatureChannel.RISK_CHECK,
            requiresCloudCredit = true
        ) { _, reservation ->
            assertTrue(reservation!!.reservedCloudCredit)
            AtomicQuotaOutcome(value = "Cached risk result", chargedCloudCredit = false)
        }

        assertEquals("Cached risk result", result)
        assertEquals(0, local.getPreferences().cloudRequestsToday)
        assertEquals(0, remote.remoteFlow.value?.cloudRequestsUsed)
        assertEquals(1, local.getPreferences().riskChecksUsedToday)
        assertEquals(1, remote.remoteFlow.value?.visionUsed)
    }

    @Test
    fun refundInteraction_isIdempotentAndNeverDoubleRefundsSameTicket() = runBlocking {
        val nowMs = epochForDate("2026-11-22")
        val local = FakeLocalMirror(
            AiCoachPreferences(
                mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
                byokApiKey = validKey,
                dailyCloudLimit = 10,
                cloudRequestsToday = 3,
                usageDayKey = "2026-11-22"
            )
        )
        val remote = FakeRemoteLedger(
            UsageQuotaLedger.initial(testUid, "2026-11-22", dailyCloudLimit = 10)
                .copy(cloudRequestsUsed = 3)
        )
        val manager = UsageQuotaManager(local, remote, clockMs = { nowMs })

        val reserved = manager.reserveInteraction(testUid, AiFeatureChannel.STUDIO, requiresCloudCredit = true)
            as QuotaReservationResult.Reserved
        assertEquals(4, local.getPreferences().cloudRequestsToday)

        assertTrue(manager.refundInteraction(reserved.reservation, "timeout"))
        assertEquals(3, local.getPreferences().cloudRequestsToday)
        assertEquals(3, remote.remoteFlow.value?.cloudRequestsUsed)

        // Second refund on same ticket is a safe no-op
        assertFalse(manager.refundInteraction(reserved.reservation, "duplicate_retry"))
        assertEquals(3, local.getPreferences().cloudRequestsToday)
        assertEquals(3, remote.remoteFlow.value?.cloudRequestsUsed)
    }

    @Test
    fun automaticMidnightRollover_resetsCountersAndGraceWhilePreservingEcoLite() = runBlocking {
        var nowMs = epochForDate("2026-11-22")
        val local = FakeLocalMirror(
            AiCoachPreferences(
                mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
                byokApiKey = validKey,
                dailyCloudLimit = 10,
                ecoLiteMode = true,
                usageDayKey = "2026-11-22"
            )
        )
        val remote = FakeRemoteLedger(
            UsageQuotaLedger.initial(testUid, "2026-11-22", dailyCloudLimit = 10, ecoLiteMode = true).copy(
                cloudRequestsUsed = 10,
                chatUsed = 6,
                visionUsed = 4,
                bonusGraceCredits = 3,
                graceClaimed = true,
                fallbackCascadeCount = 2
            )
        )
        val manager = UsageQuotaManager(local, remote, clockMs = { nowMs })

        // Same day: exhausted (10 used of 13 effective after grace -> actually 3 left; push to 13)
        remote.remoteFlow.value = remote.remoteFlow.value!!.copy(cloudRequestsUsed = 13)
        local.applyRemoteQuotaSnapshot(remote.remoteFlow.value!!, "2026-11-22")
        val exhausted = manager.reserveInteraction(testUid, AiFeatureChannel.COACH_CHAT, requiresCloudCredit = true)
        assertTrue(exhausted is QuotaReservationResult.Exhausted)
        assertEquals(AiFallbackReason.DAILY_QUOTA_REACHED, (exhausted as QuotaReservationResult.Exhausted).reason)

        // Advance clock past midnight to 2026-11-23
        nowMs = epochForDate("2026-11-23")
        val nextDayReserve = manager.reserveInteraction(testUid, AiFeatureChannel.COACH_CHAT, requiresCloudCredit = true)
        assertTrue(nextDayReserve is QuotaReservationResult.Reserved)

        val snap = local.getPreferences()
        assertEquals("2026-11-23", snap.usageDayKey)
        assertEquals(1, snap.cloudRequestsToday)
        assertEquals(0, snap.chatUsedToday)
        assertEquals(0, snap.bonusGraceCredits)
        assertFalse(snap.graceClaimedToday)
        assertTrue(snap.ecoLiteMode) // Preserved across rollover!
        assertEquals(10, snap.dailyCloudLimit)
    }

    @Test
    fun cloudSync_mirrorsFirestoreIntoLocalCacheWithoutOverwritingByokApiKey() = runBlocking {
        val nowMs = epochForDate("2026-11-22")
        val local = FakeLocalMirror(
            AiCoachPreferences(
                mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
                byokApiKey = validKey,
                dailyCloudLimit = 15,
                usageDayKey = "2026-11-22"
            )
        )
        val remote = FakeRemoteLedger(
            UsageQuotaLedger.initial(testUid, "2026-11-22", dailyCloudLimit = 20, ecoLiteMode = true).copy(
                cloudRequestsUsed = 7,
                chatUsed = 5,
                visionUsed = 2,
                audioUsed = 1
            )
        )
        val manager = UsageQuotaManager(local, remote, clockMs = { nowMs })
        val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            manager.startCloudSync(syncScope, testUid)
            delay(20)
            val synced = local.getPreferences()
            assertEquals(validKey, synced.byokApiKey) // Never wiped by Firestore sync
            assertEquals(AiEngineMode.BYOK_CLOUD_WITH_FALLBACK, synced.mode)
            assertEquals(20, synced.dailyCloudLimit)
            assertTrue(synced.ecoLiteMode)
            assertEquals(7, synced.cloudRequestsToday)
            assertEquals(5, synced.chatUsedToday)
            assertEquals(2, synced.riskChecksUsedToday)
            assertEquals(1, synced.audioUsedToday)
        } finally {
            syncScope.cancel()
        }
    }

    @Test
    fun offlineFallbackAndGraceCredits_workSeamlesslyWhenFirestoreUnreachable() = runBlocking {
        val nowMs = epochForDate("2026-11-22")
        val local = FakeLocalMirror(
            AiCoachPreferences(
                mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
                byokApiKey = validKey,
                dailyCloudLimit = 5,
                cloudRequestsToday = 5,
                usageDayKey = "2026-11-22"
            )
        )
        val remote = FakeRemoteLedger(offline = true)
        val manager = UsageQuotaManager(local, remote, clockMs = { nowMs })

        // Claim +3 grace credits offline -> succeeds once, second claim returns false
        assertTrue(manager.claimDailyGraceCredits(testUid))
        assertFalse(manager.claimDailyGraceCredits(testUid))
        assertEquals(8, local.getPreferences().effectiveDailyLimit)
        assertEquals(3, local.getPreferences().remainingCloudToday)

        // Reserve & refund while offline uses local mirror atomically
        val reserved = manager.reserveInteraction(testUid, AiFeatureChannel.AUDIO, requiresCloudCredit = true)
            as QuotaReservationResult.Reserved
        assertEquals(6, local.getPreferences().cloudRequestsToday)
        assertTrue(manager.refundInteraction(reserved.reservation, "offline_retry"))
        assertEquals(5, local.getPreferences().cloudRequestsToday)
    }

    @Test
    fun cancellationDuringExecution_refundsReservedCreditBeforeRethrowing() = runBlocking {
        val nowMs = epochForDate("2026-11-22")
        val local = FakeLocalMirror(
            AiCoachPreferences(
                mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
                byokApiKey = validKey,
                dailyCloudLimit = 10,
                usageDayKey = "2026-11-22"
            )
        )
        val remote = FakeRemoteLedger()
        val manager = UsageQuotaManager(local, remote, clockMs = { nowMs })

        var cancelled = false
        try {
            manager.executeWithAtomicQuota<Unit>(
                userId = testUid,
                channel = AiFeatureChannel.WEEKLY_BRIEF,
                requiresCloudCredit = true
            ) { _, _ ->
                throw CancellationException("User left screen")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(0, local.getPreferences().cloudRequestsToday)
        assertEquals(0, remote.remoteFlow.value?.cloudRequestsUsed)
    }

    @Test
    fun monthlyPeriod_formatsIsoKeyAsYearMonth() {
        val manager = UsageQuotaManager(
            localMirror = FakeLocalMirror(),
            remoteLedger = FakeRemoteLedger(),
            period = QuotaPeriod.MONTHLY,
            clockMs = { epochForDate("2026-11-22") }
        )
        assertEquals("2026-11", manager.currentPeriodIso())
    }

    @Test
    fun executeWithAtomicQuota_reflectsAuthoritativeRemoteCountForThresholdRouting() = runBlocking {
        val nowMs = epochForDate("2026-11-22")
        // Local cache is slightly behind (2 used), while Firestore source of truth already has 8/10 (80%)
        val local = FakeLocalMirror(
            AiCoachPreferences(
                mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
                byokApiKey = validKey,
                dailyCloudLimit = 10,
                cloudRequestsToday = 2,
                usageDayKey = "2026-11-22"
            )
        )
        val remote = FakeRemoteLedger(
            UsageQuotaLedger.initial(testUid, "2026-11-22", dailyCloudLimit = 10).copy(
                cloudRequestsUsed = 8
            )
        )
        val manager = UsageQuotaManager(local, remote, clockMs = { nowMs })

        var resolvedDuringExecution: ByokGeminiModel? = null
        var seenUsagePercent = 0
        manager.executeWithAtomicQuota(
            userId = testUid,
            channel = AiFeatureChannel.COACH_CHAT,
            requiresCloudCredit = true
        ) { prefsForExecution, _ ->
            seenUsagePercent = prefsForExecution.usagePercent
            resolvedDuringExecution = prefsForExecution.resolvedCloudModel()
            AtomicQuotaOutcome(value = Unit, chargedCloudCredit = true)
        }

        assertEquals(80, seenUsagePercent)
        assertEquals(ByokGeminiModel.ECONOMY_LITE, resolvedDuringExecution)
        assertEquals(9, local.getPreferences().cloudRequestsToday)
        assertEquals(9, remote.remoteFlow.value?.cloudRequestsUsed)
    }

    @Test
    fun deductInteractionAndFeatureRefundHelper_restoresCreditAndPreventsZeroUnderflow() = runBlocking {
        val nowMs = epochForDate("2026-11-22")
        val local = FakeLocalMirror(
            AiCoachPreferences(
                mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
                byokApiKey = validKey,
                dailyCloudLimit = 10,
                cloudRequestsToday = 0,
                usageDayKey = "2026-11-22"
            )
        )
        val remote = FakeRemoteLedger(
            UsageQuotaLedger.initial(testUid, "2026-11-22", dailyCloudLimit = 10)
        )
        val manager = UsageQuotaManager(local, remote, clockMs = { nowMs })

        val deductResult = manager.deductInteraction(testUid, AiFeatureChannel.RISK_CHECK)
        assertTrue(deductResult is QuotaReservationResult.Reserved)
        assertEquals(1, local.getPreferences().cloudRequestsToday)
        assertEquals(1, remote.remoteFlow.value?.cloudRequestsUsed)

        // Failed network call -> refund by feature helper
        assertTrue(manager.refundInteraction(AiFeatureChannel.RISK_CHECK, testUid, "network_timeout"))
        assertEquals(0, local.getPreferences().cloudRequestsToday)
        assertEquals(0, remote.remoteFlow.value?.cloudRequestsUsed)

        // Repeated refund when at 0 -> safely returns false without underflow
        assertFalse(manager.refundInteraction(AiFeatureChannel.RISK_CHECK, testUid, "duplicate_call"))
        assertEquals(0, local.getPreferences().cloudRequestsToday)
    }
}
