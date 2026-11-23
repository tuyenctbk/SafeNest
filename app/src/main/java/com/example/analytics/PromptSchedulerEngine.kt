package com.example.analytics

import kotlin.math.min

/**
 * Types of automated engagement prompts managed by [PromptSchedulerEngine].
 */
enum class EngagementPromptType {
    UPDATE,
    RATE,
    SHARE
}

/**
 * Context in which the scheduler is consulted.
 * [APP_START] is explicitly blocked from triggering any auto-popup.
 */
enum class PromptTriggerSource {
    APP_START,
    SESSION_SETTLED,
    POSITIVE_MOMENT,
    MANUAL
}

/**
 * User actions taken on an engagement popup, driving cooldowns, fatigue backoff & lifetime suppression.
 */
enum class PromptOutcome {
    ACCEPTED,
    DISMISSED_LATER,
    OPTED_OUT
}

/**
 * Typed app events logged locally for engagement intelligence & diagnostics.
 * Contains zero PII, zero chat content, and zero browsing history.
 */
enum class AppEventName(
    val positiveWeight: Int = 0,
    val isFriction: Boolean = false
) {
    APP_SESSION_STARTED(positiveWeight = 0),
    APP_FOREGROUNDED(positiveWeight = 0),
    APP_BACKGROUNDED(positiveWeight = 0),
    TAB_VIEWED(positiveWeight = 0),
    SIGN_IN_COMPLETED(positiveWeight = 1),
    SIGN_OUT(positiveWeight = 0),

    // Positive value moments (earn eligibility for Rate/Share)
    CHILD_PROFILE_CREATED(positiveWeight = 3),
    DNS_SHIELD_STARTED(positiveWeight = 3),
    DNS_SHIELD_STOPPED(positiveWeight = 0),
    DNS_SHIELD_RULE_TOGGLED(positiveWeight = 1),
    CUSTOM_DOMAIN_BLOCKED(positiveWeight = 2),
    BEDTIME_SCHEDULE_UPDATED(positiveWeight = 2),
    CATEGORY_LIMIT_UPDATED(positiveWeight = 2),
    CATEGORY_PRESET_APPLIED(positiveWeight = 2),
    USAGE_SYNC_COMPLETED(positiveWeight = 2),
    WEEKLY_SUMMARY_VIEWED(positiveWeight = 1),
    WEEKLY_PUSH_TRIGGERED(positiveWeight = 2),
    ALERT_REVIEWED(positiveWeight = 1),
    ALL_ALERTS_REVIEWED(positiveWeight = 2),
    EDGE_AI_LAB_SCAN_RUN(positiveWeight = 1),
    DNS_LAB_TEST_RUN(positiveWeight = 1),
    AI_COACH_QUERY(positiveWeight = 1),
    AI_RISK_CHECK_RUN(positiveWeight = 2),
    AI_BYOK_CONFIGURED(positiveWeight = 2),
    PARENT_PIN_CONFIGURED(positiveWeight = 3),
    CHILD_GUARD_MODE_ENABLED(positiveWeight = 2),

    // Friction signals (suppress Rate/Share prompts during calm-down window)
    ACTION_ERROR_OCCURRED(isFriction = true),
    PERMISSION_DISCLOSURE_DECLINED(isFriction = true),
    PIN_ENTRY_FAILED(isFriction = true),

    // Prompt lifecycle telemetry
    PROMPT_SHOWN(positiveWeight = 0),
    PROMPT_ACCEPTED(positiveWeight = 0),
    PROMPT_DISMISSED(positiveWeight = 0),
    PROMPT_OPTED_OUT(positiveWeight = 0)
}

data class TrackedAppEvent(
    val name: AppEventName,
    val timestampMs: Long,
    val detail: String = ""
)

data class AppUpdateInfo(
    val currentVersionCode: Int,
    val latestVersionCode: Int,
    val latestVersionName: String,
    val releaseNotes: String = "",
    val isMandatory: Boolean = false
) {
    val hasUpdate: Boolean get() = latestVersionCode > currentVersionCode
}

