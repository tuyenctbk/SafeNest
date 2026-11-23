package com.example

import com.example.analytics.AppUpdateInfo
import com.example.analytics.ChannelEligibilityStatus
import com.example.analytics.EngagementPromptType
import com.example.analytics.EngagementSnapshot
import com.example.analytics.PromptChannelState
import com.example.analytics.PromptContextFlags
import com.example.analytics.PromptSchedulerEngine
import com.example.analytics.PromptTriggerSource
import com.example.analytics.SuppressionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSchedulerEngineTest {

    private val baseInstallMs = 1_700_000_000_000L
    private val dayMs = PromptSchedulerEngine.ONE_DAY_MS
    private val minMs = PromptSchedulerEngine.ONE_MINUTE_MS

    private fun engagedSnapshot(nowMs: Long): EngagementSnapshot = EngagementSnapshot(
        installTimestampMs = baseInstallMs,
        totalSessions = 5,
        activeDaysCount = 4,
        lastActiveDayKey = "2026-11-22",
        currentSessionStartMs = nowMs - 60_000L,
        sessionPositiveActions = 2,
        totalPositiveActions = 10,
        engagementScore = 20,
        lastFrictionTimestampMs = 0L,
        lastAnyPromptShownMs = 0L,
        promptsShownThisSession = 0,
        consecutiveDismissals = 0,
        autoPromptsEnabled = true,
        recentPromptTimestampsMs = emptyList()
    )

    @Test
    fun neverShowsUpdateRateOrShareAtAppStart() {
        val now = baseInstallMs + 5 * dayMs
        val mandatoryUpdate = AppUpdateInfo(
            currentVersionCode = 1,
            latestVersionCode = 2,
            latestVersionName = "2.0.0",
            isMandatory = true
        )
        val decision = PromptSchedulerEngine.evaluate(
            snapshot = engagedSnapshot(now),
            trigger = PromptTriggerSource.APP_START,
            flags = PromptContextFlags(),
            updateInfo = mandatoryUpdate,
            nowMs = now
        )
        assertNull(decision.promptType)
        assertEquals(SuppressionReason.BLOCKED_APP_START, decision.reason)
    }

    @Test
    fun blocksRateDuringChildGuardOrHighAlertOrRecentFriction() {
        val now = baseInstallMs + 5 * dayMs
        val snap = engagedSnapshot(now)

        val childGuard = PromptSchedulerEngine.evaluate(
            snapshot = snap,
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(isChildGuardMode = true),
            updateInfo = null,
            nowMs = now
        )
        assertNull(childGuard.promptType)
        assertEquals(SuppressionReason.BLOCKED_SENSITIVE_CONTEXT, childGuard.reason)

        val highAlert = PromptSchedulerEngine.evaluate(
            snapshot = snap,
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(hasUnreviewedHighSeverityAlert = true),
            updateInfo = null,
            nowMs = now
        )
        assertNull(highAlert.promptType)
        assertEquals(SuppressionReason.BLOCKED_SENSITIVE_CONTEXT, highAlert.reason)

        val friction = PromptSchedulerEngine.evaluate(
            snapshot = snap.copy(lastFrictionTimestampMs = now - 2 * minMs),
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(),
            updateInfo = null,
            nowMs = now
        )
        assertNull(friction.promptType)
        assertEquals(SuppressionReason.BLOCKED_RECENT_FRICTION, friction.reason)
    }

    @Test
    fun enforcesDwellTimeSessionCapGlobalCooldownAndMonthlyCap() {
        val now = baseInstallMs + 6 * dayMs
        val snap = engagedSnapshot(now)

        // Too soon after opening (< 30s dwell)
        val tooEarly = PromptSchedulerEngine.evaluate(
            snapshot = snap.copy(currentSessionStartMs = now - 5_000L),
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(),
            updateInfo = null,
            nowMs = now
        )
        assertNull(tooEarly.promptType)
        assertEquals(SuppressionReason.BLOCKED_MIN_DWELL_TIME, tooEarly.reason)

        // Max 1 popup per session
        val sessionCap = PromptSchedulerEngine.evaluate(
            snapshot = snap.copy(promptsShownThisSession = 1),
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(),
            updateInfo = null,
            nowMs = now
        )
        assertNull(sessionCap.promptType)
        assertEquals(SuppressionReason.BLOCKED_SESSION_CAP, sessionCap.reason)

        // Global 72h cross-prompt cooldown
        val globalCooldown = PromptSchedulerEngine.evaluate(
            snapshot = snap.copy(lastAnyPromptShownMs = now - 2 * dayMs),
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(),
            updateInfo = null,
            nowMs = now
        )
        assertNull(globalCooldown.promptType)
        assertEquals(SuppressionReason.BLOCKED_GLOBAL_COOLDOWN, globalCooldown.reason)

        // Rolling 30-day cap (max 2)
        val monthlyCap = PromptSchedulerEngine.evaluate(
            snapshot = snap.copy(
                lastAnyPromptShownMs = now - 5 * dayMs,
                recentPromptTimestampsMs = listOf(now - 10 * dayMs, now - 5 * dayMs)
            ),
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(),
            updateInfo = null,
            nowMs = now
        )
        assertNull(monthlyCap.promptType)
        assertEquals(SuppressionReason.BLOCKED_MONTHLY_CAP, monthlyCap.reason)
    }

    @Test
    fun enforcesActiveDaysGateAndConsecutiveDismissalFatigueEscalation() {
        val now = baseInstallMs + 6 * dayMs
        val snap = engagedSnapshot(now)

        // Opened 5 times all on 1 calendar day -> activeDaysCount=1 blocks Rate/Share
        val singleDayUser = PromptSchedulerEngine.evaluate(
            snapshot = snap.copy(activeDaysCount = 1),
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(),
            updateInfo = null,
            nowMs = now
        )
        assertNull(singleDayUser.promptType)
        assertEquals(SuppressionReason.BLOCKED_NO_CANDIDATE_READY, singleDayUser.reason)

        // 2 consecutive dismissals escalates global cooldown from 3 days to 7 days
        val fatigueBlocked = PromptSchedulerEngine.evaluate(
            snapshot = snap.copy(
                consecutiveDismissals = 2,
                lastAnyPromptShownMs = now - 4 * dayMs
            ),
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(),
            updateInfo = null,
            nowMs = now
        )
        assertNull(fatigueBlocked.promptType)
        assertEquals(SuppressionReason.BLOCKED_FATIGUE_COOLDOWN, fatigueBlocked.reason)

        // After 7+ days have passed, fatigue cooldown clears
        val afterFatigue = PromptSchedulerEngine.evaluate(
            snapshot = snap.copy(
                consecutiveDismissals = 2,
                lastAnyPromptShownMs = now - 8 * dayMs
            ),
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(),
            updateInfo = null,
            nowMs = now
        )
        assertEquals(EngagementPromptType.RATE, afterFatigue.promptType)
    }

    @Test
    fun respectsMasterAutoPromptsToggleExceptMandatoryUpdate() {
        val now = baseInstallMs + 6 * dayMs
        val disabledSnap = engagedSnapshot(now).copy(autoPromptsEnabled = false)

        val blockedRate = PromptSchedulerEngine.evaluate(
            snapshot = disabledSnap,
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(),
            updateInfo = null,
            nowMs = now
        )
        assertNull(blockedRate.promptType)
        assertEquals(SuppressionReason.BLOCKED_USER_DISABLED, blockedRate.reason)

        val mandatory = AppUpdateInfo(
            currentVersionCode = 1,
            latestVersionCode = 2,
            latestVersionName = "1.2.0",
            isMandatory = true
        )
        val allowedMandatory = PromptSchedulerEngine.evaluate(
            snapshot = disabledSnap,
            trigger = PromptTriggerSource.SESSION_SETTLED,
            flags = PromptContextFlags(),
            updateInfo = mandatory,
            nowMs = now
        )
        assertEquals(EngagementPromptType.UPDATE, allowedMandatory.promptType)
    }

    @Test
    fun triggersRateThenShareWithBackoffAndOptOutRespected() {
        val now = baseInstallMs + 5 * dayMs
        val snap = engagedSnapshot(now)

        // Eligible for RATE on positive moment
        val rateDecision = PromptSchedulerEngine.evaluate(
            snapshot = snap,
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(),
            updateInfo = null,
            nowMs = now
        )
        assertEquals(EngagementPromptType.RATE, rateDecision.promptType)

        // After RATE completed & global cooldown elapsed, SHARE becomes eligible
        val later = now + 4 * dayMs
        val shareDecision = PromptSchedulerEngine.evaluate(
            snapshot = snap.copy(
                currentSessionStartMs = later - 45_000L,
                lastAnyPromptShownMs = now,
                recentPromptTimestampsMs = listOf(now),
                rateState = PromptChannelState(shownCount = 1, completed = true, lastShownMs = now)
            ),
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(),
            updateInfo = null,
            nowMs = later
        )
        assertEquals(EngagementPromptType.SHARE, shareDecision.promptType)

        // If user opted out of both Rate and Share, nothing shows
        val optedOut = PromptSchedulerEngine.evaluate(
            snapshot = snap.copy(
                rateState = PromptChannelState(optedOut = true),
                shareState = PromptChannelState(optedOut = true)
            ),
            trigger = PromptTriggerSource.POSITIVE_MOMENT,
            flags = PromptContextFlags(),
            updateInfo = null,
            nowMs = now
        )
        assertNull(optedOut.promptType)
        assertEquals(SuppressionReason.BLOCKED_NO_CANDIDATE_READY, optedOut.reason)
    }

    @Test
    fun optionalUpdateRespectsSettledSessionAndSkipVersion() {
        val now = baseInstallMs + 5 * dayMs
        val snap = engagedSnapshot(now)
        val update = AppUpdateInfo(
            currentVersionCode = 1,
            latestVersionCode = 2,
            latestVersionName = "1.1.0",
            releaseNotes = "Improved DNS speed"
        )

        val shown = PromptSchedulerEngine.evaluate(
            snapshot = snap,
            trigger = PromptTriggerSource.SESSION_SETTLED,
            flags = PromptContextFlags(),
            updateInfo = update,
            nowMs = now
        )
        assertEquals(EngagementPromptType.UPDATE, shown.promptType)

        val skipped = PromptSchedulerEngine.evaluate(
            snapshot = snap.copy(
                updateState = PromptChannelState(
                    shownCount = 1,
                    dismissCount = 1,
                    optedOut = true,
                    lastDismissedVersionCode = 2
                )
            ),
            trigger = PromptTriggerSource.SESSION_SETTLED,
            flags = PromptContextFlags(),
            updateInfo = update,
            nowMs = now
        )
        assertNull(skipped.promptType)
    }

    @Test
    fun summarizeReadinessReflectsChannelStatesAndProgress() {
        val now = baseInstallMs + 5 * dayMs
        val snap = engagedSnapshot(now)
        val summary = PromptSchedulerEngine.summarizeReadiness(snap, updateInfo = null, nowMs = now)
        assertEquals(100, summary.readinessPercent)
        assertEquals(0L, summary.effectiveCooldownHoursRemaining)
        assertEquals(ChannelEligibilityStatus.COMPLETED, summary.updateStatus)
        assertEquals(ChannelEligibilityStatus.READY, summary.rateStatus)
        assertEquals(ChannelEligibilityStatus.READY, summary.shareStatus)

        val coolingSummary = PromptSchedulerEngine.summarizeReadiness(
            snap.copy(consecutiveDismissals = 2, lastAnyPromptShownMs = now - 2 * dayMs),
            updateInfo = null,
            nowMs = now
        )
        assertTrue(coolingSummary.effectiveCooldownHoursRemaining >= 119L)
    }
}
