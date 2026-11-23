package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.analytics.ActivePromptRequest
import com.example.analytics.AppEventName
import com.example.analytics.AppEventTracker
import com.example.analytics.AppUsageCategory
import com.example.analytics.DailyCategoryUsage
import com.example.analytics.EngagementPromptType
import com.example.analytics.EngagementSnapshot
import com.example.analytics.PromptContextFlags
import com.example.analytics.PromptOutcome
import com.example.analytics.SuppressionReason
import com.example.analytics.TrackedAppEvent
import com.example.analytics.WeeklySummaryEngine
import com.example.analytics.WeeklySummaryReport
import com.example.data.AiCoachPreferences
import com.example.data.AiEngineMode
import com.example.data.AiFeatureChannel
import com.example.data.AlertSeverity
import com.example.data.AlertType
import com.example.data.AtomicQuotaOutcome
import com.example.data.AvatarTone
import com.example.data.ByokGeminiModel
import com.example.data.ChildProfile
import com.example.data.DailySummary
import com.example.data.FamilyRepository
import com.example.data.LocalFilterSnapshot
import com.example.data.LocalPinStore
import com.example.data.ParentProfile
import com.example.data.SafetyAlert
import com.example.data.UiState
import com.example.data.UsageQuotaManager
import com.example.engine.AiActionType
import com.example.engine.AiChildContext
import com.example.engine.AiCoachMessage
import com.example.engine.AiRecommendedAction
import com.example.engine.AiRiskCheckResult
import com.example.engine.AiWeeklyBrief
import com.example.engine.AnomalyCode
import com.example.engine.BlockedWebsiteEntry
import com.example.engine.ByokTestResult
import com.example.engine.CategoryEnforcementSnapshot
import com.example.engine.CategoryLimitPreset
import com.example.engine.CategoryLimitRule
import com.example.engine.CategoryTimeLimitEngine
import com.example.engine.DnsEvaluation
import com.example.engine.DomainPatternMatch
import com.example.engine.HabitAnomalyReport
import com.example.engine.InMemoryDomainBlocklist
import com.example.engine.LocalDnsFilterEngine
import com.example.engine.AndroidAiCoachLocalizer
import com.example.engine.OnDeviceSafetyEngine
import com.example.engine.ParentAiCoachEngine
import com.example.engine.SafetyClassificationResult
import com.example.service.DeviceHealthStatus
import com.example.service.DeviceProtectionChecker
import com.example.service.InstalledLauncherApp
import com.example.service.SafeNestDnsVpnService
import com.example.service.SafeNestNotificationListener
import com.example.service.VpnRuntimeState
import com.example.service.WeeklySummaryNotifier
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class SafeNestViewModel(
    private val appContext: Context,
    private val repository: FamilyRepository,
    private val localPinStore: LocalPinStore,
    private val deviceChecker: DeviceProtectionChecker,
    private val eventTracker: AppEventTracker,
    private val quotaManager: UsageQuotaManager,
    val currentUserId: String,
    private val userDisplayName: String
) : ViewModel() {

    val parentProfileState: StateFlow<UiState<ParentProfile?>> =
        repository.observeParentProfile(currentUserId)
            .map<ParentProfile?, UiState<ParentProfile?>> { UiState.Success(it) }
            .catch { error -> emit(unwrapFlowError(appContext.getString(R.string.parent_mode), error)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val childrenState: StateFlow<UiState<List<ChildProfile>>> =
        repository.observeChildren(currentUserId)
            .map<List<ChildProfile>, UiState<List<ChildProfile>>> { UiState.Success(it) }
            .catch { error -> emit(unwrapFlowError(appContext.getString(R.string.dashboard_title), error)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val alertsState: StateFlow<UiState<List<SafetyAlert>>> =
        repository.observeAlerts(currentUserId)
            .map<List<SafetyAlert>, UiState<List<SafetyAlert>>> { UiState.Success(it) }
            .catch { error -> emit(unwrapFlowError(appContext.getString(R.string.alerts_title), error)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val summariesState: StateFlow<UiState<List<DailySummary>>> =
        repository.observeDailySummaries(currentUserId)
            .map<List<DailySummary>, UiState<List<DailySummary>>> { UiState.Success(it) }
            .catch { error -> emit(unwrapFlowError(appContext.getString(R.string.habit_card_title), error)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val vpnState: StateFlow<VpnRuntimeState> = SafeNestDnsVpnService.runtimeState

    val localFilterSnapshot: StateFlow<LocalFilterSnapshot> =
        localPinStore.filterSnapshotFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = LocalFilterSnapshot()
        )

    val customBlockedDomains: StateFlow<List<String>> =
        localPinStore.filterSnapshotFlow
            .map { snapshot -> snapshot.customBlockedDomains.sorted() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList()
            )

    val activeBlockedWebsites: StateFlow<List<BlockedWebsiteEntry>> =
        combine(localFilterSnapshot, vpnState) { snapshot, _ ->
            LocalDnsFilterEngine.getActiveBlockedWebsites(
                rules = snapshot,
                customBlocklist = SafeNestDnsVpnService.getCustomBlocklist(),
                categoryLimitBlocklist = SafeNestDnsVpnService.getCategoryLimitBlocklist()
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = LocalDnsFilterEngine.getActiveBlockedWebsites(LocalFilterSnapshot())
        )

    val categoryLimitRules: StateFlow<Map<AppUsageCategory, CategoryLimitRule>> =
        localPinStore.filterSnapshotFlow
            .map { snapshot -> snapshot.categoryLimitRules }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = CategoryTimeLimitEngine.defaultRuleMap()
            )

    val weeklySummaryNotifyEnabled: StateFlow<Boolean> =
        localPinStore.filterSnapshotFlow
            .map { snapshot -> snapshot.weeklySummaryNotifyEnabled }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = true
            )

    private val _selectedChildId = MutableStateFlow<String?>(null)
    val selectedChildId: StateFlow<String?> = _selectedChildId.asStateFlow()

    private val _deviceHealth = MutableStateFlow(deviceChecker.inspectHealth(vpnState.value.running))
    val deviceHealth: StateFlow<DeviceHealthStatus> = _deviceHealth.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledLauncherApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledLauncherApp>> = _installedApps.asStateFlow()

    private val _labAiResult = MutableStateFlow<SafetyClassificationResult?>(null)
    val labAiResult: StateFlow<SafetyClassificationResult?> = _labAiResult.asStateFlow()

    private val _labDnsResult = MutableStateFlow<DnsEvaluation?>(null)
    val labDnsResult: StateFlow<DnsEvaluation?> = _labDnsResult.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _isSyncingUsage = MutableStateFlow(false)
    val isSyncingUsage: StateFlow<Boolean> = _isSyncingUsage.asStateFlow()

    private val _deviceCategoryHistory = MutableStateFlow<List<DailyCategoryUsage>>(emptyList())
    val deviceCategoryHistory: StateFlow<List<DailyCategoryUsage>> = _deviceCategoryHistory.asStateFlow()

    private val _todayCategoryUsage = MutableStateFlow<DailyCategoryUsage?>(null)
    private val _alertedExceededCategories = mutableSetOf<AppUsageCategory>()

    val weeklySummaryReport: StateFlow<WeeklySummaryReport?> =
        combine(
            childrenState,
            selectedChildId,
            summariesState,
            _deviceCategoryHistory
        ) { childrenUi, selectedId, summariesUi, deviceHistory ->
            val children = (childrenUi as? UiState.Success)?.data.orEmpty()
            val activeChild = children.firstOrNull { it.id == selectedId } ?: children.firstOrNull()
                ?: return@combine null
            val childSummaries = (summariesUi as? UiState.Success)?.data
                ?.filter { it.childId == activeChild.id }
                .orEmpty()
            WeeklySummaryEngine.buildWeeklySummary(
                childName = activeChild.name,
                cloudSummaries = childSummaries,
                deviceHistory = deviceHistory,
                dailyLimitMin = activeChild.dailyLimitMin.toInt()
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null
        )

    val categoryEnforcementState: StateFlow<CategoryEnforcementSnapshot> =
        combine(categoryLimitRules, _todayCategoryUsage) { rules, today ->
            CategoryTimeLimitEngine.evaluate(rules = rules, todayUsage = today)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = CategoryTimeLimitEngine.evaluate(
                rules = CategoryTimeLimitEngine.defaultRuleMap(),
                todayUsage = null
            )
        )

    val engagementSnapshot: StateFlow<EngagementSnapshot> = eventTracker.snapshot
    val recentAppEvents: StateFlow<List<TrackedAppEvent>> = eventTracker.recentEvents
    val activePrompt: StateFlow<ActivePromptRequest?> = eventTracker.activePrompt
    val lastPromptReason: StateFlow<SuppressionReason> = eventTracker.lastDecisionReason
    val availableUpdate: StateFlow<com.example.analytics.AppUpdateInfo> = eventTracker.availableUpdate
    val sessionVersion: StateFlow<Int> = eventTracker.sessionVersion

    // Hybrid Firestore + Local DataStore Usage Quota & Parent AI Coach state
    private val aiCoachEngine = ParentAiCoachEngine(
        localizer = AndroidAiCoachLocalizer(appContext)
    )

    val aiCoachPrefs: StateFlow<AiCoachPreferences> = quotaManager.quotaState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = AiCoachPreferences()
    )

    private val _aiCoachMessages = MutableStateFlow<List<AiCoachMessage>>(emptyList())
    val aiCoachMessages: StateFlow<List<AiCoachMessage>> = _aiCoachMessages.asStateFlow()

    private val _aiRiskResult = MutableStateFlow<AiRiskCheckResult?>(null)
    val aiRiskResult: StateFlow<AiRiskCheckResult?> = _aiRiskResult.asStateFlow()

    private val _aiWeeklyBrief = MutableStateFlow<AiWeeklyBrief?>(null)
    val aiWeeklyBrief: StateFlow<AiWeeklyBrief?> = _aiWeeklyBrief.asStateFlow()

    private val _aiIsThinking = MutableStateFlow(false)
    val aiIsThinking: StateFlow<Boolean> = _aiIsThinking.asStateFlow()

    private val _aiIsTestingKey = MutableStateFlow(false)
    val aiIsTestingKey: StateFlow<Boolean> = _aiIsTestingKey.asStateFlow()

    private val _aiKeyTestResult = MutableStateFlow<ByokTestResult?>(null)
    val aiKeyTestResult: StateFlow<ByokTestResult?> = _aiKeyTestResult.asStateFlow()

    @Volatile
    private var uiHasOpenModal: Boolean = false

    @Volatile
    private var uiIsOnBlockingFlow: Boolean = false

    init {
        eventTracker.initializeSession()
        quotaManager.startCloudSync(viewModelScope, currentUserId)
        viewModelScope.launch {
            combine(parentProfileState, alertsState) { parentUi, alertsUi ->
                val isChildMode = (parentUi as? UiState.Success)?.data?.childModeEnabled == true
                val hasHighAlert = (alertsUi as? UiState.Success)?.data
                    ?.any { !it.acknowledged && it.severity.equals(AlertSeverity.HIGH.name, ignoreCase = true) } == true
                Pair(isChildMode, hasHighAlert)
            }.collect { (isChildMode, hasHighAlert) ->
                eventTracker.updateLiveContextFlags(
                    PromptContextFlags(
                        isChildGuardMode = isChildMode,
                        hasOpenModal = uiHasOpenModal,
                        hasUnreviewedHighSeverityAlert = hasHighAlert,
                        isOnBlockingFlow = uiIsOnBlockingFlow
                    )
                )
            }
        }
        viewModelScope.launch {
            repository.ensureParentProfile(currentUserId, userDisplayName)
        }
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { deviceChecker.loadInstalledLauncherApps() }
            _installedApps.value = apps
            val history = withContext(Dispatchers.IO) {
                deviceChecker.readRecentDailyCategoryUsage(days = 14)
            }
            _deviceCategoryHistory.value = history
            _todayCategoryUsage.value = withContext(Dispatchers.IO) {
                deviceChecker.readTodayCategoryUsage()
            }
        }
        viewModelScope.launch {
            combine(weeklySummaryReport, localPinStore.filterSnapshotFlow, parentProfileState) { report, prefs, parentUi ->
                Triple(report, prefs, (parentUi as? UiState.Success)?.data?.childModeEnabled == true)
            }.collect { (report, prefs, isChildMode) ->
                if (report != null &&
                    report.hasAnyData &&
                    prefs.weeklySummaryNotifyEnabled &&
                    !isChildMode &&
                    prefs.lastWeeklySummaryWeekKey != report.isoWeekKey &&
                    WeeklySummaryNotifier.hasNotificationPermission(appContext)
                ) {
                    val sent = WeeklySummaryNotifier.showWeeklySummaryNotification(appContext, report)
                    if (sent) {
                        localPinStore.recordWeeklySummaryNotified(report.isoWeekKey)
                    }
                }
            }
        }
        viewModelScope.launch {
            localPinStore.filterSnapshotFlow.collect { snapshot ->
                SafeNestDnsVpnService.updateInMemoryBlocklist(snapshot.customBlockedDomains)
                val evaluated = CategoryTimeLimitEngine.evaluate(
                    rules = snapshot.categoryLimitRules,
                    todayUsage = _todayCategoryUsage.value
                )
                SafeNestDnsVpnService.updateCategoryLimitEnforcement(
                    exceededCategories = evaluated.exceededCategories,
                    patterns = evaluated.autoBlockedPatterns
                )
            }
        }
        viewModelScope.launch {
            categoryEnforcementState.collect { snapshot ->
                SafeNestDnsVpnService.updateCategoryLimitEnforcement(
                    exceededCategories = snapshot.exceededCategories,
                    patterns = snapshot.autoBlockedPatterns
                )
                logNewlyExceededCategories(snapshot.exceededCategories)
            }
        }
        viewModelScope.launch {
            childrenState.collect { state ->
                if (state is UiState.Success) {
                    val children = state.data
                    val savedActiveId = (parentProfileState.value as? UiState.Success)?.data?.activeChildId
                    val preferredId = _selectedChildId.value ?: savedActiveId?.takeIf { it.isNotBlank() }
                    val active = children.firstOrNull { it.id == preferredId } ?: children.firstOrNull()
                    if (active != null) {
                        if (_selectedChildId.value != active.id) {
                            _selectedChildId.value = active.id
                        }
                        localPinStore.syncLocalRulesFromChild(active)
                        SafeNestDnsVpnService.updateInMemoryBlocklist(active.customBlockedDomains.toSet())
                    } else {
                        _selectedChildId.value = null
                    }
                }
            }
        }
        viewModelScope.launch {
            vpnState.collect { vpn ->
                _deviceHealth.value = deviceChecker.inspectHealth(vpn.running)
            }
        }
        viewModelScope.launch {
            SafeNestNotificationListener.detectedEvents.collect { event ->
                val child = currentSelectedChild() ?: return@collect
                repository.createAlert(
                    userId = currentUserId,
                    childId = child.id,
                    childName = child.name,
                    alertType = event.alertType,
                    severity = event.severity,
                    appSource = event.appSource,
                    redactedSnippet = event.redactedSnippet,
                    confidence = event.confidence
                )
            }
        }
        viewModelScope.launch {
            SafeNestDnsVpnService.blockedEvents.collect { blocked ->
                val child = currentSelectedChild() ?: return@collect
                repository.createAlert(
                    userId = currentUserId,
                    childId = child.id,
                    childName = child.name,
                    alertType = AlertType.WEB_BLOCKED,
                    severity = AlertSeverity.MEDIUM,
                    appSource = appContext.getString(R.string.source_local_dns),
                    redactedSnippet = appContext.getString(
                        R.string.alert_dns_blocked_snippet,
                        blocked.domain,
                        blocked.verdict.name.replace('_', ' ').lowercase()
                    ),
                    confidence = 1.0
                )
            }
        }
    }

    fun selectChild(childId: String) {
        _selectedChildId.value = childId
        val child = (childrenState.value as? UiState.Success)?.data?.firstOrNull { it.id == childId }
        if (child != null) {
            SafeNestDnsVpnService.updateInMemoryBlocklist(child.customBlockedDomains.toSet())
            viewModelScope.launch {
                localPinStore.syncLocalRulesFromChild(child)
            }
        }
    }

    fun refreshDeviceHealth() {
        val health = deviceChecker.inspectHealth(vpnState.value.running)
        _deviceHealth.value = health
        if (health.usageAccessGranted) {
            viewModelScope.launch {
                val history = withContext(Dispatchers.IO) {
                    deviceChecker.readRecentDailyCategoryUsage(days = 14)
                }
                val today = withContext(Dispatchers.IO) {
                    deviceChecker.readTodayCategoryUsage()
                }
                _deviceCategoryHistory.value = history
                _todayCategoryUsage.value = today
            }
        }
    }

    fun setWeeklySummaryNotifyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            localPinStore.setWeeklySummaryNotifyEnabled(enabled)
        }
        _bannerMessage.value = appContext.getString(
            if (enabled) R.string.banner_weekly_push_enabled else R.string.banner_weekly_push_disabled
        )
    }

    fun sendWeeklySummaryNotificationNow() {
        val report = weeklySummaryReport.value
        if (report == null) {
            _bannerMessage.value = appContext.getString(R.string.banner_weekly_push_no_child)
            return
        }
        val sent = WeeklySummaryNotifier.showWeeklySummaryNotification(appContext, report)
        if (sent) {
            viewModelScope.launch {
                localPinStore.recordWeeklySummaryNotified(report.isoWeekKey)
            }
            _bannerMessage.value = appContext.getString(R.string.banner_weekly_push_sent, report.childName)
            eventTracker.trackEvent(
                event = AppEventName.WEEKLY_PUSH_TRIGGERED,
                contextFlags = currentPromptContextFlags()
            )
        } else {
            _bannerMessage.value = appContext.getString(R.string.banner_weekly_push_permission_needed)
            trackFrictionEvent(AppEventName.PERMISSION_DISCLOSURE_DECLINED, "weekly_push")
        }
    }

    fun setCategoryLimit(category: AppUsageCategory, enabled: Boolean, limitMinutes: Int) {
        val clamped = limitMinutes.coerceIn(
            CategoryTimeLimitEngine.MIN_LIMIT_MINUTES,
            CategoryTimeLimitEngine.MAX_LIMIT_MINUTES
        )
        val updated = categoryLimitRules.value.toMutableMap().apply {
            this[category] = CategoryLimitRule(
                category = category,
                enabled = enabled,
                limitMinutes = clamped
            )
        }
        val evaluated = CategoryTimeLimitEngine.evaluate(updated, _todayCategoryUsage.value)
        SafeNestDnsVpnService.updateCategoryLimitEnforcement(
            exceededCategories = evaluated.exceededCategories,
            patterns = evaluated.autoBlockedPatterns
        )
        viewModelScope.launch {
            localPinStore.saveCategoryLimitRules(updated)
        }
        val catLabel = appContext.getString(category.labelRes)
        _bannerMessage.value = if (enabled) {
            appContext.getString(R.string.banner_category_limit_updated, catLabel, clamped)
        } else {
            appContext.getString(R.string.banner_category_limit_disabled, catLabel)
        }
        eventTracker.trackEvent(
            event = AppEventName.CATEGORY_LIMIT_UPDATED,
            detail = "${category.name.lowercase()}:${if (enabled) clamped else 0}",
            contextFlags = currentPromptContextFlags(),
            evaluateAutoPrompt = enabled
        )
    }

    fun applyCategoryLimitPreset(preset: CategoryLimitPreset) {
        val rules = CategoryTimeLimitEngine.presetRules(preset)
        val evaluated = CategoryTimeLimitEngine.evaluate(rules, _todayCategoryUsage.value)
        SafeNestDnsVpnService.updateCategoryLimitEnforcement(
            exceededCategories = evaluated.exceededCategories,
            patterns = evaluated.autoBlockedPatterns
        )
        viewModelScope.launch {
            localPinStore.saveCategoryLimitRules(rules)
        }
        val presetNameRes = when (preset) {
            CategoryLimitPreset.SCHOOL_NIGHT -> R.string.cat_limits_preset_school
            CategoryLimitPreset.BALANCED -> R.string.cat_limits_preset_balanced
            CategoryLimitPreset.WEEKEND -> R.string.cat_limits_preset_weekend
        }
        _bannerMessage.value = appContext.getString(
            R.string.banner_category_preset_applied,
            appContext.getString(presetNameRes)
        )
        eventTracker.trackEvent(
            event = AppEventName.CATEGORY_PRESET_APPLIED,
            detail = preset.name.lowercase(),
            contextFlags = currentPromptContextFlags()
        )
    }

    fun clearAllCategoryLimits() {
        val cleared = CategoryTimeLimitEngine.defaultRuleMap()
        SafeNestDnsVpnService.updateCategoryLimitEnforcement(emptySet(), emptySet())
        _alertedExceededCategories.clear()
        viewModelScope.launch {
            localPinStore.saveCategoryLimitRules(cleared)
        }
        _bannerMessage.value = appContext.getString(R.string.banner_category_limits_cleared)
    }

    fun refreshCategoryLimitEnforcement() {
        viewModelScope.launch {
            _isSyncingUsage.value = true
            try {
                val health = deviceChecker.inspectHealth(vpnState.value.running)
                _deviceHealth.value = health
                if (!health.usageAccessGranted) {
                    _bannerMessage.value = appContext.getString(R.string.banner_grant_usage_first)
                    trackFrictionEvent(AppEventName.PERMISSION_DISCLOSURE_DECLINED, "usage_limits")
                    return@launch
                }
                val today = withContext(Dispatchers.IO) { deviceChecker.readTodayCategoryUsage() }
                val history = withContext(Dispatchers.IO) { deviceChecker.readRecentDailyCategoryUsage(days = 14) }
                _todayCategoryUsage.value = today
                _deviceCategoryHistory.value = history

                val evaluated = CategoryTimeLimitEngine.evaluate(
                    rules = categoryLimitRules.value,
                    todayUsage = today
                )
                SafeNestDnsVpnService.updateCategoryLimitEnforcement(
                    exceededCategories = evaluated.exceededCategories,
                    patterns = evaluated.autoBlockedPatterns
                )
                _bannerMessage.value = if (evaluated.exceededCategories.isNotEmpty()) {
                    appContext.getString(
                        R.string.banner_category_enforcement_blocking,
                        evaluated.exceededCategories.size,
                        evaluated.autoBlockedPatterns.size
                    )
                } else {
                    appContext.getString(
                        R.string.banner_category_enforcement_clear,
                        evaluated.activeRuleCount
                    )
                }
                eventTracker.trackEvent(
                    event = AppEventName.USAGE_SYNC_COMPLETED,
                    detail = "category_limits",
                    contextFlags = currentPromptContextFlags()
                )
            } finally {
                _isSyncingUsage.value = false
            }
        }
    }

    private fun logNewlyExceededCategories(exceeded: Set<AppUsageCategory>) {
        val newlyExceeded = exceeded - _alertedExceededCategories
        if (newlyExceeded.isEmpty()) return
        _alertedExceededCategories.addAll(newlyExceeded)
        val child = currentSelectedChild() ?: return
        val rules = categoryLimitRules.value
        viewModelScope.launch {
            newlyExceeded.forEach { category ->
                val catLabel = appContext.getString(category.labelRes)
                val limitMin = rules[category]?.limitMinutes ?: CategoryTimeLimitEngine.defaultMinutesFor(category)
                repository.createAlert(
                    userId = currentUserId,
                    childId = child.id,
                    childName = child.name,
                    alertType = AlertType.ANOMALY,
                    severity = AlertSeverity.MEDIUM,
                    appSource = appContext.getString(R.string.source_category_limit_guard),
                    redactedSnippet = appContext.getString(
                        R.string.alert_category_limit_reached_snippet,
                        catLabel,
                        limitMin
                    ),
                    confidence = 1.0
                )
            }
        }
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun trackNavigationTab(route: String) {
        eventTracker.trackEvent(
            event = AppEventName.TAB_VIEWED,
            detail = route,
            contextFlags = currentPromptContextFlags(),
            evaluateAutoPrompt = false
        )
        if (route == "usage_insights") {
            eventTracker.trackEvent(
                event = AppEventName.WEEKLY_SUMMARY_VIEWED,
                contextFlags = currentPromptContextFlags(),
                evaluateAutoPrompt = false
            )
        }
    }

    fun onAppForegrounded() {
        eventTracker.onAppForegrounded()
    }

    fun onAppBackgrounded() {
        eventTracker.onAppBackgrounded()
    }

    fun updateUiPromptContext(hasOpenModal: Boolean, isOnBlockingFlow: Boolean) {
        uiHasOpenModal = hasOpenModal
        uiIsOnBlockingFlow = isOnBlockingFlow
        eventTracker.updateLiveContextFlags(currentPromptContextFlags())
    }

    fun setAutoPromptsEnabled(enabled: Boolean) {
        eventTracker.setAutoPromptsEnabled(enabled)
        _bannerMessage.value = appContext.getString(
            if (enabled) R.string.banner_auto_prompts_enabled else R.string.banner_auto_prompts_paused
        )
    }

    fun clearEventHistory() {
        eventTracker.clearEventHistory()
        _bannerMessage.value = appContext.getString(R.string.banner_event_log_cleared)
    }

    fun trackVpnToggled(started: Boolean) {
        eventTracker.trackEvent(
            event = if (started) AppEventName.DNS_SHIELD_STARTED else AppEventName.DNS_SHIELD_STOPPED,
            contextFlags = currentPromptContextFlags(),
            evaluateAutoPrompt = started
        )
    }

    fun trackFrictionEvent(event: AppEventName, detail: String = "") {
        eventTracker.trackEvent(
            event = event,
            detail = detail,
            contextFlags = currentPromptContextFlags(),
            evaluateAutoPrompt = false
        )
    }

    fun onSessionSettled(hasOpenModal: Boolean) {
        eventTracker.evaluateSettledSession(currentPromptContextFlags(hasOpenModal = hasOpenModal))
    }

    fun openManualPrompt(type: EngagementPromptType) {
        if (type == EngagementPromptType.UPDATE) {
            viewModelScope.launch {
                eventTracker.refreshUpdateInfo()
                eventTracker.openManualPrompt(EngagementPromptType.UPDATE)
            }
        } else {
            eventTracker.openManualPrompt(type)
        }
    }

    fun recordPromptOutcome(type: EngagementPromptType, outcome: PromptOutcome) {
        eventTracker.recordPromptOutcome(type, outcome)
    }

    private fun currentPromptContextFlags(hasOpenModal: Boolean = uiHasOpenModal): PromptContextFlags {
        val parent = (parentProfileState.value as? UiState.Success)?.data
        val hasHighAlert = (alertsState.value as? UiState.Success)?.data
            ?.any { !it.acknowledged && it.severity.equals(AlertSeverity.HIGH.name, ignoreCase = true) } == true
        return PromptContextFlags(
            isChildGuardMode = parent?.childModeEnabled == true,
            hasOpenModal = hasOpenModal || uiHasOpenModal,
            hasUnreviewedHighSeverityAlert = hasHighAlert,
            isOnBlockingFlow = uiIsOnBlockingFlow
        )
    }

    fun addChild(
        name: String,
        age: Long,
        deviceModel: String,
        avatarTone: AvatarTone,
        dailyLimitMin: Long,
        bedtimeStart: String,
        bedtimeEnd: String
    ) {
        viewModelScope.launch {
            val result = repository.createChild(
                userId = currentUserId,
                name = name,
                age = age,
                deviceModel = deviceModel,
                avatarTone = avatarTone,
                dailyLimitMin = dailyLimitMin,
                bedtimeStart = bedtimeStart,
                bedtimeEnd = bedtimeEnd
            )
            result.onSuccess { newId ->
                _selectedChildId.value = newId
                _bannerMessage.value = appContext.getString(R.string.banner_child_added, name)
                eventTracker.trackEvent(
                    event = AppEventName.CHILD_PROFILE_CREATED,
                    contextFlags = currentPromptContextFlags()
                )
            }.onFailure { err ->
                _bannerMessage.value = err.message ?: appContext.getString(R.string.banner_child_add_failed)
                trackFrictionEvent(AppEventName.ACTION_ERROR_OCCURRED, "add_child")
            }
        }
    }

    fun deleteChild(child: ChildProfile) {
        viewModelScope.launch {
            repository.deleteChild(currentUserId, child.id)
                .onSuccess {
                    _bannerMessage.value = appContext.getString(R.string.banner_child_removed, child.name)
                }
                .onFailure {
                    _bannerMessage.value = it.message ?: appContext.getString(R.string.banner_child_remove_failed)
                }
        }
    }

    fun updateRuleField(child: ChildProfile, key: String, value: Any) {
        viewModelScope.launch {
            repository.updateChildRules(currentUserId, child.id, mapOf(key to value))
                .onFailure {
                    _bannerMessage.value = it.message ?: appContext.getString(R.string.banner_rule_update_failed)
                    trackFrictionEvent(AppEventName.ACTION_ERROR_OCCURRED, "rule_$key")
                }
        }
    }

    fun updateBedtime(child: ChildProfile, start: String, end: String) {
        viewModelScope.launch {
            repository.updateChildRules(
                currentUserId,
                child.id,
                mapOf("bedtimeStart" to start, "bedtimeEnd" to end)
            ).onSuccess {
                _bannerMessage.value = appContext.getString(R.string.banner_bedtime_updated, start, end)
                eventTracker.trackEvent(
                    event = AppEventName.BEDTIME_SCHEDULE_UPDATED,
                    contextFlags = currentPromptContextFlags()
                )
            }.onFailure {
                _bannerMessage.value = it.message ?: appContext.getString(R.string.banner_bedtime_failed)
                trackFrictionEvent(AppEventName.ACTION_ERROR_OCCURRED, "update_bedtime")
            }
        }
    }

    fun addCustomBlockedDomain(child: ChildProfile, rawDomain: String): Boolean {
        val cleaned = normalizeDomainInput(rawDomain)
        if (cleaned == null) {
            _bannerMessage.value = appContext.getString(R.string.banner_domain_invalid)
            return false
        }
        if (child.customBlockedDomains.contains(cleaned)) {
            _bannerMessage.value = appContext.getString(R.string.banner_domain_already_blocked, cleaned)
            return false
        }
        if (child.customBlockedDomains.size >= 50) {
            _bannerMessage.value = appContext.getString(R.string.banner_domain_limit_reached)
            return false
        }
        val updated = (child.customBlockedDomains + cleaned).distinct()
        SafeNestDnsVpnService.updateInMemoryBlocklist(updated.toSet())
        viewModelScope.launch { localPinStore.saveCustomBlockedDomains(updated.toSet()) }
        updateRuleField(child, "customBlockedDomains", updated)
        eventTracker.trackEvent(
            event = AppEventName.CUSTOM_DOMAIN_BLOCKED,
            contextFlags = currentPromptContextFlags()
        )
        return true
    }

    fun removeCustomBlockedDomain(child: ChildProfile, domain: String) {
        val updated = child.customBlockedDomains.filterNot { it == domain }
        SafeNestDnsVpnService.updateInMemoryBlocklist(updated.toSet())
        viewModelScope.launch { localPinStore.saveCustomBlockedDomains(updated.toSet()) }
        updateRuleField(child, "customBlockedDomains", updated)
    }

    fun addRealtimeBlockedDomain(rawDomain: String): Boolean {
        val cleaned = normalizeDomainInput(rawDomain)
        if (cleaned == null) {
            _bannerMessage.value = appContext.getString(R.string.banner_domain_invalid)
            return false
        }
        val activeChild = currentSelectedChild()
        if (activeChild != null) {
            if (addCustomBlockedDomain(activeChild, cleaned)) {
                _bannerMessage.value = appContext.getString(R.string.banner_domain_blocking_realtime, cleaned)
                return true
            }
            return false
        }
        val current = customBlockedDomains.value.toSet()
        if (current.contains(cleaned)) {
            _bannerMessage.value = appContext.getString(R.string.banner_domain_already_blocked, cleaned)
            return false
        }
        if (current.size >= 50) {
            _bannerMessage.value = appContext.getString(R.string.banner_domain_limit_reached)
            return false
        }
        val updated = current + cleaned
        SafeNestDnsVpnService.updateInMemoryBlocklist(updated)
        viewModelScope.launch {
            localPinStore.saveCustomBlockedDomains(updated)
        }
        _bannerMessage.value = appContext.getString(R.string.banner_domain_blocking_realtime, cleaned)
        eventTracker.trackEvent(
            event = AppEventName.CUSTOM_DOMAIN_BLOCKED,
            contextFlags = currentPromptContextFlags()
        )
        return true
    }

    fun addRealtimePatternPack(rawPatterns: List<String>) {
        val validIncoming = rawPatterns.mapNotNull { normalizeDomainInput(it) }.distinct()
        if (validIncoming.isEmpty()) return

        val activeChild = currentSelectedChild()
        val current = activeChild?.customBlockedDomains ?: customBlockedDomains.value
        val toAdd = validIncoming.filterNot { current.contains(it) }
        if (toAdd.isEmpty()) {
            _bannerMessage.value = appContext.getString(R.string.banner_pattern_pack_already_active)
            return
        }
        val availableSlots = (50 - current.size).coerceAtLeast(0)
        if (availableSlots == 0) {
            _bannerMessage.value = appContext.getString(R.string.banner_domain_limit_reached)
            return
        }
        val accepted = toAdd.take(availableSlots)
        val updated = (current + accepted).distinct()

        SafeNestDnsVpnService.updateInMemoryBlocklist(updated.toSet())
        viewModelScope.launch {
            localPinStore.saveCustomBlockedDomains(updated.toSet())
        }
        if (activeChild != null) {
            updateRuleField(activeChild, "customBlockedDomains", updated)
        }
        _bannerMessage.value = appContext.getString(R.string.banner_pattern_pack_added, accepted.size)
        eventTracker.trackEvent(
            event = AppEventName.CUSTOM_DOMAIN_BLOCKED,
            contextFlags = currentPromptContextFlags()
        )
    }

    fun testDomainAgainstVpnBlocklist(rawDomain: String): DomainPatternMatch =
        SafeNestDnsVpnService.testDomainAgainstInMemoryBlocklist(rawDomain)

    fun evaluateWebsiteWithLocalDns(rawDomain: String): DnsEvaluation {
        val result = LocalDnsFilterEngine.evaluateDomain(
            rawDomain = rawDomain,
            rules = localFilterSnapshot.value,
            customBlocklist = SafeNestDnsVpnService.getCustomBlocklist(),
            categoryLimitBlocklist = SafeNestDnsVpnService.getCategoryLimitBlocklist()
        )
        if (rawDomain.trim().isNotEmpty()) {
            eventTracker.trackEvent(
                event = AppEventName.DNS_LAB_TEST_RUN,
                detail = "blocked_sites",
                contextFlags = currentPromptContextFlags()
            )
        }
        return result
    }

    fun addBlockedWebsiteBatch(rawInput: String): Int {
        val parsed = LocalDnsFilterEngine.parseBatchDomainInputs(rawInput)
        if (parsed.isEmpty()) {
            _bannerMessage.value = appContext.getString(R.string.banner_domain_invalid)
            return 0
        }
        if (parsed.size == 1) {
            return if (addRealtimeBlockedDomain(parsed.first())) 1 else 0
        }
        val activeChild = currentSelectedChild()
        val current = activeChild?.customBlockedDomains ?: customBlockedDomains.value
        val toAdd = parsed.filterNot { current.contains(it) }
        if (toAdd.isEmpty()) {
            _bannerMessage.value = appContext.getString(R.string.banner_domain_already_blocked, parsed.first())
            return 0
        }
        val availableSlots = (50 - current.size).coerceAtLeast(0)
        if (availableSlots == 0) {
            _bannerMessage.value = appContext.getString(R.string.banner_domain_limit_reached)
            return 0
        }
        val accepted = toAdd.take(availableSlots)
        val updated = (current + accepted).distinct()
        SafeNestDnsVpnService.updateInMemoryBlocklist(updated.toSet())
        viewModelScope.launch {
            localPinStore.saveCustomBlockedDomains(updated.toSet())
        }
        if (activeChild != null) {
            updateRuleField(activeChild, "customBlockedDomains", updated)
        }
        _bannerMessage.value = appContext.getString(R.string.banner_websites_batch_blocked, accepted.size)
        eventTracker.trackEvent(
            event = AppEventName.CUSTOM_DOMAIN_BLOCKED,
            contextFlags = currentPromptContextFlags()
        )
        return accepted.size
    }

    fun toggleLocalDnsShield(field: String, enabled: Boolean) {
        viewModelScope.launch {
            localPinStore.setDnsShieldToggle(field, enabled)
        }
        currentSelectedChild()?.let { child ->
            updateRuleField(child, field, enabled)
        }
        eventTracker.trackEvent(
            event = AppEventName.DNS_SHIELD_RULE_TOGGLED,
            detail = "$field=$enabled",
            contextFlags = currentPromptContextFlags(),
            evaluateAutoPrompt = enabled
        )
    }

    fun removeRealtimeBlockedDomain(domain: String) {
        val activeChild = currentSelectedChild()
        if (activeChild != null) {
            removeCustomBlockedDomain(activeChild, domain)
            _bannerMessage.value = appContext.getString(R.string.banner_domain_removed, domain)
            return
        }
        val updated = customBlockedDomains.value.toSet() - domain
        SafeNestDnsVpnService.updateInMemoryBlocklist(updated)
        viewModelScope.launch {
            localPinStore.saveCustomBlockedDomains(updated)
        }
        _bannerMessage.value = appContext.getString(R.string.banner_domain_removed, domain)
    }

    fun clearRealtimeBlockedDomains() {
        val activeChild = currentSelectedChild()
        SafeNestDnsVpnService.updateInMemoryBlocklist(emptySet())
        viewModelScope.launch {
            localPinStore.saveCustomBlockedDomains(emptySet())
        }
        if (activeChild != null) {
            updateRuleField(activeChild, "customBlockedDomains", emptyList<String>())
        }
        _bannerMessage.value = appContext.getString(R.string.banner_blocklist_cleared)
    }

    private fun normalizeDomainInput(rawDomain: String): String? =
        InMemoryDomainBlocklist.normalizeAndValidatePattern(rawDomain)

    fun toggleBlockedApp(child: ChildProfile, packageName: String) {
        val current = child.blockedAppPackages
        val updated = if (current.contains(packageName)) {
            current.filterNot { it == packageName }
        } else {
            if (current.size >= 50) {
                _bannerMessage.value = appContext.getString(R.string.banner_app_limit_reached)
                return
            }
            current + packageName
        }
        updateRuleField(child, "blockedAppPackages", updated)
    }

    fun acknowledgeAlert(alert: SafetyAlert, acknowledged: Boolean) {
        viewModelScope.launch {
            repository.setAlertAcknowledged(currentUserId, alert.id, acknowledged)
                .onSuccess {
                    if (acknowledged) {
                        eventTracker.trackEvent(
                            event = AppEventName.ALERT_REVIEWED,
                            contextFlags = currentPromptContextFlags()
                        )
                    }
                }
                .onFailure {
                    _bannerMessage.value = it.message ?: appContext.getString(R.string.banner_alert_update_failed)
                    trackFrictionEvent(AppEventName.ACTION_ERROR_OCCURRED, "ack_alert")
                }
        }
    }

    fun acknowledgeAllAlerts() {
        val unreviewedIds = (alertsState.value as? UiState.Success)?.data
            ?.filter { !it.acknowledged }
            ?.map { it.id }
            .orEmpty()
        if (unreviewedIds.isEmpty()) return
        viewModelScope.launch {
            repository.acknowledgeAllAlerts(currentUserId, unreviewedIds)
                .onSuccess {
                    _bannerMessage.value = appContext.getString(
                        R.string.banner_alerts_reviewed_all,
                        unreviewedIds.size
                    )
                    eventTracker.trackEvent(
                        event = AppEventName.ALL_ALERTS_REVIEWED,
                        detail = "count=${unreviewedIds.size}",
                        contextFlags = currentPromptContextFlags()
                    )
                }
                .onFailure {
                    _bannerMessage.value = it.message ?: appContext.getString(R.string.banner_alerts_update_failed)
                    trackFrictionEvent(AppEventName.ACTION_ERROR_OCCURRED, "ack_all_alerts")
                }
        }
    }

    fun deleteAlert(alert: SafetyAlert) {
        viewModelScope.launch {
            repository.deleteAlert(currentUserId, alert.id)
                .onFailure {
                    _bannerMessage.value = it.message ?: appContext.getString(R.string.banner_alert_delete_failed)
                }
        }
    }

    fun syncThisDeviceUsage(child: ChildProfile) {
        viewModelScope.launch {
            _isSyncingUsage.value = true
            try {
                val snapshot = withContext(Dispatchers.IO) { deviceChecker.readTodayUsage() }
                val battery = deviceChecker.readBatteryPercent()
                val blockedToday = vpnState.value.queriesBlocked.toLong()
                if (!snapshot.usagePermissionGranted) {
                    _bannerMessage.value = appContext.getString(R.string.banner_grant_usage_first)
                    return@launch
                }

                val priorDays = (summariesState.value as? UiState.Success)?.data
                    ?.filter { it.childId == child.id && it.dateKey != snapshot.dateKey }
                    ?.take(7)
                    ?.map { it.totalScreenTimeMin.toInt() }
                    .orEmpty()

                val anomaly = if (priorDays.size >= 3) {
                    OnDeviceSafetyEngine.evaluateHabitAnomaly(
                        todayMinutes = snapshot.totalMinutes,
                        lateNightMinutes = snapshot.lateNightMinutes,
                        dailyLimitMin = child.dailyLimitMin.toInt(),
                        historicalDailyMinutes = priorDays
                    )
                } else {
                    OnDeviceSafetyEngine.evaluateHabitAnomaly(
                        todayMinutes = snapshot.totalMinutes,
                        lateNightMinutes = snapshot.lateNightMinutes,
                        dailyLimitMin = child.dailyLimitMin.toInt()
                    )
                }
                val localizedAnomalyNote = formatLocalizedAnomaly(anomaly)
                val formattedApps = if (snapshot.categorizedApps.isNotEmpty()) {
                    snapshot.categorizedApps.take(10).map { "${it.appName}|${it.minutes}|${it.category.name}" }
                } else {
                    snapshot.topApps.take(10).map { (name, mins) -> "$name|$mins" }
                }

                val summaryResult = repository.upsertDailySummary(
                    userId = currentUserId,
                    childId = child.id,
                    dateKey = snapshot.dateKey,
                    totalScreenTimeMin = snapshot.totalMinutes.toLong(),
                    lateNightMin = snapshot.lateNightMinutes.toLong(),
                    blockedAttempts = blockedToday,
                    anomalyNote = localizedAnomalyNote,
                    topApps = formattedApps
                )
                val telemetryResult = repository.updateChildTelemetry(
                    userId = currentUserId,
                    childId = child.id,
                    todayScreenTimeMin = snapshot.totalMinutes.toLong(),
                    blockedAttemptsToday = blockedToday,
                    batteryPercent = battery.toLong()
                )

                val syncError = summaryResult.exceptionOrNull() ?: telemetryResult.exceptionOrNull()
                if (syncError != null) {
                    _bannerMessage.value = syncError.message ?: appContext.getString(R.string.banner_usage_sync_failed)
                    trackFrictionEvent(AppEventName.ACTION_ERROR_OCCURRED, "usage_sync")
                    return@launch
                }

                if (anomaly.isAnomalous) {
                    repository.createAlert(
                        userId = currentUserId,
                        childId = child.id,
                        childName = child.name,
                        alertType = AlertType.ANOMALY,
                        severity = anomaly.severity,
                        appSource = appContext.getString(R.string.source_habit_zscore),
                        redactedSnippet = localizedAnomalyNote,
                        confidence = 0.88
                    )
                }
                _bannerMessage.value = appContext.getString(R.string.banner_usage_synced, snapshot.totalMinutes)
                eventTracker.trackEvent(
                    event = AppEventName.USAGE_SYNC_COMPLETED,
                    contextFlags = currentPromptContextFlags()
                )
            } finally {
                _isSyncingUsage.value = false
                refreshDeviceHealth()
            }
        }
    }

    private fun formatLocalizedAnomaly(report: HabitAnomalyReport): String = when (report.code) {
        AnomalyCode.LATE_NIGHT -> appContext.getString(R.string.anomaly_late_night, report.lateNightMinutes)
        AnomalyCode.OVER_LIMIT -> appContext.getString(
            R.string.anomaly_over_limit,
            report.todayMinutes,
            report.dailyLimitMin,
            report.zScore
        )
        AnomalyCode.SURGE -> appContext.getString(R.string.anomaly_surge, report.surgePercent, report.zScore)
        AnomalyCode.HEALTHY -> appContext.getString(R.string.anomaly_healthy, report.todayMinutes, report.zScore)
    }

    fun runEdgeAiLabCheck(inputText: String) {
        val result = OnDeviceSafetyEngine.analyzeText(inputText, deduplicate = false)
        _labAiResult.value = result
        eventTracker.trackEvent(
            event = AppEventName.EDGE_AI_LAB_SCAN_RUN,
            contextFlags = currentPromptContextFlags()
        )
    }

    fun logLabAiAlertToFeed(child: ChildProfile, result: SafetyClassificationResult) {
        val category = result.topCategory ?: return
        viewModelScope.launch {
            repository.createAlert(
                userId = currentUserId,
                childId = child.id,
                childName = child.name,
                alertType = category,
                severity = result.severity,
                appSource = appContext.getString(R.string.source_ai_lab),
                redactedSnippet = result.redactedSnippet,
                confidence = result.confidence
            ).onSuccess {
                _bannerMessage.value = appContext.getString(R.string.banner_lab_alert_saved, child.name)
            }.onFailure {
                _bannerMessage.value = it.message ?: appContext.getString(R.string.banner_lab_alert_failed)
            }
        }
    }

    fun runDnsLabCheck(domainInput: String, child: ChildProfile?) {
        val rules = if (child != null) {
            LocalFilterSnapshot(
                blockAdult = child.blockAdult,
                blockGambling = child.blockGambling,
                blockMalware = child.blockMalware,
                blockSocial = child.blockSocial,
                safeSearchEnabled = child.safeSearchEnabled,
                aiProtectionEnabled = child.aiProtectionEnabled,
                customBlockedDomains = child.customBlockedDomains.toSet()
            )
        } else {
            LocalFilterSnapshot(
                customBlockedDomains = customBlockedDomains.value.toSet()
            )
        }
        _labDnsResult.value = LocalDnsFilterEngine.evaluateDomain(
            rawDomain = domainInput,
            rules = rules,
            categoryLimitBlocklist = SafeNestDnsVpnService.getCategoryLimitBlocklist()
        )
        eventTracker.trackEvent(
            event = AppEventName.DNS_LAB_TEST_RUN,
            contextFlags = currentPromptContextFlags()
        )
    }

    fun saveParentPin(pin: String) {
        if (pin.length != 4 || !pin.all { it.isDigit() }) {
            _bannerMessage.value = appContext.getString(R.string.banner_pin_invalid)
            trackFrictionEvent(AppEventName.PIN_ENTRY_FAILED, "setup_invalid")
            return
        }
        viewModelScope.launch {
            val hash = LocalPinStore.hashPin(pin)
            localPinStore.savePinHash(hash)
            repository.updateParentSettings(
                userId = currentUserId,
                pinHash = hash,
                fallbackDisplayName = userDisplayName
            )
                .onSuccess {
                    _bannerMessage.value = appContext.getString(R.string.banner_pin_saved)
                    eventTracker.trackEvent(
                        event = AppEventName.PARENT_PIN_CONFIGURED,
                        contextFlags = currentPromptContextFlags()
                    )
                }
                .onFailure {
                    _bannerMessage.value = it.message ?: appContext.getString(R.string.banner_pin_save_failed)
                    trackFrictionEvent(AppEventName.ACTION_ERROR_OCCURRED, "save_pin")
                }
        }
    }

    fun verifyPinAndToggleChildMode(pin: String, enableChildMode: Boolean): Boolean {
        val profile = (parentProfileState.value as? UiState.Success)?.data
        val storedHash = profile?.pinHash.orEmpty()
        if (storedHash.isBlank()) {
            _bannerMessage.value = appContext.getString(R.string.banner_pin_required_first)
            return false
        }
        if (LocalPinStore.hashPin(pin) != storedHash) {
            trackFrictionEvent(AppEventName.PIN_ENTRY_FAILED, "verify_mismatch")
            return false
        }
        viewModelScope.launch {
            repository.updateParentSettings(
                userId = currentUserId,
                childModeEnabled = enableChildMode,
                activeChildId = _selectedChildId.value,
                fallbackDisplayName = userDisplayName
            ).onSuccess {
                _bannerMessage.value = appContext.getString(
                    if (enableChildMode) R.string.banner_child_mode_on else R.string.banner_child_mode_off
                )
                if (enableChildMode) {
                    eventTracker.trackEvent(
                        event = AppEventName.CHILD_GUARD_MODE_ENABLED,
                        contextFlags = currentPromptContextFlags(),
                        evaluateAutoPrompt = false
                    )
                }
            }.onFailure {
                _bannerMessage.value = it.message ?: appContext.getString(R.string.banner_child_mode_failed)
                trackFrictionEvent(AppEventName.ACTION_ERROR_OCCURRED, "child_mode")
            }
        }
        return true
    }

    suspend fun clearLocalSessionOnSignOut() {
        eventTracker.trackEvent(
            event = AppEventName.SIGN_OUT,
            evaluateAutoPrompt = false
        )
        SafeNestDnsVpnService.updateInMemoryBlocklist(emptySet())
        localPinStore.clearSessionState()
    }

    fun askParentAiCoach(question: String) {
        val clean = question.trim()
        if (clean.isEmpty() || _aiIsThinking.value) return
        val recentTurns = _aiCoachMessages.value.takeLast(6).map { it.isUser to it.text }
        val userMsg = AiCoachMessage(
            id = "user_${System.nanoTime()}",
            isUser = true,
            text = clean
        )
        _aiCoachMessages.value = _aiCoachMessages.value + userMsg
        _aiIsThinking.value = true
        viewModelScope.launch {
            try {
                val reply = quotaManager.executeWithAtomicQuota(
                    userId = currentUserId,
                    channel = AiFeatureChannel.COACH_CHAT,
                    requiresCloudCredit = true
                ) { prefsForExecution, _ ->
                    var chargedCloud = false
                    var cascaded = false
                    val result = aiCoachEngine.askCoach(
                        question = clean,
                        context = buildAiChildContext(recentTurns = recentTurns),
                        prefs = prefsForExecution,
                        onInteractionFinished = { charged, cas ->
                            chargedCloud = charged
                            cascaded = cas
                        }
                    )
                    AtomicQuotaOutcome(
                        value = result,
                        chargedCloudCredit = chargedCloud,
                        cascadedTier = cascaded
                    )
                }
                _aiCoachMessages.value = _aiCoachMessages.value + reply
                eventTracker.trackEvent(
                    event = AppEventName.AI_COACH_QUERY,
                    detail = reply.origin.name.lowercase(),
                    contextFlags = currentPromptContextFlags()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val fallback = aiCoachEngine.generateOnDeviceCoachReply(clean, buildAiChildContext())
                _aiCoachMessages.value = _aiCoachMessages.value + fallback
            } finally {
                _aiIsThinking.value = false
            }
        }
    }

    fun clearAiCoachConversation() {
        _aiCoachMessages.value = emptyList()
    }

    fun runAiRiskAdvisor(inputText: String, screenshotUri: Uri?) {
        val clean = inputText.trim()
        if (clean.isEmpty() && screenshotUri == null) return
        if (_aiIsThinking.value) return
        _aiIsThinking.value = true
        viewModelScope.launch {
            try {
                val jpegBase64 = screenshotUri?.let { encodeScreenshotUriToBase64(it) }
                val result = quotaManager.executeWithAtomicQuota(
                    userId = currentUserId,
                    channel = AiFeatureChannel.RISK_CHECK,
                    requiresCloudCredit = true
                ) { prefsForExecution, _ ->
                    var chargedCloud = false
                    var cascaded = false
                    val risk = aiCoachEngine.analyzeRisk(
                        inputText = clean,
                        screenshotJpegBase64 = jpegBase64,
                        context = buildAiChildContext(),
                        prefs = prefsForExecution,
                        onInteractionFinished = { charged, cas ->
                            chargedCloud = charged
                            cascaded = cas
                        }
                    )
                    AtomicQuotaOutcome(
                        value = risk,
                        chargedCloudCredit = chargedCloud,
                        cascadedTier = cascaded
                    )
                }
                _aiRiskResult.value = result
                eventTracker.trackEvent(
                    event = AppEventName.AI_RISK_CHECK_RUN,
                    detail = result.origin.name.lowercase(),
                    contextFlags = currentPromptContextFlags()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _aiRiskResult.value = aiCoachEngine.analyzeRiskOnDevice(
                    inputText = clean,
                    hasScreenshot = screenshotUri != null,
                    context = buildAiChildContext()
                )
            } finally {
                _aiIsThinking.value = false
            }
        }
    }

    fun refreshAiWeeklyBrief(forceCloudIfEnabled: Boolean = true) {
        if (_aiIsThinking.value) return
        _aiIsThinking.value = true
        viewModelScope.launch {
            try {
                val context = buildAiChildContext()
                val brief = if (forceCloudIfEnabled) {
                    quotaManager.executeWithAtomicQuota(
                        userId = currentUserId,
                        channel = AiFeatureChannel.WEEKLY_BRIEF,
                        requiresCloudCredit = true
                    ) { prefsForExecution, _ ->
                        var chargedCloud = false
                        var cascaded = false
                        val generated = aiCoachEngine.generateWeeklyBrief(
                            context = context,
                            prefs = prefsForExecution,
                            onInteractionFinished = { charged, cas ->
                                chargedCloud = charged
                                cascaded = cas
                            }
                        )
                        AtomicQuotaOutcome(
                            value = generated,
                            chargedCloudCredit = chargedCloud,
                            cascadedTier = cascaded
                        )
                    }
                } else {
                    aiCoachEngine.buildOnDeviceWeeklyBrief(context)
                }
                _aiWeeklyBrief.value = brief
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _aiWeeklyBrief.value = aiCoachEngine.buildOnDeviceWeeklyBrief(buildAiChildContext())
            } finally {
                _aiIsThinking.value = false
            }
        }
    }

    fun saveAiByokSettings(
        mode: AiEngineMode,
        apiKey: String,
        model: ByokGeminiModel,
        dailyLimit: Int,
        ecoLiteMode: Boolean
    ) {
        viewModelScope.launch {
            quotaManager.saveConfiguration(
                userId = currentUserId,
                mode = mode,
                apiKey = apiKey,
                model = model,
                dailyCloudLimit = dailyLimit,
                ecoLiteMode = ecoLiteMode
            )
            _aiKeyTestResult.value = null
            _bannerMessage.value = appContext.getString(R.string.ai_coach_settings_saved)
            eventTracker.trackEvent(
                event = AppEventName.AI_BYOK_CONFIGURED,
                detail = mode.name.lowercase(),
                contextFlags = currentPromptContextFlags()
            )
        }
    }

    fun setAiEcoLiteMode(enabled: Boolean) {
        viewModelScope.launch {
            quotaManager.setEcoLiteMode(currentUserId, enabled)
            _bannerMessage.value = appContext.getString(
                if (enabled) R.string.ai_coach_eco_lite_enabled_banner else R.string.ai_coach_eco_lite_disabled_banner
            )
        }
    }

    fun claimDailyAiGraceCredits() {
        viewModelScope.launch {
            val granted = quotaManager.claimDailyGraceCredits(currentUserId)
            _bannerMessage.value = appContext.getString(
                if (granted) R.string.ai_coach_grace_claimed_banner else R.string.ai_coach_grace_already_used_banner
            )
        }
    }

    fun switchAiToOnDeviceFree() {
        viewModelScope.launch {
            quotaManager.switchToOnDeviceFreeMode()
            _bannerMessage.value = appContext.getString(R.string.ai_coach_switched_local_banner)
        }
    }

    fun clearAiByokKey() {
        viewModelScope.launch {
            quotaManager.clearByokKey()
            _aiKeyTestResult.value = null
            _bannerMessage.value = appContext.getString(R.string.ai_coach_key_cleared)
        }
    }

    fun testAiByokKey(apiKey: String, model: ByokGeminiModel) {
        if (_aiIsTestingKey.value) return
        _aiIsTestingKey.value = true
        _aiKeyTestResult.value = null
        viewModelScope.launch {
            try {
                _aiKeyTestResult.value = aiCoachEngine.testByokApiKey(apiKey, model)
            } finally {
                _aiIsTestingKey.value = false
            }
        }
    }

    fun clearAiKeyTestResult() {
        _aiKeyTestResult.value = null
    }

    fun executeAiAction(action: AiRecommendedAction) {
        when (action.type) {
            AiActionType.BLOCK_DOMAIN -> {
                if (action.domain.isNotBlank()) {
                    addRealtimeBlockedDomain(action.domain)
                }
            }
            AiActionType.APPLY_SCHOOL_NIGHT_PRESET -> {
                applyCategoryLimitPreset(CategoryLimitPreset.SCHOOL_NIGHT)
            }
            AiActionType.OPEN_CATEGORY_LIMITS,
            AiActionType.OPEN_BLOCKED_WEBSITES -> Unit
        }
    }

    private fun buildAiChildContext(
        recentTurns: List<Pair<Boolean, String>> = emptyList()
    ): AiChildContext {
        val child = currentSelectedChild()
        val allAlerts = (alertsState.value as? UiState.Success)?.data.orEmpty()
        val childAlerts = if (child != null) allAlerts.filter { it.childId == child.id } else allAlerts
        val unreviewed = childAlerts.filter { !it.acknowledged }
        return AiChildContext(
            childName = child?.name?.ifBlank { null } ?: appContext.getString(R.string.default_child_name),
            childAge = (child?.age ?: 10L).toInt().coerceIn(4, 18),
            dailyLimitMinutes = (child?.dailyLimitMin ?: 120L).toInt().coerceAtLeast(15),
            todayScreenMinutes = (child?.todayScreenTimeMin ?: 0L).toInt().coerceAtLeast(0),
            bedtimeStart = child?.bedtimeStart ?: "21:30",
            bedtimeEnd = child?.bedtimeEnd ?: "06:30",
            unreviewedAlertsCount = unreviewed.size,
            recentAlertCategories = unreviewed.take(5).map { it.parsedType() },
            customBlockedCount = customBlockedDomains.value.size,
            vpnRunning = vpnState.value.running,
            weeklyReport = weeklySummaryReport.value,
            filterSnapshot = localFilterSnapshot.value,
            recentConversationTurns = recentTurns
        )
    }

    private suspend fun encodeScreenshotUriToBase64(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight).coerceAtLeast(1)
            var sampleSize = 1
            while (maxDim / sampleSize > 1024) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return@runCatching null

            val baos = ByteArrayOutputStream()
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                val bytes = baos.toByteArray()
                if (bytes.size > 4_000_000) return@runCatching null
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    private fun currentSelectedChild(): ChildProfile? {
        val children = (childrenState.value as? UiState.Success)?.data.orEmpty()
        return children.firstOrNull { it.id == _selectedChildId.value } ?: children.firstOrNull()
    }

    private fun unwrapFlowError(label: String, error: Throwable): UiState.Error {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing $label", rootError)
        return UiState.Error(rootError.message ?: appContext.getString(R.string.error_load_failed, label))
    }

    private companion object {
        const val TAG = "SafeNestVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