data class PromptChannelState(
    val shownCount: Int = 0,
    val dismissCount: Int = 0,
    val lastShownMs: Long = 0L,
    val completed: Boolean = false,
    val optedOut: Boolean = false,
    val lastDismissedVersionCode: Int = 0
)

/**
 * Snapshot of persisted + in-session engagement metrics consumed by [PromptSchedulerEngine].
 */
data class EngagementSnapshot(
    val installTimestampMs: Long = 0L,
    val totalSessions: Int = 0,
    val activeDaysCount: Int = 0,
    val lastActiveDayKey: String = "",
    val currentSessionStartMs: Long = 0L,
    val sessionPositiveActions: Int = 0,
    val totalPositiveActions: Int = 0,
    val engagementScore: Int = 0,
    val lastFrictionTimestampMs: Long = 0L,
    val lastAnyPromptShownMs: Long = 0L,
    val promptsShownThisSession: Int = 0,
    val consecutiveDismissals: Int = 0,
    val autoPromptsEnabled: Boolean = true,
    val recentPromptTimestampsMs: List<Long> = emptyList(),
    val updateState: PromptChannelState = PromptChannelState(),
    val rateState: PromptChannelState = PromptChannelState(),
    val shareState: PromptChannelState = PromptChannelState()
)

data class PromptContextFlags(
    val isChildGuardMode: Boolean = false,
    val hasOpenModal: Boolean = false,
    val hasUnreviewedHighSeverityAlert: Boolean = false,
    val isOnBlockingFlow: Boolean = false
)

enum class SuppressionReason {
    ELIGIBLE,
    BLOCKED_USER_DISABLED,
    BLOCKED_APP_START,
    BLOCKED_SENSITIVE_CONTEXT,
    BLOCKED_SESSION_CAP,
    BLOCKED_GLOBAL_COOLDOWN,
    BLOCKED_FATIGUE_COOLDOWN,
    BLOCKED_MONTHLY_CAP,
    BLOCKED_RECENT_FRICTION,
    BLOCKED_MIN_DWELL_TIME,
    BLOCKED_INSUFFICIENT_ENGAGEMENT,
    BLOCKED_NO_CANDIDATE_READY
}

data class PromptDecision(
    val promptType: EngagementPromptType?,
    val reason: SuppressionReason,
    val updateInfo: AppUpdateInfo? = null
)

enum class ChannelEligibilityStatus {
    READY,
    COMPLETED,
    OPTED_OUT,
    COOLDOWN,
    LOCKED
}

data class PromptReadinessSummary(
    val readinessPercent: Int,
    val effectiveCooldownHoursRemaining: Long,
    val updateStatus: ChannelEligibilityStatus,
    val rateStatus: ChannelEligibilityStatus,
    val shareStatus: ChannelEligibilityStatus
)

/**
 * Pure, deterministic scheduling algorithm for Update, Rate, and Share auto-popups.
 *
 * Anti-annoyance guarantees:
 * 1. Never shows Update, Rate, or Share on cold app startup ([PromptTriggerSource.APP_START]).
 * 2. Requires minimum session dwell time, distinct active days, and completed positive value moments.
 * 3. Suppresses prompts during Child Guard Mode, open modals, active forms, high-severity alerts, or after recent friction/errors.
 * 4. Enforces strict session cap (max 1 popup/session), global cross-prompt cooldown (72h, escalating to 7d on consecutive dismissals),
 *    and a rolling 30-day cap (max 2 non-critical popups).
 * 5. Applies exponential per-channel backoff (14d -> 30d -> stop for Rate/Share; 3d -> 6d -> 12d for Update) and honors permanent opt-outs.
 * 6. Respects the parent's master Auto-Suggestions toggle (only critical mandatory security updates bypass when settled).
 */
object PromptSchedulerEngine {

    const val ONE_MINUTE_MS: Long = 60_000L
    const val ONE_HOUR_MS: Long = 60L * ONE_MINUTE_MS
    const val ONE_DAY_MS: Long = 24L * ONE_HOUR_MS

    // Session boundary: backgrounding >= 30 min starts a fresh session
    const val SESSION_GAP_MS: Long = 30L * ONE_MINUTE_MS

