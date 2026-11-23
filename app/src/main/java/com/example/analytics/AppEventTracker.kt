package com.example.analytics

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

private val Context.engagementDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "safenest_engagement_telemetry"
)

data class ActivePromptRequest(
    val type: EngagementPromptType,
    val updateInfo: AppUpdateInfo? = null,
    val manualTrigger: Boolean = false
)

/**
 * Privacy-first on-device app event tracker & smart auto-popup coordinator.
 *
 * - Persists engagement counters, active-day streaks, prompt cooldowns, fatigue state, and a rolling
 *   non-PII event log in Jetpack DataStore behind a [Mutex] to eliminate concurrent double-fire races.
 * - Uses a gentle post-action beat ([POSITIVE_MOMENT_DELAY_MS]) and live UI context verification before
 *   showing Rate/Share popups so users are never interrupted mid-action or over another modal.
 * - Tracks foreground/background session boundaries ([onAppForegrounded] / [onAppBackgrounded]) so
 *   returning after 30+ minutes starts a clean session and re-evaluates optional updates safely.
 * - Checks optional remote release metadata from Firestore (`app_config/android_release`) when available.
 */
class AppEventTracker(
    private val appContext: Context,
    private val firestore: FirebaseFirestore? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    private val _snapshot = MutableStateFlow(EngagementSnapshot())
    val snapshot: StateFlow<EngagementSnapshot> = _snapshot.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<TrackedAppEvent>>(emptyList())
    val recentEvents: StateFlow<List<TrackedAppEvent>> = _recentEvents.asStateFlow()

    private val _activePrompt = MutableStateFlow<ActivePromptRequest?>(null)
    val activePrompt: StateFlow<ActivePromptRequest?> = _activePrompt.asStateFlow()

    private val _availableUpdate = MutableStateFlow(
        AppUpdateInfo(
            currentVersionCode = BuildConfig.VERSION_CODE,
            latestVersionCode = BuildConfig.VERSION_CODE,
            latestVersionName = BuildConfig.VERSION_NAME
        )
    )
    val availableUpdate: StateFlow<AppUpdateInfo> = _availableUpdate.asStateFlow()

    private val _lastDecisionReason = MutableStateFlow(SuppressionReason.BLOCKED_APP_START)
    val lastDecisionReason: StateFlow<SuppressionReason> = _lastDecisionReason.asStateFlow()

    private val _sessionVersion = MutableStateFlow(0)
    val sessionVersion: StateFlow<Int> = _sessionVersion.asStateFlow()

    @Volatile
    private var sessionInitialized = false

    @Volatile
    private var lastBackgroundTimestampMs: Long = 0L

    @Volatile
    private var liveContextFlags: PromptContextFlags = PromptContextFlags()

    private var pendingPositiveEvalJob: Job? = null

    fun initializeSession() {
        if (sessionInitialized) return
        sessionInitialized = true
        scope.launch {
            stateMutex.withLock {
                hydratePersistedEventsLocked()
                startFreshSessionLocked(clock(), reason = "cold_start")
            }
            checkRemoteUpdateMetadata()
        }
    }

    /**
     * Keeps live UI safety context (Child Guard, open modal, active blocking form, high-severity alerts)
     * synchronized so delayed positive-moment evaluations never fire behind a modal.
     */
    fun updateLiveContextFlags(flags: PromptContextFlags) {
        liveContextFlags = flags
        if (flags.isChildGuardMode || flags.hasOpenModal || flags.isOnBlockingFlow) {
            pendingPositiveEvalJob?.cancel()
            val active = _activePrompt.value
            if (active != null && !active.manualTrigger && flags.isChildGuardMode) {
                _activePrompt.value = null
            }
        }
    }

    /**
     * Called when the activity enters background (`ON_STOP`).
     */
    fun onAppBackgrounded() {
        val now = clock()
        lastBackgroundTimestampMs = now
        pendingPositiveEvalJob?.cancel()
        scope.launch {
            stateMutex.withLock {
                appendEventLocked(AppEventName.APP_BACKGROUNDED, "", now)
            }
        }
    }

    /**
     * Called when the activity returns to foreground (`ON_START`).
     * Starts a fresh session if the app was backgrounded for >= [PromptSchedulerEngine.SESSION_GAP_MS].
     */
    fun onAppForegrounded() {
        if (!sessionInitialized) {
            initializeSession()
            return
        }
        val bgTs = lastBackgroundTimestampMs
        if (bgTs <= 0L) return
        lastBackgroundTimestampMs = 0L
        scope.launch {
            val now = clock()
            val shouldStartNewSession = (now - bgTs) >= PromptSchedulerEngine.SESSION_GAP_MS
            stateMutex.withLock {
                if (shouldStartNewSession) {
                    startFreshSessionLocked(now, reason = "foreground_gap")
                } else {
                    appendEventLocked(AppEventName.APP_FOREGROUNDED, "resume", now)
                }
            }
            if (shouldStartNewSession) {
                checkRemoteUpdateMetadata()
            }
        }
    }

    /**
     * Tracks a privacy-safe app event and optionally schedules a smart auto-popup evaluation
     * after a gentle beat so confirmation snackbars and transitions settle first.
     */
    fun trackEvent(
        event: AppEventName,
        detail: String = "",
        contextFlags: PromptContextFlags? = null,
        evaluateAutoPrompt: Boolean = true
    ) {
        scope.launch {
            val now = clock()
            val effectiveFlags = contextFlags ?: liveContextFlags
            val updated = stateMutex.withLock {
                val next = updatePersistedStateLocked { current ->
                    val dayKey = formatDayKey(now)
                    val withInstall = if (current.installTimestampMs == 0L) {
                        current.copy(
                            installTimestampMs = now,
                            currentSessionStartMs = now,
                            activeDaysCount = 1,
                            lastActiveDayKey = dayKey
                        )
                    } else if (current.lastActiveDayKey != dayKey) {
                        current.copy(
                            activeDaysCount = current.activeDaysCount + 1,
                            lastActiveDayKey = dayKey
                        )
                    } else {
                        current
                    }

                    when {
                        event.isFriction -> withInstall.copy(lastFrictionTimestampMs = now)
                        event.positiveWeight > 0 -> withInstall.copy(
                            sessionPositiveActions = withInstall.sessionPositiveActions + 1,
                            totalPositiveActions = withInstall.totalPositiveActions + 1,
                            engagementScore = withInstall.engagementScore + event.positiveWeight
                        )
                        else -> withInstall
                    }
                }
                appendEventLocked(event, detail, now)
                if (event.isFriction) {
                    pendingPositiveEvalJob?.cancel()
                }
                next
            }

            if (evaluateAutoPrompt && !event.isFriction && _activePrompt.value == null) {
                when {
                    event == AppEventName.APP_SESSION_STARTED -> {
                        stateMutex.withLock {
                            evaluateAndMaybeShowPromptLocked(
                                snapshot = updated,
                                trigger = PromptTriggerSource.APP_START,
                                flags = effectiveFlags,
                                nowMs = now
                            )
                        }
                    }

                    event.positiveWeight > 0 -> {
                        scheduleDelayedPositiveEvaluation(contextFlags)
                    }

                    else -> {
                        stateMutex.withLock {
                            evaluateAndMaybeShowPromptLocked(
                                snapshot = updated,
                                trigger = PromptTriggerSource.SESSION_SETTLED,
                                flags = effectiveFlags,
                                nowMs = now
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks whether a settled session is eligible for an update prompt (never Rate/Share).
     */
    fun evaluateSettledSession(contextFlags: PromptContextFlags) {
        scope.launch {
            stateMutex.withLock {
                if (_activePrompt.value != null) return@withLock
                val now = clock()
                val latest = readSnapshotFromStoreLocked()
                evaluateAndMaybeShowPromptLocked(
                    snapshot = latest,
                    trigger = PromptTriggerSource.SESSION_SETTLED,
                    flags = contextFlags,
                    nowMs = now
                )
            }
        }
    }

    /**
     * Enables or disables automatic update/rate/share popups (critical security updates remain protected).
     */
    fun setAutoPromptsEnabled(enabled: Boolean) {
        scope.launch {
            stateMutex.withLock {
                updatePersistedStateLocked { current ->
                    current.copy(autoPromptsEnabled = enabled)
                }
                if (!enabled) {
                    pendingPositiveEvalJob?.cancel()
                    val active = _activePrompt.value
                    if (active != null && !active.manualTrigger && active.updateInfo?.isMandatory != true) {
                        _activePrompt.value = null
                    }
                    _lastDecisionReason.value = SuppressionReason.BLOCKED_USER_DISABLED
                }
            }
        }
    }

    /**
     * Clears the locally persisted recent event log without wiping anti-spam prompt cooldowns or opt-outs.
     */
    fun clearEventHistory() {
        scope.launch {
            stateMutex.withLock {
                _recentEvents.value = emptyList()
                appContext.engagementDataStore.edit { prefs ->
                    prefs.remove(KEY_PERSISTED_EVENTS)
                }
            }
        }
    }

    /**
     * Voluntary user-triggered prompt (e.g., tapping "Update", "Rate", or "Share" in Shield tab).
     * Bypasses anti-spam cooldowns because the user explicitly requested it.
     */
    fun openManualPrompt(type: EngagementPromptType) {
        pendingPositiveEvalJob?.cancel()
        val update = if (type == EngagementPromptType.UPDATE) _availableUpdate.value else null
        _activePrompt.value = ActivePromptRequest(type = type, updateInfo = update, manualTrigger = true)
        scope.launch {
            stateMutex.withLock {
                appendEventLocked(AppEventName.PROMPT_SHOWN, "type=${type.name.lowercase()}:manual", clock())
            }
        }
    }

    /**
     * Records user interaction with an active popup, updating cooldowns, fatigue counters, and opt-outs.
     */
    fun recordPromptOutcome(type: EngagementPromptType, outcome: PromptOutcome) {
        val active = _activePrompt.value
        _activePrompt.value = null
        scope.launch {
            stateMutex.withLock {
                val now = clock()
                val targetVersion = active?.updateInfo?.latestVersionCode ?: _availableUpdate.value.latestVersionCode
                updatePersistedStateLocked { current ->
                    fun updateChannel(channel: PromptChannelState): PromptChannelState = when (outcome) {
                        PromptOutcome.ACCEPTED -> channel.copy(
                            completed = true,
                            lastShownMs = now
                        )
                        PromptOutcome.DISMISSED_LATER -> channel.copy(
                            dismissCount = channel.dismissCount + 1,
                            lastShownMs = now,
                            lastDismissedVersionCode = if (type == EngagementPromptType.UPDATE) {
                                targetVersion
                            } else {
                                channel.lastDismissedVersionCode
                            }
                        )
                        PromptOutcome.OPTED_OUT -> channel.copy(
                            optedOut = true,
                            dismissCount = channel.dismissCount + 1,
                            lastShownMs = now,
                            lastDismissedVersionCode = if (type == EngagementPromptType.UPDATE) {
                                targetVersion
                            } else {
                                channel.lastDismissedVersionCode
                            }
                        )
                    }

                    val nextConsecutiveDismissals = when (outcome) {
                        PromptOutcome.ACCEPTED -> 0
                        PromptOutcome.DISMISSED_LATER,
                        PromptOutcome.OPTED_OUT -> if (active?.manualTrigger == true) {
                            current.consecutiveDismissals
                        } else {
                            current.consecutiveDismissals + 1
                        }
                    }

                    val withChannel = when (type) {
                        EngagementPromptType.UPDATE -> current.copy(updateState = updateChannel(current.updateState))
                        EngagementPromptType.RATE -> current.copy(rateState = updateChannel(current.rateState))
                        EngagementPromptType.SHARE -> current.copy(shareState = updateChannel(current.shareState))
                    }
                    withChannel.copy(consecutiveDismissals = nextConsecutiveDismissals)
                }
                val eventName = when (outcome) {
                    PromptOutcome.ACCEPTED -> AppEventName.PROMPT_ACCEPTED
                    PromptOutcome.DISMISSED_LATER -> AppEventName.PROMPT_DISMISSED
                    PromptOutcome.OPTED_OUT -> AppEventName.PROMPT_OPTED_OUT
                }
                appendEventLocked(eventName, "type=${type.name.lowercase()}", now)
            }
        }
    }

    suspend fun refreshUpdateInfo(): AppUpdateInfo {
        checkRemoteUpdateMetadata()
        return _availableUpdate.value
    }

    private fun scheduleDelayedPositiveEvaluation(explicitFlags: PromptContextFlags?) {
        pendingPositiveEvalJob?.cancel()
        pendingPositiveEvalJob = scope.launch {
            delay(POSITIVE_MOMENT_DELAY_MS)
            stateMutex.withLock {
                if (_activePrompt.value != null) return@withLock
                val now = clock()
                val latest = readSnapshotFromStoreLocked()
                // Combine explicit flags with live UI context so an opened modal or Child Guard always wins
                val live = liveContextFlags
                val mergedFlags = if (explicitFlags != null) {
                    PromptContextFlags(
                        isChildGuardMode = explicitFlags.isChildGuardMode || live.isChildGuardMode,
                        hasOpenModal = explicitFlags.hasOpenModal || live.hasOpenModal,
                        hasUnreviewedHighSeverityAlert = explicitFlags.hasUnreviewedHighSeverityAlert || live.hasUnreviewedHighSeverityAlert,
                        isOnBlockingFlow = explicitFlags.isOnBlockingFlow || live.isOnBlockingFlow
                    )
                } else {
                    live
                }
                evaluateAndMaybeShowPromptLocked(
                    snapshot = latest,
                    trigger = PromptTriggerSource.POSITIVE_MOMENT,
                    flags = mergedFlags,
                    nowMs = now
                )
            }
        }
    }

    private suspend fun startFreshSessionLocked(now: Long, reason: String) {
        val dayKey = formatDayKey(now)
        val updated = updatePersistedStateLocked { current ->
            val newActiveDays = if (current.lastActiveDayKey != dayKey) {
                current.activeDaysCount + 1
            } else {
                current.activeDaysCount.coerceAtLeast(1)
            }
            current.copy(
                installTimestampMs = if (current.installTimestampMs == 0L) now else current.installTimestampMs,
                totalSessions = current.totalSessions + 1,
                activeDaysCount = newActiveDays,
                lastActiveDayKey = dayKey,
                currentSessionStartMs = now,
                sessionPositiveActions = 0,
                promptsShownThisSession = 0
            )
        }
        _sessionVersion.value += 1
        _lastDecisionReason.value = SuppressionReason.BLOCKED_APP_START
        appendEventLocked(
            AppEventName.APP_SESSION_STARTED,
            "session=${updated.totalSessions}:$reason",
            now
        )
    }

    private suspend fun checkRemoteUpdateMetadata() {
        val db = firestore ?: return
        try {
            val doc = db.collection("app_config").document("android_release").get().await()
            if (doc.exists()) {
                val latestCode = (doc.getLong("latestVersionCode") ?: BuildConfig.VERSION_CODE.toLong()).toInt()
                val latestName = doc.getString("latestVersionName").orEmpty().ifBlank { BuildConfig.VERSION_NAME }
                val notes = doc.getString("releaseNotes").orEmpty()
                val mandatory = doc.getBoolean("isMandatory") ?: false
                _availableUpdate.value = AppUpdateInfo(
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    latestVersionCode = latestCode,
                    latestVersionName = latestName,
                    releaseNotes = notes,
                    isMandatory = mandatory
                )
            }
        } catch (err: Exception) {
            // Silently ignore if remote config doc is not provisioned or offline — zero user disruption
            Log.d(TAG, "Remote release check skipped: ${err.message}")
        }
    }

    private suspend fun evaluateAndMaybeShowPromptLocked(
        snapshot: EngagementSnapshot,
        trigger: PromptTriggerSource,
        flags: PromptContextFlags,
        nowMs: Long
    ) {
        if (_activePrompt.value != null) return
        val decision = PromptSchedulerEngine.evaluate(
            snapshot = snapshot,
            trigger = trigger,
            flags = flags,
            updateInfo = _availableUpdate.value,
            nowMs = nowMs
        )
        _lastDecisionReason.value = decision.reason
        val chosen = decision.promptType ?: return

        // Persist impression immediately inside the mutex so concurrent events can never double-fire
        updatePersistedStateLocked { current ->
            val prunedRecent = (current.recentPromptTimestampsMs + nowMs)
                .filter { (nowMs - it) <= PromptSchedulerEngine.ROLLING_30_DAY_WINDOW_MS }
                .takeLast(12)

            val updatedChannel = when (chosen) {
                EngagementPromptType.UPDATE -> current.copy(
                    updateState = current.updateState.copy(
                        shownCount = current.updateState.shownCount + 1,
                        lastShownMs = nowMs
                    )
                )
                EngagementPromptType.RATE -> current.copy(
                    rateState = current.rateState.copy(
                        shownCount = current.rateState.shownCount + 1,
                        lastShownMs = nowMs
                    )
                )
                EngagementPromptType.SHARE -> current.copy(
                    shareState = current.shareState.copy(
                        shownCount = current.shareState.shownCount + 1,
                        lastShownMs = nowMs
                    )
                )
            }
            updatedChannel.copy(
                lastAnyPromptShownMs = nowMs,
                promptsShownThisSession = current.promptsShownThisSession + 1,
                recentPromptTimestampsMs = prunedRecent
            )
        }
        appendEventLocked(AppEventName.PROMPT_SHOWN, "type=${chosen.name.lowercase()}:auto", nowMs)
        _activePrompt.value = ActivePromptRequest(
            type = chosen,
            updateInfo = decision.updateInfo,
            manualTrigger = false
        )
    }

    private suspend fun updatePersistedStateLocked(
        transform: (EngagementSnapshot) -> EngagementSnapshot
    ): EngagementSnapshot {
        val current = readSnapshotFromStoreLocked()
        val next = transform(current)
        appContext.engagementDataStore.edit { prefs ->
            prefs[KEY_INSTALL_TS] = next.installTimestampMs
            prefs[KEY_TOTAL_SESSIONS] = next.totalSessions
            prefs[KEY_ACTIVE_DAYS] = next.activeDaysCount
            prefs[KEY_LAST_ACTIVE_DAY] = next.lastActiveDayKey
            prefs[KEY_TOTAL_POSITIVE] = next.totalPositiveActions
            prefs[KEY_ENGAGEMENT_SCORE] = next.engagementScore
            prefs[KEY_LAST_FRICTION_TS] = next.lastFrictionTimestampMs
            prefs[KEY_LAST_ANY_PROMPT_TS] = next.lastAnyPromptShownMs
            prefs[KEY_CONSECUTIVE_DISMISSALS] = next.consecutiveDismissals
            prefs[KEY_AUTO_PROMPTS_ENABLED] = next.autoPromptsEnabled
            prefs[KEY_RECENT_PROMPT_TS] = next.recentPromptTimestampsMs.joinToString(",")

            writeChannel(prefs, "update", next.updateState)
            writeChannel(prefs, "rate", next.rateState)
            writeChannel(prefs, "share", next.shareState)
        }
        _snapshot.value = next
        return next
    }

    private suspend fun readSnapshotFromStoreLocked(): EngagementSnapshot {
        val prefs = appContext.engagementDataStore.data.first()
        val inMemory = _snapshot.value
        val recentTimestamps = prefs[KEY_RECENT_PROMPT_TS]
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            .orEmpty()

        return EngagementSnapshot(
            installTimestampMs = prefs[KEY_INSTALL_TS] ?: inMemory.installTimestampMs,
            totalSessions = prefs[KEY_TOTAL_SESSIONS] ?: inMemory.totalSessions,
            activeDaysCount = prefs[KEY_ACTIVE_DAYS] ?: inMemory.activeDaysCount,
            lastActiveDayKey = prefs[KEY_LAST_ACTIVE_DAY] ?: inMemory.lastActiveDayKey,
            currentSessionStartMs = inMemory.currentSessionStartMs,
            sessionPositiveActions = inMemory.sessionPositiveActions,
            totalPositiveActions = prefs[KEY_TOTAL_POSITIVE] ?: inMemory.totalPositiveActions,
            engagementScore = prefs[KEY_ENGAGEMENT_SCORE] ?: inMemory.engagementScore,
            lastFrictionTimestampMs = prefs[KEY_LAST_FRICTION_TS] ?: inMemory.lastFrictionTimestampMs,
            lastAnyPromptShownMs = prefs[KEY_LAST_ANY_PROMPT_TS] ?: inMemory.lastAnyPromptShownMs,
            promptsShownThisSession = inMemory.promptsShownThisSession,
            consecutiveDismissals = prefs[KEY_CONSECUTIVE_DISMISSALS] ?: inMemory.consecutiveDismissals,
            autoPromptsEnabled = prefs[KEY_AUTO_PROMPTS_ENABLED] ?: inMemory.autoPromptsEnabled,
            recentPromptTimestampsMs = recentTimestamps,
            updateState = readChannel(prefs, "update"),
            rateState = readChannel(prefs, "rate"),
            shareState = readChannel(prefs, "share")
        )
    }

    private fun writeChannel(
        prefs: MutablePreferences,
        prefix: String,
        state: PromptChannelState
    ) {
        prefs[intPreferencesKey("${prefix}_shown")] = state.shownCount
        prefs[intPreferencesKey("${prefix}_dismiss")] = state.dismissCount
        prefs[longPreferencesKey("${prefix}_last_ts")] = state.lastShownMs
        prefs[booleanPreferencesKey("${prefix}_completed")] = state.completed
        prefs[booleanPreferencesKey("${prefix}_opted_out")] = state.optedOut
        prefs[intPreferencesKey("${prefix}_dismissed_ver")] = state.lastDismissedVersionCode
    }

    private fun readChannel(prefs: Preferences, prefix: String): PromptChannelState = PromptChannelState(
        shownCount = prefs[intPreferencesKey("${prefix}_shown")] ?: 0,
        dismissCount = prefs[intPreferencesKey("${prefix}_dismiss")] ?: 0,
        lastShownMs = prefs[longPreferencesKey("${prefix}_last_ts")] ?: 0L,
        completed = prefs[booleanPreferencesKey("${prefix}_completed")] ?: false,
        optedOut = prefs[booleanPreferencesKey("${prefix}_opted_out")] ?: false,
        lastDismissedVersionCode = prefs[intPreferencesKey("${prefix}_dismissed_ver")] ?: 0
    )

    private suspend fun hydratePersistedEventsLocked() {
        val prefs = appContext.engagementDataStore.data.first()
        val encoded = prefs[KEY_PERSISTED_EVENTS].orEmpty()
        if (encoded.isBlank()) return
        val restored = encoded.split("\n").mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size < 2) return@mapNotNull null
            val name = runCatching { AppEventName.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val ts = parts[1].toLongOrNull() ?: return@mapNotNull null
            val detail = parts.getOrNull(2).orEmpty()
            TrackedAppEvent(name = name, timestampMs = ts, detail = detail)
        }.take(MAX_PERSISTED_EVENTS)
        if (restored.isNotEmpty()) {
            _recentEvents.value = restored
        }
    }

    private suspend fun appendEventLocked(name: AppEventName, detail: String, timestampMs: Long) {
        val sanitizedDetail = detail.replace("|", "/").replace("\n", " ").take(48)
        val entry = TrackedAppEvent(name = name, timestampMs = timestampMs, detail = sanitizedDetail)
        val updated = (listOf(entry) + _recentEvents.value).take(MAX_PERSISTED_EVENTS)
        _recentEvents.value = updated
        val serialized = updated.joinToString("\n") { e -> "${e.name.name}|${e.timestampMs}|${e.detail}" }
        appContext.engagementDataStore.edit { prefs ->
            prefs[KEY_PERSISTED_EVENTS] = serialized
        }
        Log.d(TAG, "Event[${name.name}] $sanitizedDetail")
    }

    private fun formatDayKey(timestampMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMs))

    private companion object {
        const val TAG = "SafeNestEvents"
        const val MAX_PERSISTED_EVENTS = 40
        const val POSITIVE_MOMENT_DELAY_MS = 1_200L

        val KEY_INSTALL_TS = longPreferencesKey("install_ts")
        val KEY_TOTAL_SESSIONS = intPreferencesKey("total_sessions")
        val KEY_ACTIVE_DAYS = intPreferencesKey("active_days_count")
        val KEY_LAST_ACTIVE_DAY = stringPreferencesKey("last_active_day_key")
        val KEY_TOTAL_POSITIVE = intPreferencesKey("total_positive_actions")
        val KEY_ENGAGEMENT_SCORE = intPreferencesKey("engagement_score")
        val KEY_LAST_FRICTION_TS = longPreferencesKey("last_friction_ts")
        val KEY_LAST_ANY_PROMPT_TS = longPreferencesKey("last_any_prompt_ts")
        val KEY_CONSECUTIVE_DISMISSALS = intPreferencesKey("consecutive_dismissals")
        val KEY_AUTO_PROMPTS_ENABLED = booleanPreferencesKey("auto_prompts_enabled")
        val KEY_RECENT_PROMPT_TS = stringPreferencesKey("recent_prompt_timestamps")
        val KEY_PERSISTED_EVENTS = stringPreferencesKey("persisted_recent_events")
    }
}
