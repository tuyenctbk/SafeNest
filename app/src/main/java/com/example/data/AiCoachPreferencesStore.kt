package com.example.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Context.aiCoachDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "safenest_ai_coach_prefs"
)

/**
 * Centralized, model-agnostic capability tier configuration.
 * Never hardcode model IDs inside UI screens or ViewModels — update here only.
 */
object AiModelConfig {
    const val PRIMARY_REASONING_MODEL = "gemini-3.8-flash"
    const val ECONOMY_LITE_MODEL = "gemini-3.5-flash-lite"
    const val LOCAL_REFLEX_ENGINE_ID = "safenest-on-device-reflex-v1"

    const val SOFT_NUDGE_THRESHOLD_PERCENT = 80
    const val GRACE_CREDITS_GRANT = 3
    const val DEDUP_CACHE_TTL_MS = 45_000L
    const val SLIDING_CONTEXT_MAX_TURNS = 6
}

/**
 * Operating mode for SafeNest's Hybrid Parent AI Coach.
 * - [ON_DEVICE_ONLY]: 100% free, offline-capable local coaching & risk intelligence ($0 API cost).
 * - [BYOK_CLOUD_WITH_FALLBACK]: Uses the parent's own personal Gemini API key stored locally on-device,
 *   applying Tier 1 -> Tier 2 (Eco Lite) -> Tier 3 (On-Device) auto-cascade and daily budget protection.
 */
enum class AiEngineMode {
    ON_DEVICE_ONLY,
    BYOK_CLOUD_WITH_FALLBACK
}

/**
 * Capability tiers supported for optional BYOK cloud coaching.
 */
enum class ByokGeminiModel(
    val modelId: String,
    val displayLabel: String,
    @StringRes val labelRes: Int,
    val isEconomyTier: Boolean
) {
    PRIMARY_STANDARD(
        AiModelConfig.PRIMARY_REASONING_MODEL,
        "Gemini 3.8 Flash (Standard)",
        R.string.ai_model_primary_standard,
        false
    ),
    ECONOMY_LITE(
        AiModelConfig.ECONOMY_LITE_MODEL,
        "Gemini 3.5 Flash-Lite (Eco)",
        R.string.ai_model_economy_lite,
        true
    );

    companion object {
        fun fromModelId(id: String?): ByokGeminiModel =
            entries.firstOrNull { it.modelId.equals(id, ignoreCase = true) } ?: PRIMARY_STANDARD
    }
}

enum class AiFeatureChannel {
    COACH_CHAT,
    RISK_CHECK,
    WEEKLY_BRIEF,
    STUDIO,
    AUDIO
}

/**
 * Local-only AI Coach configuration combined with the cached hybrid quota ledger snapshot.
 * The BYOK API key is NEVER synced to Firebase Firestore or logged in analytics.
 */