    // Global anti-annoyance thresholds
    const val MAX_PROMPTS_PER_SESSION = 1
    const val GLOBAL_CROSS_PROMPT_COOLDOWN_MS = 3L * ONE_DAY_MS          // 72 hours between any 2 prompts
    const val FATIGUE_ESCALATED_COOLDOWN_MS = 7L * ONE_DAY_MS            // 7 days if user dismissed 2+ prompts in a row
    const val FATIGUE_DISMISSAL_THRESHOLD = 2
    const val ROLLING_30_DAY_WINDOW_MS = 30L * ONE_DAY_MS
    const val MAX_NON_CRITICAL_PROMPTS_PER_30_DAYS = 2
    const val FRICTION_SUPPRESSION_WINDOW_MS = 10L * ONE_MINUTE_MS       // 10 min calm-down after errors

    // Dwell time requirements (never interrupt immediately on open)
    const val MIN_SESSION_DWELL_UPDATE_MS = 15_000L                      // 15s for optional update
    const val MIN_SESSION_DWELL_ENGAGEMENT_MS = 30_000L                  // 30s for Rate / Share

    // Rate App eligibility gates
    const val RATE_MIN_SESSIONS = 3
    const val RATE_MIN_ACTIVE_DAYS = 2
    const val RATE_MIN_DAYS_INSTALLED = 2
    const val RATE_MIN_POSITIVE_ACTIONS = 5
    const val RATE_MIN_ENGAGEMENT_SCORE = 10
    const val RATE_MAX_LIFETIME_PROMPTS = 3

    // Share App eligibility gates
    const val SHARE_MIN_SESSIONS = 4
    const val SHARE_MIN_ACTIVE_DAYS = 3
    const val SHARE_MIN_DAYS_INSTALLED = 3
    const val SHARE_MIN_POSITIVE_ACTIONS = 7
    const val SHARE_MIN_ENGAGEMENT_SCORE = 14
    const val SHARE_MAX_LIFETIME_PROMPTS = 3
    const val SHARE_POST_COMPLETE_COOLDOWN_MS = 60L * ONE_DAY_MS

    // Update eligibility gates
    const val UPDATE_MAX_DISMISSES_PER_VERSION = 3

