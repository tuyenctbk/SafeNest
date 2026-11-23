package com.example

import com.example.analytics.AppUsageCategory
import com.example.analytics.CategoryWeeklyDelta
import com.example.analytics.WeeklySummaryReport
import com.example.analytics.WeeklyTrendDirection
import com.example.data.AiCoachPreferences
import com.example.data.AiEngineMode
import com.example.data.AiModelConfig
import com.example.data.AlertSeverity
import com.example.data.ByokGeminiModel
import com.example.data.LocalFilterSnapshot
import com.example.engine.AiActionType
import com.example.engine.AiChildContext
import com.example.engine.AiFallbackReason
import com.example.engine.AiResponseOrigin
import com.example.engine.CloudTransportResult
import com.example.engine.GeminiCloudTransport
import com.example.engine.ParentAiCoachEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentAiCoachEngineTest {

    private val validTestKey = "AIzaSyTestKeyForUnitTestsOnly1234567890"

    private fun baseContext(
        weeklyReport: WeeklySummaryReport? = null,
        turns: List<Pair<Boolean, String>> = emptyList()
    ) = AiChildContext(
        childName = "Maya",
        childAge = 11,
        dailyLimitMinutes = 90,
        todayScreenMinutes = 65,
        bedtimeStart = "21:00",
        bedtimeEnd = "07:00",
        unreviewedAlertsCount = 1,
        customBlockedCount = 4,
        vpnRunning = true,
        weeklyReport = weeklyReport,
        filterSnapshot = LocalFilterSnapshot(
            blockAdult = true,
            blockGambling = true,
            blockMalware = true,
            customBlockedDomains = setOf("badsite.example")
        ),
        recentConversationTurns = turns
    )

    private fun sampleWeeklyReport(
        direction: WeeklyTrendDirection = WeeklyTrendDirection.UP_INCREASED,
        deltaPercent: Int = 18
    ) = WeeklySummaryReport(
        childName = "Maya",
        isoWeekKey = "2026-W47",
        weekRangeLabel = "Nov 16 – Nov 22",
        thisWeekDays = emptyList(),
        previousWeekDays = emptyList(),
        thisWeekTotalMin = 700,
        previousWeekTotalMin = 590,
        thisWeekDailyAvgMin = 100,
        previousWeekDailyAvgMin = 84,
        deltaMinutes = 110,
        deltaPercent = deltaPercent,
        trendDirection = direction,
        dailyLimitMin = 90,
        activeDaysThisWeek = 7,
        daysWithinLimitThisWeek = 3,
        daysWithinLimitPrevWeek = 5,
        lateNightThisWeekMin = 40,
        lateNightPrevWeekMin = 15,
        categoryDeltas = listOf(
            CategoryWeeklyDelta(AppUsageCategory.GAMES, 360, 260, 100),
            CategoryWeeklyDelta(AppUsageCategory.VIDEO, 200, 190, 10)
        ),
        topRisingCategory = CategoryWeeklyDelta(AppUsageCategory.GAMES, 360, 260, 100),
        topImprovedCategory = null
    )

    @Test
    fun redactForCloud_masksEmailsPhonesOtpCodesAndHandles() {
        val engine = ParentAiCoachEngine()
        val raw = "Contact parent.test@example.com or +1 555-234-5678, OTP 492817 from @stranger_kid!"
        val redacted = engine.redactForCloud(raw)

        assertFalse(redacted.contains("parent.test@example.com"))
        assertFalse(redacted.contains("555-234-5678"))
        assertFalse(redacted.contains("492817"))
        assertFalse(redacted.contains("@stranger_kid"))
        assertTrue(redacted.contains("[EMAIL]"))
        assertTrue(redacted.contains("[NUMBER]"))
        assertTrue(redacted.contains("[CODE]"))
        assertTrue(redacted.contains("[USER]"))
    }

    @Test
    fun onDeviceCoachReply_returnsActionableGamingAdviceAtZeroCost() = runBlocking {
        var transportCalled = false
        val engine = ParentAiCoachEngine(
            transport = GeminiCloudTransport { _, _, _, _, _ ->
                transportCalled = true
                CloudTransportResult.Success("should not run")
            }
        )
        var charged = true
        val reply = engine.askCoach(
            question = "How do I reduce gaming time without arguments?",
            context = baseContext(),
            prefs = AiCoachPreferences(mode = AiEngineMode.ON_DEVICE_ONLY)
        ) { chargedCredit, _ -> charged = chargedCredit }

        assertFalse(transportCalled)
        assertFalse(charged)
        assertEquals(AiResponseOrigin.ON_DEVICE_FREE, reply.origin)
        assertTrue(reply.recommendedActions.any { it.type == AiActionType.APPLY_SCHOOL_NIGHT_PRESET })
        assertTrue(reply.recommendedActions.any { it.type == AiActionType.OPEN_CATEGORY_LIMITS })
    }

    @Test
    fun onDeviceRiskAnalyzer_flagsOmegleAndCustomBlockedDomain() {
        val engine = ParentAiCoachEngine()
        val omegleRisk = engine.analyzeRiskOnDevice(
            inputText = "Is omegle.com safe for my 11 year old?",
            hasScreenshot = false,
            context = baseContext()
        )
        assertEquals(AlertSeverity.HIGH, omegleRisk.severity)
        assertEquals("omegle.com", omegleRisk.detectedDomain)
        assertTrue(omegleRisk.recommendedActions.any { it.type == AiActionType.BLOCK_DOMAIN && it.domain == "omegle.com" })

        val customBlocked = engine.analyzeRiskOnDevice(
            inputText = "badsite.example",
            hasScreenshot = false,
            context = baseContext()
        )
        assertEquals(AlertSeverity.HIGH, customBlocked.severity)
        assertEquals("badsite.example", customBlocked.detectedDomain)
    }

    @Test
    fun onDeviceWeeklyBrief_synthesizesTrendAndSchoolNightAction() {
        val engine = ParentAiCoachEngine()
        val brief = engine.buildOnDeviceWeeklyBrief(baseContext(weeklyReport = sampleWeeklyReport()))

        assertEquals("Maya", brief.childName)
        assertTrue(brief.headline.contains("18%"))
        assertEquals(3, brief.bullets.size)
        assertTrue(brief.recommendedActions.any { it.type == AiActionType.APPLY_SCHOOL_NIGHT_PRESET })
        assertEquals(AiResponseOrigin.ON_DEVICE_FREE, brief.origin)
    }

    @Test
    fun cloudByok_cascadesTier1ToTier2EcoLiteOn429AndChargesOnce() = runBlocking {
        val calledModels = mutableListOf<String>()
        val engine = ParentAiCoachEngine(
            transport = GeminiCloudTransport { _, modelId, _, _, _ ->
                calledModels += modelId
                if (modelId == AiModelConfig.PRIMARY_REASONING_MODEL) {
                    CloudTransportResult.Failure(
                        reason = AiFallbackReason.AUTH_OR_RATE_LIMIT,
                        detail = "HTTP 429",
                        isRateLimited429 = true
                    )
                } else {
                    CloudTransportResult.Success("• Calm Eco Lite response")
                }
            }
        )

        val prefs = AiCoachPreferences(
            mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
            byokApiKey = validTestKey,
            model = ByokGeminiModel.PRIMARY_STANDARD,
            ecoLiteMode = false,
            dailyCloudLimit = 15,
            cloudRequestsToday = 2
        )

        var charged = false
        var cascaded = false
        val reply = engine.askCoach(
            question = "Tips for calmer bedtimes?",
            context = baseContext(),
            prefs = prefs
        ) { c, cas ->
            charged = c
            cascaded = cas
        }

        assertEquals(
            listOf(AiModelConfig.PRIMARY_REASONING_MODEL, AiModelConfig.ECONOMY_LITE_MODEL),
            calledModels
        )
        assertEquals(AiResponseOrigin.CLOUD_BYOK_ECO_LITE, reply.origin)
        assertTrue(reply.cascadedFromStandard)
        assertTrue(charged)
        assertTrue(cascaded)
    }

    @Test
    fun autoEcoLiteThreshold_switchesToEconomyTierAtEightyPercentQuota() {
        val healthyPrefs = AiCoachPreferences(
            mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
            byokApiKey = validTestKey,
            model = ByokGeminiModel.PRIMARY_STANDARD,
            ecoLiteMode = false,
            dailyCloudLimit = 10,
            cloudRequestsToday = 7
        )
        assertFalse(healthyPrefs.isSoftNudgeActive)
        assertEquals(ByokGeminiModel.PRIMARY_STANDARD, healthyPrefs.resolvedCloudModel())

        val nudgePrefs = healthyPrefs.copy(cloudRequestsToday = 8)
        assertTrue(nudgePrefs.isSoftNudgeActive)
        assertTrue(nudgePrefs.canClaimGraceCredits)
        assertEquals(ByokGeminiModel.ECONOMY_LITE, nudgePrefs.resolvedCloudModel())

        val withGrace = nudgePrefs.copy(
            cloudRequestsToday = 10,
            bonusGraceCredits = AiModelConfig.GRACE_CREDITS_GRANT,
            graceClaimedToday = true
        )
        assertEquals(13, withGrace.effectiveDailyLimit)
        assertEquals(3, withGrace.remainingCloudToday)
        assertFalse(withGrace.isQuotaExhausted)
        assertFalse(withGrace.canClaimGraceCredits)
    }

    @Test
    fun dedupCache_returnsZeroTokenCachedHitWithin45SecondsAndExpiresAfter() = runBlocking {
        var nowMs = 1_000_000L
        var networkCalls = 0
        val engine = ParentAiCoachEngine(
            transport = GeminiCloudTransport { _, _, _, _, _ ->
                networkCalls++
                CloudTransportResult.Success("Cloud coaching answer #$networkCalls")
            },
            clockMs = { nowMs }
        )

        val prefs = AiCoachPreferences(
            mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
            byokApiKey = validTestKey,
            dailyCloudLimit = 15,
            cloudRequestsToday = 1
        )

        var firstCharged = false
        val first = engine.askCoach("Draft a family phone agreement", baseContext(), prefs) { c, _ ->
            firstCharged = c
        }
        assertEquals(AiResponseOrigin.CLOUD_BYOK_STANDARD, first.origin)
        assertTrue(firstCharged)
        assertEquals(1, networkCalls)

        // Duplicate request 10 seconds later -> 0 cloud tokens
        nowMs += 10_000L
        var secondCharged = true
        val second = engine.askCoach("Draft a family phone agreement", baseContext(), prefs) { c, _ ->
            secondCharged = c
        }
        assertEquals(AiResponseOrigin.CACHED_ZERO_TOKEN, second.origin)
        assertFalse(secondCharged)
        assertEquals(1, networkCalls)
        assertEquals(first.text, second.text)

        // After 46 seconds -> cache expires and calls cloud again
        nowMs += 46_000L
        val third = engine.askCoach("Draft a family phone agreement", baseContext(), prefs)
        assertEquals(AiResponseOrigin.CLOUD_BYOK_STANDARD, third.origin)
        assertEquals(2, networkCalls)
    }

    @Test
    fun atomicDeductOnSuccess_doesNotChargeWhenCloudFailsAndFallsBackToLocal() = runBlocking {
        val engine = ParentAiCoachEngine(
            transport = GeminiCloudTransport { _, _, _, _, _ ->
                CloudTransportResult.Failure(AiFallbackReason.OFFLINE_OR_NETWORK, "No route")
            }
        )
        val prefs = AiCoachPreferences(
            mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
            byokApiKey = validTestKey,
            dailyCloudLimit = 15,
            cloudRequestsToday = 3
        )

        var charged = true
        val reply = engine.askCoach("How to handle bullying?", baseContext(), prefs) { c, _ ->
            charged = c
        }

        assertFalse(charged)
        assertEquals(AiResponseOrigin.ON_DEVICE_FALLBACK, reply.origin)
        assertEquals(AiFallbackReason.OFFLINE_OR_NETWORK, reply.fallbackReason)
        assertTrue(reply.text.isNotBlank())
    }

    @Test
    fun dailyQuotaExhausted_skipsNetworkAndFallsBackGracefully() = runBlocking {
        var transportCalled = false
        val engine = ParentAiCoachEngine(
            transport = GeminiCloudTransport { _, _, _, _, _ ->
                transportCalled = true
                CloudTransportResult.Success("should not execute")
            }
        )
        val exhaustedPrefs = AiCoachPreferences(
            mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
            byokApiKey = validTestKey,
            dailyCloudLimit = 10,
            cloudRequestsToday = 10
        )

        assertTrue(exhaustedPrefs.isQuotaExhausted)
        assertEquals(AiFallbackReason.DAILY_QUOTA_REACHED, engine.checkCloudEligibility(exhaustedPrefs))

        val reply = engine.askCoach("Bedtime tips", baseContext(), exhaustedPrefs)
        assertFalse(transportCalled)
        assertEquals(AiResponseOrigin.ON_DEVICE_FALLBACK, reply.origin)
        assertEquals(AiFallbackReason.DAILY_QUOTA_REACHED, reply.fallbackReason)
    }

    @Test
    fun slidingContextWindow_capsHistoryToSixRecentTurnsAndRedactsPii() = runBlocking {
        var capturedPrompt: String? = null
        val engine = ParentAiCoachEngine(
            transport = GeminiCloudTransport { _, _, _, userPrompt, _ ->
                capturedPrompt = userPrompt
                CloudTransportResult.Success("Short reply")
            }
        )
        val tenTurns = (1..10).map { idx ->
            (idx % 2 == 1) to "Turn #$idx email user$idx@test.com"
        }
        val prefs = AiCoachPreferences(
            mode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
            byokApiKey = validTestKey,
            dailyCloudLimit = 15,
            cloudRequestsToday = 0
        )

        engine.askCoach("Latest question", baseContext(turns = tenTurns), prefs)

        val prompt = capturedPrompt
        assertNotNull(prompt)
        assertTrue(prompt!!.contains("last ${AiModelConfig.SLIDING_CONTEXT_MAX_TURNS} turns"))
        assertFalse(prompt.contains("Turn #1 "))
        assertFalse(prompt.contains("Turn #4 "))
        assertTrue(prompt.contains("Turn #5 "))
        assertTrue(prompt.contains("Turn #10 "))
        assertFalse(prompt.contains("@test.com"))
        assertTrue(prompt.contains("[EMAIL]"))
    }
}