data class AiCoachPreferences(
    val mode: AiEngineMode = AiEngineMode.ON_DEVICE_ONLY,
    val byokApiKey: String = "",
    val model: ByokGeminiModel = ByokGeminiModel.PRIMARY_STANDARD,
    val ecoLiteMode: Boolean = false,
    val dailyCloudLimit: Int = DEFAULT_DAILY_CLOUD_LIMIT,
    val cloudRequestsToday: Int = 0,
    val bonusGraceCredits: Int = 0,
    val graceClaimedToday: Boolean = false,
    val chatUsedToday: Int = 0,
    val riskChecksUsedToday: Int = 0,
    val briefsUsedToday: Int = 0,
    val studioUsedToday: Int = 0,
    val audioUsedToday: Int = 0,
    val fallbackCascadeCount: Int = 0,
    val usageDayKey: String = ""
) {
    val hasValidKeyFormat: Boolean get() = byokApiKey.trim().length >= 20
    val isCloudActive: Boolean get() = mode == AiEngineMode.BYOK_CLOUD_WITH_FALLBACK && hasValidKeyFormat
    val effectiveDailyLimit: Int get() = (dailyCloudLimit + bonusGraceCredits).coerceAtLeast(1)
    val remainingCloudToday: Int get() = (effectiveDailyLimit - cloudRequestsToday).coerceAtLeast(0)
    val usagePercent: Int get() = ((cloudRequestsToday * 100) / effectiveDailyLimit).coerceIn(0, 100)
    val isSoftNudgeActive: Boolean
        get() = isCloudActive && usagePercent >= AiModelConfig.SOFT_NUDGE_THRESHOLD_PERCENT && remainingCloudToday > 0
    val isQuotaExhausted: Boolean
        get() = isCloudActive && remainingCloudToday <= 0
    val canClaimGraceCredits: Boolean
        get() = isCloudActive && !graceClaimedToday && (isQuotaExhausted || isSoftNudgeActive)
    val shouldAutoUseEcoLite: Boolean
        get() = ecoLiteMode || model.isEconomyTier || usagePercent >= AiModelConfig.SOFT_NUDGE_THRESHOLD_PERCENT
    val totalValueActionsToday: Int
        get() = chatUsedToday + riskChecksUsedToday + briefsUsedToday + studioUsedToday + audioUsedToday

    fun resolvedCloudModel(): ByokGeminiModel =
        if (shouldAutoUseEcoLite) ByokGeminiModel.ECONOMY_LITE else model

    fun maskedKeyPreview(): String {
        val clean = byokApiKey.trim()
        if (clean.length <= 8) return if (clean.isEmpty()) "" else "••••"
        return "${clean.take(4)}••••${clean.takeLast(4)}"
    }

    companion object {
        const val MIN_DAILY_CLOUD_LIMIT = 5
        const val MAX_DAILY_CLOUD_LIMIT = 50
        const val DEFAULT_DAILY_CLOUD_LIMIT = 15

        /**
         * Returns (hours, minutes) remaining until local midnight quota rollover.
         */
        fun timeUntilMidnightReset(nowMs: Long = System.currentTimeMillis()): Pair<Int, Int> {
            val now = Calendar.getInstance().apply { timeInMillis = nowMs }
            val midnight = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val diffMin = ((midnight.timeInMillis - now.timeInMillis) / 60_000L).coerceAtLeast(1L).toInt()
            return (diffMin / 60) to (diffMin % 60)
        }
    }
}