    fun evaluate(
        snapshot: EngagementSnapshot,
        trigger: PromptTriggerSource,
        flags: PromptContextFlags,
        updateInfo: AppUpdateInfo?,
        nowMs: Long
    ): PromptDecision {
        // 1. Never interrupt sensitive, modal, or active-form states
        if (flags.isChildGuardMode || flags.hasOpenModal || flags.isOnBlockingFlow) {
            return PromptDecision(null, SuppressionReason.BLOCKED_SENSITIVE_CONTEXT)
        }

        // 2. Hard rule: NEVER auto-popup at cold app start (even mandatory update waits for session settle)
        if (trigger == PromptTriggerSource.APP_START) {
            return PromptDecision(null, SuppressionReason.BLOCKED_APP_START)
        }

        // 3. Mandatory critical update can bypass non-critical caps once session settles
        if (updateInfo != null && updateInfo.hasUpdate && updateInfo.isMandatory) {
            if (snapshot.promptsShownThisSession < MAX_PROMPTS_PER_SESSION) {
                return PromptDecision(EngagementPromptType.UPDATE, SuppressionReason.ELIGIBLE, updateInfo)
            }
            return PromptDecision(null, SuppressionReason.BLOCKED_SESSION_CAP)
        }

        // 4. Respect master auto-suggestions preference
        if (!snapshot.autoPromptsEnabled) {
            return PromptDecision(null, SuppressionReason.BLOCKED_USER_DISABLED)
        }

        // 5. Hard rule: Max 1 auto-popup per session
        if (snapshot.promptsShownThisSession >= MAX_PROMPTS_PER_SESSION) {
            return PromptDecision(null, SuppressionReason.BLOCKED_SESSION_CAP)
        }

        // 6. Hard rule: Cross-prompt global cooldown (72h standard, escalated to 7 days after 2+ consecutive dismissals)
        val requiredCooldownMs = effectiveGlobalCooldownMs(snapshot)
        if (snapshot.lastAnyPromptShownMs > 0L &&
            (nowMs - snapshot.lastAnyPromptShownMs) < requiredCooldownMs
        ) {
            val reason = if (snapshot.consecutiveDismissals >= FATIGUE_DISMISSAL_THRESHOLD) {
                SuppressionReason.BLOCKED_FATIGUE_COOLDOWN
            } else {
                SuppressionReason.BLOCKED_GLOBAL_COOLDOWN
            }
            return PromptDecision(null, reason)
        }

        // 7. Hard rule: Rolling 30-day cap across non-critical popups
        val promptsInLast30Days = snapshot.recentPromptTimestampsMs.count { ts ->
            ts > 0L && (nowMs - ts) in 0L..ROLLING_30_DAY_WINDOW_MS
        }
        if (promptsInLast30Days >= MAX_NON_CRITICAL_PROMPTS_PER_30_DAYS) {
            return PromptDecision(null, SuppressionReason.BLOCKED_MONTHLY_CAP)
        }

        val sessionDwellMs = if (snapshot.currentSessionStartMs > 0L) {
            (nowMs - snapshot.currentSessionStartMs).coerceAtLeast(0L)
        } else 0L

        // Priority 1: Optional App Update (when a newer version actually exists)
        if (updateInfo != null && updateInfo.hasUpdate) {
            if (sessionDwellMs >= MIN_SESSION_DWELL_UPDATE_MS &&
                isUpdateEligible(snapshot.updateState, updateInfo, nowMs)
            ) {
                return PromptDecision(EngagementPromptType.UPDATE, SuppressionReason.ELIGIBLE, updateInfo)
            }
        }

        // Rate & Share are strictly suppressed during high-severity safety alerts or right after friction/errors
        if (flags.hasUnreviewedHighSeverityAlert) {
            return PromptDecision(null, SuppressionReason.BLOCKED_SENSITIVE_CONTEXT)
        }
        if (snapshot.lastFrictionTimestampMs > 0L &&
            (nowMs - snapshot.lastFrictionTimestampMs) < FRICTION_SUPPRESSION_WINDOW_MS
        ) {
            return PromptDecision(null, SuppressionReason.BLOCKED_RECENT_FRICTION)
        }

        // Rate & Share require a genuine positive value moment (never passive tab open or start)
        if (trigger != PromptTriggerSource.POSITIVE_MOMENT) {
            return PromptDecision(null, SuppressionReason.BLOCKED_INSUFFICIENT_ENGAGEMENT)
        }

        // Minimum session dwell time + at least 1 positive action in this session
        if (sessionDwellMs < MIN_SESSION_DWELL_ENGAGEMENT_MS) {
            return PromptDecision(null, SuppressionReason.BLOCKED_MIN_DWELL_TIME)
        }
        if (snapshot.sessionPositiveActions < 1) {
            return PromptDecision(null, SuppressionReason.BLOCKED_INSUFFICIENT_ENGAGEMENT)
        }

        val daysInstalled = computeDaysInstalled(snapshot, nowMs)
        val effectiveActiveDays = effectiveActiveDaysCount(snapshot, daysInstalled)

        // Priority 2: Rate App (strictly earned after sustained value across multiple days)
        if (isRateEligible(snapshot, daysInstalled, effectiveActiveDays, nowMs)) {
            return PromptDecision(EngagementPromptType.RATE, SuppressionReason.ELIGIBLE)
        }

        // Priority 3: Share App (advocacy after deeper engagement or after rating)
        if (isShareEligible(snapshot, daysInstalled, effectiveActiveDays, nowMs)) {
            return PromptDecision(EngagementPromptType.SHARE, SuppressionReason.ELIGIBLE)
        }

        return PromptDecision(null, SuppressionReason.BLOCKED_NO_CANDIDATE_READY)
    }

    /**
     * Computes an inspectable readiness report for parents & diagnostics UI.
     */
    fun summarizeReadiness(
        snapshot: EngagementSnapshot,
        updateInfo: AppUpdateInfo?,
        nowMs: Long
    ): PromptReadinessSummary {
        val daysInstalled = computeDaysInstalled(snapshot, nowMs)
        val activeDays = effectiveActiveDaysCount(snapshot, daysInstalled)

        val sessionProgress = (snapshot.totalSessions.toFloat() / RATE_MIN_SESSIONS).coerceIn(0f, 1f)
        val activeDaysProgress = (activeDays.toFloat() / RATE_MIN_ACTIVE_DAYS).coerceIn(0f, 1f)
        val actionsProgress = (snapshot.totalPositiveActions.toFloat() / RATE_MIN_POSITIVE_ACTIONS).coerceIn(0f, 1f)
        val scoreProgress = (snapshot.engagementScore.toFloat() / RATE_MIN_ENGAGEMENT_SCORE).coerceIn(0f, 1f)
        val readinessPercent = (((sessionProgress + activeDaysProgress + actionsProgress + scoreProgress) / 4f) * 100f)
            .toInt()
            .coerceIn(0, 100)

        val updateStatus = when {
            updateInfo == null || !updateInfo.hasUpdate -> ChannelEligibilityStatus.COMPLETED
            snapshot.updateState.optedOut &&
                snapshot.updateState.lastDismissedVersionCode == updateInfo.latestVersionCode -> ChannelEligibilityStatus.OPTED_OUT
            isUpdateEligible(snapshot.updateState, updateInfo, nowMs) -> ChannelEligibilityStatus.READY
            else -> ChannelEligibilityStatus.COOLDOWN
        }

        val rateStatus = when {
            snapshot.rateState.completed -> ChannelEligibilityStatus.COMPLETED
            snapshot.rateState.optedOut || snapshot.rateState.shownCount >= RATE_MAX_LIFETIME_PROMPTS -> ChannelEligibilityStatus.OPTED_OUT
            isRateEligible(snapshot, daysInstalled, activeDays, nowMs) -> ChannelEligibilityStatus.READY
            snapshot.rateState.lastShownMs > 0L -> ChannelEligibilityStatus.COOLDOWN
            else -> ChannelEligibilityStatus.LOCKED
        }

        val shareStatus = when {
            snapshot.shareState.optedOut || snapshot.shareState.shownCount >= SHARE_MAX_LIFETIME_PROMPTS -> ChannelEligibilityStatus.OPTED_OUT
            snapshot.shareState.completed &&
                !isShareEligible(snapshot, daysInstalled, activeDays, nowMs) -> ChannelEligibilityStatus.COMPLETED
            isShareEligible(snapshot, daysInstalled, activeDays, nowMs) -> ChannelEligibilityStatus.READY
            snapshot.shareState.lastShownMs > 0L -> ChannelEligibilityStatus.COOLDOWN
            else -> ChannelEligibilityStatus.LOCKED
        }

        return PromptReadinessSummary(
            readinessPercent = readinessPercent,
            effectiveCooldownHoursRemaining = remainingGlobalCooldownHours(snapshot, nowMs),
            updateStatus = updateStatus,
            rateStatus = rateStatus,
            shareStatus = shareStatus
        )
    }

    fun effectiveGlobalCooldownMs(snapshot: EngagementSnapshot): Long =
        if (snapshot.consecutiveDismissals >= FATIGUE_DISMISSAL_THRESHOLD) {
            FATIGUE_ESCALATED_COOLDOWN_MS
        } else {
            GLOBAL_CROSS_PROMPT_COOLDOWN_MS
        }

    private fun computeDaysInstalled(snapshot: EngagementSnapshot, nowMs: Long): Int =
        if (snapshot.installTimestampMs > 0L) {
            ((nowMs - snapshot.installTimestampMs).coerceAtLeast(0L) / ONE_DAY_MS).toInt()
        } else 0

    /**
     * Backward-compatible helper: if a snapshot predates [activeDaysCount] tracking (0 with sessions > 0),
     * conservatively infer at most `min(totalSessions, daysInstalled + 1)`.
     */
    private fun effectiveActiveDaysCount(snapshot: EngagementSnapshot, daysInstalled: Int): Int =
        if (snapshot.activeDaysCount > 0) {
            snapshot.activeDaysCount
        } else if (snapshot.totalSessions > 0) {
            min(snapshot.totalSessions, daysInstalled + 1)
        } else {
            0
        }