class AiCoachPreferencesStore(
    private val context: Context,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) : QuotaLocalMirror {

    private val modeKey = stringPreferencesKey("ai_engine_mode")
    private val byokApiKeyPref = stringPreferencesKey("ai_byok_api_key_local")
    private val modelKey = stringPreferencesKey("ai_byok_model_id")
    private val ecoLiteKey = booleanPreferencesKey("ai_eco_lite_mode")
    private val dailyLimitKey = intPreferencesKey("ai_daily_cloud_limit")
    private val requestsTodayKey = intPreferencesKey("ai_cloud_requests_today")
    private val bonusGraceKey = intPreferencesKey("ai_bonus_grace_credits")
    private val graceClaimedKey = booleanPreferencesKey("ai_grace_claimed_today")
    private val chatUsedKey = intPreferencesKey("ai_chat_used_today")
    private val riskUsedKey = intPreferencesKey("ai_risk_used_today")
    private val briefsUsedKey = intPreferencesKey("ai_briefs_used_today")
    private val studioUsedKey = intPreferencesKey("ai_studio_used_today")
    private val audioUsedKey = intPreferencesKey("ai_audio_used_today")
    private val fallbackCascadeKey = intPreferencesKey("ai_fallback_cascade_count")
    private val usageDayKeyPref = stringPreferencesKey("ai_usage_day_key")

    override val preferencesFlow: Flow<AiCoachPreferences> = context.aiCoachDataStore.data.map { prefs ->
        decodePreferences(prefs, currentDayKey(clockMs()))
    }

    override suspend fun getPreferences(): AiCoachPreferences = preferencesFlow.first()

    override suspend fun saveConfiguration(
        mode: AiEngineMode,
        apiKey: String,
        model: ByokGeminiModel,
        dailyCloudLimit: Int,
        ecoLiteMode: Boolean
    ) {
        val cleanKey = apiKey.trim()
        val safeLimit = dailyCloudLimit.coerceIn(
            AiCoachPreferences.MIN_DAILY_CLOUD_LIMIT,
            AiCoachPreferences.MAX_DAILY_CLOUD_LIMIT
        )
        val effectiveMode = if (mode == AiEngineMode.BYOK_CLOUD_WITH_FALLBACK && cleanKey.isEmpty()) {
            AiEngineMode.ON_DEVICE_ONLY
        } else {
            mode
        }
        context.aiCoachDataStore.edit { prefs ->
            prefs[modeKey] = effectiveMode.name
            prefs[byokApiKeyPref] = cleanKey
            prefs[modelKey] = model.modelId
            prefs[dailyLimitKey] = safeLimit
            prefs[ecoLiteKey] = ecoLiteMode
        }
    }

    override suspend fun setEcoLiteMode(enabled: Boolean) {
        context.aiCoachDataStore.edit { prefs ->
            prefs[ecoLiteKey] = enabled
        }
    }

    override suspend fun switchToOnDeviceFreeMode() {
        context.aiCoachDataStore.edit { prefs ->
            prefs[modeKey] = AiEngineMode.ON_DEVICE_ONLY.name
        }
    }

    override suspend fun clearByokKey() {
        context.aiCoachDataStore.edit { prefs ->
            prefs[byokApiKeyPref] = ""
            prefs[modeKey] = AiEngineMode.ON_DEVICE_ONLY.name
        }
    }

    override suspend fun applyPeriodRolloverIfNeeded(periodIso: String): AiCoachPreferences {
        var snapshot = AiCoachPreferences()
        context.aiCoachDataStore.edit { prefs ->
            if (prefs[usageDayKeyPref].orEmpty() != periodIso) {
                resetDailyCountersInPlace(prefs, periodIso)
            }
            snapshot = decodePreferences(prefs, periodIso)
        }
        return snapshot
    }

    override suspend fun applyRemoteQuotaSnapshot(
        ledger: UsageQuotaLedger,
        currentPeriodIso: String
    ): AiCoachPreferences {
        var snapshot = AiCoachPreferences()
        context.aiCoachDataStore.edit { prefs ->
            val safeLimit = ledger.dailyCloudLimit.coerceIn(
                AiCoachPreferences.MIN_DAILY_CLOUD_LIMIT,
                AiCoachPreferences.MAX_DAILY_CLOUD_LIMIT
            )
            prefs[dailyLimitKey] = safeLimit
            prefs[ecoLiteKey] = ledger.ecoLiteMode
            if (ledger.dateIso == currentPeriodIso) {
                prefs[usageDayKeyPref] = currentPeriodIso
                prefs[requestsTodayKey] = ledger.cloudRequestsUsed.coerceAtLeast(0)
                prefs[chatUsedKey] = ledger.chatUsed.coerceAtLeast(0)
                prefs[riskUsedKey] = ledger.visionUsed.coerceAtLeast(0)
                prefs[briefsUsedKey] = ledger.briefsUsed.coerceAtLeast(0)
                prefs[studioUsedKey] = ledger.studioUsed.coerceAtLeast(0)
                prefs[audioUsedKey] = ledger.audioUsed.coerceAtLeast(0)
                prefs[bonusGraceKey] = ledger.bonusGraceCredits.coerceAtLeast(0)
                prefs[graceClaimedKey] = ledger.graceClaimed
                prefs[fallbackCascadeKey] = ledger.fallbackCascadeCount.coerceAtLeast(0)
            } else {
                // Automatic rollover reset while preserving synced ecoLiteMode & dailyCloudLimit
                resetDailyCountersInPlace(prefs, currentPeriodIso)
            }
            snapshot = decodePreferences(prefs, currentPeriodIso)
        }
        return snapshot
    }

    override suspend fun reserveCloudCreditLocally(periodIso: String): Pair<Boolean, AiCoachPreferences> {
        var reserved = false
        var snapshot = AiCoachPreferences()
        context.aiCoachDataStore.edit { prefs ->
            if (prefs[usageDayKeyPref].orEmpty() != periodIso) {
                resetDailyCountersInPlace(prefs, periodIso)
            }
            val current = decodePreferences(prefs, periodIso)
            if (current.remainingCloudToday > 0) {
                prefs[usageDayKeyPref] = periodIso
                prefs[requestsTodayKey] = current.cloudRequestsToday + 1
                reserved = true
            }
            snapshot = decodePreferences(prefs, periodIso)
        }
        return reserved to snapshot
    }

    override suspend fun refundCloudCreditLocally(periodIso: String): AiCoachPreferences {
        var snapshot = AiCoachPreferences()
        context.aiCoachDataStore.edit { prefs ->
            if (prefs[usageDayKeyPref].orEmpty() == periodIso) {
                val currentUsed = (prefs[requestsTodayKey] ?: 0).coerceAtLeast(0)
                prefs[requestsTodayKey] = (currentUsed - 1).coerceAtLeast(0)
            }
            snapshot = decodePreferences(prefs, periodIso)
        }
        return snapshot
    }

    override suspend fun recordFeatureCompletionLocally(
        channel: AiFeatureChannel,
        cascadedTier: Boolean,
        periodIso: String
    ): AiCoachPreferences {
        var snapshot = AiCoachPreferences()
        context.aiCoachDataStore.edit { prefs ->
            if (prefs[usageDayKeyPref].orEmpty() != periodIso) {
                resetDailyCountersInPlace(prefs, periodIso)
            }
            prefs[usageDayKeyPref] = periodIso
            incrementChannelKey(prefs, channel)
            if (cascadedTier) {
                prefs[fallbackCascadeKey] = (prefs[fallbackCascadeKey] ?: 0) + 1
            }
            snapshot = decodePreferences(prefs, periodIso)
        }
        return snapshot
    }

    /**
     * Grants one-time daily +3 Free Grace Credits so parents mid-task never hit a hard wall.
     * Automatically resets at midnight rollover.
     */
    override suspend fun claimDailyGraceCredits(periodIso: String): Boolean {
        var granted = false
        context.aiCoachDataStore.edit { prefs ->
            val isSameDay = prefs[usageDayKeyPref].orEmpty() == periodIso
            val alreadyClaimed = isSameDay && (prefs[graceClaimedKey] ?: false)
            if (!alreadyClaimed) {
                if (!isSameDay) {
                    resetDailyCountersInPlace(prefs, periodIso)
                }
                val currentBonus = if (isSameDay) (prefs[bonusGraceKey] ?: 0) else 0
                prefs[usageDayKeyPref] = periodIso
                prefs[bonusGraceKey] = currentBonus + AiModelConfig.GRACE_CREDITS_GRANT
                prefs[graceClaimedKey] = true
                granted = true
            }
        }
        return granted
    }

    /**
     * Legacy convenience helper for direct local recording without a pre-reservation ticket.
     */
    suspend fun recordInteraction(
        channel: AiFeatureChannel,
        chargedCloudCredit: Boolean,
        cascadedTier: Boolean
    ) {
        val todayKey = currentDayKey(clockMs())
        context.aiCoachDataStore.edit { prefs ->
            if (prefs[usageDayKeyPref].orEmpty() != todayKey) {
                resetDailyCountersInPlace(prefs, todayKey)
            }
            prefs[usageDayKeyPref] = todayKey
            incrementChannelKey(prefs, channel)
            if (chargedCloudCredit) {
                prefs[requestsTodayKey] = (prefs[requestsTodayKey] ?: 0) + 1
            }
            if (cascadedTier) {
                prefs[fallbackCascadeKey] = (prefs[fallbackCascadeKey] ?: 0) + 1
            }
        }
    }

    private fun incrementChannelKey(prefs: MutablePreferences, channel: AiFeatureChannel) {
        when (channel) {
            AiFeatureChannel.COACH_CHAT ->
                prefs[chatUsedKey] = (prefs[chatUsedKey] ?: 0) + 1
            AiFeatureChannel.RISK_CHECK ->
                prefs[riskUsedKey] = (prefs[riskUsedKey] ?: 0) + 1
            AiFeatureChannel.WEEKLY_BRIEF ->
                prefs[briefsUsedKey] = (prefs[briefsUsedKey] ?: 0) + 1
            AiFeatureChannel.STUDIO ->
                prefs[studioUsedKey] = (prefs[studioUsedKey] ?: 0) + 1
            AiFeatureChannel.AUDIO ->
                prefs[audioUsedKey] = (prefs[audioUsedKey] ?: 0) + 1
        }
    }

    private fun decodePreferences(prefs: Preferences, activePeriodIso: String): AiCoachPreferences {
        val isSamePeriod = prefs[usageDayKeyPref].orEmpty() == activePeriodIso

        val mode = runCatching {
            AiEngineMode.valueOf(prefs[modeKey] ?: AiEngineMode.ON_DEVICE_ONLY.name)
        }.getOrDefault(AiEngineMode.ON_DEVICE_ONLY)

        val limit = (prefs[dailyLimitKey] ?: AiCoachPreferences.DEFAULT_DAILY_CLOUD_LIMIT)
            .coerceIn(AiCoachPreferences.MIN_DAILY_CLOUD_LIMIT, AiCoachPreferences.MAX_DAILY_CLOUD_LIMIT)

        return AiCoachPreferences(
            mode = mode,
            byokApiKey = prefs[byokApiKeyPref].orEmpty(),
            model = ByokGeminiModel.fromModelId(prefs[modelKey]),
            ecoLiteMode = prefs[ecoLiteKey] ?: false,
            dailyCloudLimit = limit,
            cloudRequestsToday = if (isSamePeriod) (prefs[requestsTodayKey] ?: 0).coerceAtLeast(0) else 0,
            bonusGraceCredits = if (isSamePeriod) (prefs[bonusGraceKey] ?: 0).coerceAtLeast(0) else 0,
            graceClaimedToday = if (isSamePeriod) (prefs[graceClaimedKey] ?: false) else false,
            chatUsedToday = if (isSamePeriod) (prefs[chatUsedKey] ?: 0).coerceAtLeast(0) else 0,
            riskChecksUsedToday = if (isSamePeriod) (prefs[riskUsedKey] ?: 0).coerceAtLeast(0) else 0,
            briefsUsedToday = if (isSamePeriod) (prefs[briefsUsedKey] ?: 0).coerceAtLeast(0) else 0,
            studioUsedToday = if (isSamePeriod) (prefs[studioUsedKey] ?: 0).coerceAtLeast(0) else 0,
            audioUsedToday = if (isSamePeriod) (prefs[audioUsedKey] ?: 0).coerceAtLeast(0) else 0,
            fallbackCascadeCount = if (isSamePeriod) (prefs[fallbackCascadeKey] ?: 0).coerceAtLeast(0) else 0,
            usageDayKey = activePeriodIso
        )
    }

    private fun resetDailyCountersInPlace(
        prefs: MutablePreferences,
        todayKey: String
    ) {
        prefs[usageDayKeyPref] = todayKey
        prefs[requestsTodayKey] = 0
        prefs[bonusGraceKey] = 0
        prefs[graceClaimedKey] = false
        prefs[chatUsedKey] = 0
        prefs[riskUsedKey] = 0
        prefs[briefsUsedKey] = 0
        prefs[studioUsedKey] = 0
        prefs[audioUsedKey] = 0
        prefs[fallbackCascadeKey] = 0
    }

    private fun currentDayKey(nowMs: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
}