    private fun isUpdateEligible(
        state: PromptChannelState,
        updateInfo: AppUpdateInfo,
        nowMs: Long
    ): Boolean {
        if (state.optedOut && state.lastDismissedVersionCode == updateInfo.latestVersionCode) {
            return false
        }
        val effectiveDismisses = if (state.lastDismissedVersionCode == updateInfo.latestVersionCode) {
            state.dismissCount
        } else {
            0
        }
        if (effectiveDismisses >= UPDATE_MAX_DISMISSES_PER_VERSION) {
            return false
        }
        if (state.lastShownMs > 0L && state.lastDismissedVersionCode == updateInfo.latestVersionCode) {
            // Exponential backoff: 3 days -> 6 days -> 12 days (capped at 21 days)
            val multiplier = 1L shl min(effectiveDismisses, 3)
            val cooldownMs = min(3L * ONE_DAY_MS * multiplier / 2L, 21L * ONE_DAY_MS)
                .coerceAtLeast(3L * ONE_DAY_MS)
            if ((nowMs - state.lastShownMs) < cooldownMs) return false
        }
        return true
    }

    private fun isRateEligible(
        snapshot: EngagementSnapshot,
        daysInstalled: Int,
        activeDaysCount: Int,
        nowMs: Long
    ): Boolean {
        val state = snapshot.rateState
        if (state.completed || state.optedOut) return false
        if (state.shownCount >= RATE_MAX_LIFETIME_PROMPTS) return false

        if (snapshot.totalSessions < RATE_MIN_SESSIONS) return false
        if (daysInstalled < RATE_MIN_DAYS_INSTALLED) return false
        if (activeDaysCount < RATE_MIN_ACTIVE_DAYS) return false
        if (snapshot.totalPositiveActions < RATE_MIN_POSITIVE_ACTIONS) return false
        if (snapshot.engagementScore < RATE_MIN_ENGAGEMENT_SCORE) return false

        if (state.lastShownMs > 0L) {
            // Exponential backoff: 14 days after 1st dismiss, 30 days after 2nd dismiss
            val backoffDays = when (state.dismissCount) {
                0 -> 14L
                1 -> 14L
                else -> 30L
            }
            if ((nowMs - state.lastShownMs) < backoffDays * ONE_DAY_MS) return false
        }
        return true
    }

    private fun isShareEligible(
        snapshot: EngagementSnapshot,
        daysInstalled: Int,
        activeDaysCount: Int,
        nowMs: Long
    ): Boolean {
        val state = snapshot.shareState
        if (state.optedOut) return false
        if (state.shownCount >= SHARE_MAX_LIFETIME_PROMPTS) return false

        if (snapshot.totalSessions < SHARE_MIN_SESSIONS) return false
        if (daysInstalled < SHARE_MIN_DAYS_INSTALLED) return false
        if (activeDaysCount < SHARE_MIN_ACTIVE_DAYS) return false
        if (snapshot.totalPositiveActions < SHARE_MIN_POSITIVE_ACTIONS) return false
        if (snapshot.engagementScore < SHARE_MIN_ENGAGEMENT_SCORE) return false

        if (state.completed && state.lastShownMs > 0L) {
            if ((nowMs - state.lastShownMs) < SHARE_POST_COMPLETE_COOLDOWN_MS) return false
        } else if (state.lastShownMs > 0L) {
            val backoffDays = when (state.dismissCount) {
                0 -> 14L
                1 -> 14L
                else -> 30L
            }
            if ((nowMs - state.lastShownMs) < backoffDays * ONE_DAY_MS) return false
        }
        return true
    }

    /**
     * Computes remaining hours until global cross-prompt (or fatigue-escalated) cooldown expires (0 if ready).
     */
    fun remainingGlobalCooldownHours(snapshot: EngagementSnapshot, nowMs: Long): Long {
        if (snapshot.lastAnyPromptShownMs <= 0L) return 0L
        val elapsed = (nowMs - snapshot.lastAnyPromptShownMs).coerceAtLeast(0L)
        val required = effectiveGlobalCooldownMs(snapshot)
        val remaining = (required - elapsed).coerceAtLeast(0L)
        return (remaining + ONE_HOUR_MS - 1L) / ONE_HOUR_MS
    }
}
