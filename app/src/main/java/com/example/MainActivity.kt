package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.analytics.AppEventName
import com.example.analytics.AppEventTracker
import com.example.analytics.EngagementPromptType
import com.example.analytics.PromptOutcome
import com.example.analytics.StoreIntentHelper
import com.example.auth.isFirebaseConfigured
import com.example.auth.safeAuthStateFlow
import com.example.auth.safeCurrentFirebaseUser
import com.example.auth.signOutUser
import com.example.data.FamilyRepository
import com.example.data.LocalFamilyRepository
import com.example.data.LocalPinStore
import com.example.data.UiState
import kotlinx.coroutines.launch
import com.example.service.DeviceProtectionChecker
import com.example.service.SafeNestDnsVpnService
import com.example.service.WeeklySummaryNotifier
import com.example.ui.SafeNestViewModel
import com.example.ui.components.AddChildDialog
import com.example.ui.components.ChildGuardModeScreen
import com.example.ui.components.EngagementPromptHost
import com.example.ui.components.ParentPinDialog
import com.example.ui.components.ProminentDisclosureDialog
import kotlinx.coroutines.delay
import com.example.engine.AiActionType
import com.example.ui.screens.AiCoachScreen
import com.example.ui.screens.AlertsScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.BlockedWebsitesScreen
import com.example.ui.screens.CategoryLimitsScreen
import com.example.ui.screens.ControlsScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DnsFilterScreen
import com.example.ui.screens.SafeNestSplashScreen
import com.example.ui.screens.ShieldLabScreen
import com.example.ui.screens.UsageInsightsScreen
import com.example.ui.theme.SafeNestTheme
import com.example.ui.theme.StatusSafeGreen
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    private val openWeeklySummarySignal = kotlinx.coroutines.flow.MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNotificationIntent(intent)
        enableEdgeToEdge()
        setContent {
            SafeNestTheme {
                AppContent(
                    openWeeklySummarySignal = openWeeklySummarySignal
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.getStringExtra(WeeklySummaryNotifier.EXTRA_OPEN_DEST) == WeeklySummaryNotifier.DEST_WEEKLY_SUMMARY) {
            openWeeklySummarySignal.value += 1
            intent.removeExtra(WeeklySummaryNotifier.EXTRA_OPEN_DEST)
        }
    }
}

private enum class SafeNestTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("home", R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    DNS("dns", R.string.nav_dns, Icons.Filled.Dns, Icons.Outlined.Dns),
    CONTROLS("controls", R.string.nav_controls, Icons.Filled.Tune, Icons.Outlined.Tune),
    ALERTS("alerts", R.string.nav_alerts, Icons.Filled.Notifications, Icons.Outlined.Notifications),
    SHIELD("shield", R.string.nav_shield, Icons.Filled.Shield, Icons.Outlined.Shield)
}

private enum class SecondaryScreen {
    NONE,
    USAGE_INSIGHTS,
    CATEGORY_LIMITS,
    BLOCKED_WEBSITES,
    AI_COACH
}

private enum class DisclosureType {
    VPN, USAGE_STATS, NOTIFICATION_LISTENER
}

private enum class PinModalMode {
    SETUP, LOCK_CHILD_MODE, UNLOCK_PARENT_MODE
}

private sealed interface RootRoute {
    data object Splash : RootRoute
    data object Auth : RootRoute
    data class Authenticated(
        val uid: String,
        val displayName: String,
        val isLocalMode: Boolean
    ) : RootRoute
}

@Composable
fun AppContent(
    openWeeklySummarySignal: kotlinx.coroutines.flow.StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0)
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localPinStore = remember(context) { LocalPinStore(context.applicationContext) }
    val authFlow = remember(context) { safeAuthStateFlow(context) }
    val currentUser by authFlow.collectAsStateWithLifecycle(initialValue = safeCurrentFirebaseUser(context))
    val localPrivateMode by localPinStore.localPrivateModeFlow.collectAsStateWithLifecycle(initialValue = false)
    var splashCompleted by rememberSaveable { mutableStateOf(false) }

    val defaultParentName = stringResource(R.string.parent_default_name)

    val route: RootRoute = when {
        !splashCompleted -> RootRoute.Splash
        currentUser != null -> {
            val user = currentUser!!
            RootRoute.Authenticated(
                uid = user.uid,
                displayName = user.displayName ?: user.email?.substringBefore("@") ?: defaultParentName,
                isLocalMode = false
            )
        }
        localPrivateMode -> RootRoute.Authenticated(
            uid = LocalFamilyRepository.LOCAL_USER_ID,
            displayName = defaultParentName,
            isLocalMode = true
        )
        else -> RootRoute.Auth
    }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            (fadeIn(tween(280)) + scaleIn(initialScale = 0.98f, animationSpec = tween(280))) togetherWith
                fadeOut(tween(200))
        },
        label = "rootNavigationTransition"
    ) { target ->
        when (target) {
            is RootRoute.Splash -> SafeNestSplashScreen(
                onFinished = { splashCompleted = true }
            )

            is RootRoute.Auth -> AuthScreen(
                onAuthSuccess = { /* AuthStateListener updates currentUser reactively */ },
                onContinueLocalMode = {
                    scope.launch {
                        localPinStore.setLocalPrivateMode(true)
                    }
                }
            )

            is RootRoute.Authenticated -> key("${target.uid}_${target.isLocalMode}") {
                SafeNestMainShell(
                    currentUserId = target.uid,
                    userDisplayName = target.displayName,
                    isLocalMode = target.isLocalMode,
                    openWeeklySummarySignal = openWeeklySummarySignal
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SafeNestMainShell(
    currentUserId: String,
    userDisplayName: String,
    isLocalMode: Boolean,
    openWeeklySummarySignal: kotlinx.coroutines.flow.StateFlow<Int>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: SafeNestViewModel = viewModel(
        key = "${currentUserId}_${isLocalMode}",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val useCloud = !isLocalMode && isFirebaseConfigured(app)
                val db = if (useCloud) {
                    val databaseId = app.getString(R.string.firestore_database_id)
                    runCatching { FirebaseFirestore.getInstance(databaseId) }.getOrNull()
                } else {
                    null
                }
                val repository = if (db != null) {
                    FamilyRepository(db)
                } else {
                    LocalFamilyRepository(app)
                }
                SafeNestViewModel(
                    appContext = app,
                    repository = repository,
                    localPinStore = LocalPinStore(app),
                    deviceChecker = DeviceProtectionChecker(app),
                    eventTracker = AppEventTracker(app, db),
                    quotaManager = com.example.data.UsageQuotaManager.create(app, db),
                    currentUserId = currentUserId,
                    userDisplayName = userDisplayName
                )
            }
        }
    )

    val parentProfileState by viewModel.parentProfileState.collectAsStateWithLifecycle()
    val childrenState by viewModel.childrenState.collectAsStateWithLifecycle()
    val alertsState by viewModel.alertsState.collectAsStateWithLifecycle()
    val summariesState by viewModel.summariesState.collectAsStateWithLifecycle()
    val selectedChildId by viewModel.selectedChildId.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val localFilterSnapshot by viewModel.localFilterSnapshot.collectAsStateWithLifecycle()
    val activeBlockedWebsites by viewModel.activeBlockedWebsites.collectAsStateWithLifecycle()
    val customBlockedDomains by viewModel.customBlockedDomains.collectAsStateWithLifecycle()
    val deviceHealth by viewModel.deviceHealth.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val labAiResult by viewModel.labAiResult.collectAsStateWithLifecycle()
    val labDnsResult by viewModel.labDnsResult.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
    val isSyncingUsage by viewModel.isSyncingUsage.collectAsStateWithLifecycle()
    val deviceCategoryHistory by viewModel.deviceCategoryHistory.collectAsStateWithLifecycle()
    val categoryEnforcementState by viewModel.categoryEnforcementState.collectAsStateWithLifecycle()
    val weeklySummaryReport by viewModel.weeklySummaryReport.collectAsStateWithLifecycle()
    val weeklySummaryNotifyEnabled by viewModel.weeklySummaryNotifyEnabled.collectAsStateWithLifecycle()
    val weeklyDeepLinkTick by openWeeklySummarySignal.collectAsStateWithLifecycle()
    val engagementSnapshot by viewModel.engagementSnapshot.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val sessionVersion by viewModel.sessionVersion.collectAsStateWithLifecycle()
    val recentAppEvents by viewModel.recentAppEvents.collectAsStateWithLifecycle()
    val activePrompt by viewModel.activePrompt.collectAsStateWithLifecycle()
    val lastPromptReason by viewModel.lastPromptReason.collectAsStateWithLifecycle()
    val aiCoachPrefs by viewModel.aiCoachPrefs.collectAsStateWithLifecycle()
    val aiCoachMessages by viewModel.aiCoachMessages.collectAsStateWithLifecycle()
    val aiRiskResult by viewModel.aiRiskResult.collectAsStateWithLifecycle()
    val aiWeeklyBrief by viewModel.aiWeeklyBrief.collectAsStateWithLifecycle()
    val aiIsThinking by viewModel.aiIsThinking.collectAsStateWithLifecycle()
    val aiIsTestingKey by viewModel.aiIsTestingKey.collectAsStateWithLifecycle()
    val aiKeyTestResult by viewModel.aiKeyTestResult.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(SafeNestTab.HOME) }
    var secondaryScreen by rememberSaveable { mutableStateOf(SecondaryScreen.NONE) }
    var lastHandledWeeklyTick by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(weeklyDeepLinkTick) {
        if (weeklyDeepLinkTick > lastHandledWeeklyTick) {
            lastHandledWeeklyTick = weeklyDeepLinkTick
            secondaryScreen = SecondaryScreen.USAGE_INSIGHTS
        }
    }
    BackHandler(enabled = secondaryScreen != SecondaryScreen.NONE || currentTab != SafeNestTab.HOME) {
        if (secondaryScreen != SecondaryScreen.NONE) {
            secondaryScreen = SecondaryScreen.NONE
        } else {
            currentTab = SafeNestTab.HOME
        }
    }
    var showAddChildDialog by remember { mutableStateOf(false) }
    var pendingDisclosure by remember { mutableStateOf<DisclosureType?>(null) }
    var pinModalMode by remember { mutableStateOf<PinModalMode?>(null) }
    var pinErrorText by remember { mutableStateOf<String?>(null) }
    var pinErrorCounter by remember { mutableIntStateOf(0) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    // Track tab switches without triggering cold-start popups
    LaunchedEffect(currentTab, secondaryScreen) {
        val route = when (secondaryScreen) {
            SecondaryScreen.USAGE_INSIGHTS -> "usage_insights"
            SecondaryScreen.CATEGORY_LIMITS -> "category_limits"
            SecondaryScreen.BLOCKED_WEBSITES -> "blocked_websites"
            SecondaryScreen.AI_COACH -> "ai_coach"
            SecondaryScreen.NONE -> currentTab.route
        }
        viewModel.trackNavigationTab(route)
    }

    // Keep live modal context synced so delayed auto-prompts never fire or count impressions behind another dialog
    LaunchedEffect(showAddChildDialog, pendingDisclosure, pinModalMode) {
        val hasOpenModal = showAddChildDialog || pendingDisclosure != null || pinModalMode != null
        viewModel.updateUiPromptContext(
            hasOpenModal = hasOpenModal,
            isOnBlockingFlow = pinModalMode != null || pendingDisclosure != null
        )
    }

    // Evaluate optional app update only after each session has settled (never at cold start)
    LaunchedEffect(currentUserId, sessionVersion) {
        delay(16_000L)
        val hasOpenModal = showAddChildDialog || pendingDisclosure != null || pinModalMode != null
        viewModel.onSessionSettled(hasOpenModal = hasOpenModal)
    }

    // Track foreground/background session boundaries and refresh device permission health on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                Lifecycle.Event.ON_RESUME -> viewModel.refreshDeviceHealth()
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Foreground VPN runs regardless; notification shows if granted */ }

    val weeklyPushPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.sendWeeklySummaryNotificationNow()
        } else {
            viewModel.notifyWeeklyPushPermissionDenied()
        }
    }

    val triggerWeeklySummaryPush: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            weeklyPushPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.sendWeeklySummaryNotificationNow()
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            SafeNestDnsVpnService.startService(context)
            viewModel.trackVpnToggled(started = true)
        }
        viewModel.refreshDeviceHealth()
    }

    val parentProfile = (parentProfileState as? UiState.Success)?.data
    val children = (childrenState as? UiState.Success)?.data.orEmpty()
    val activeChild = children.firstOrNull { it.id == selectedChildId } ?: children.firstOrNull()
    val unreviewedAlerts = (alertsState as? UiState.Success)?.data?.count { !it.acknowledged } ?: 0
    val hasParentPin = !parentProfile?.pinHash.isNullOrBlank()
    val isChildMode = parentProfile?.childModeEnabled == true

    // Child Guard Lock Mode Overlay
    if (isChildMode) {
        ChildGuardModeScreen(
            activeChild = activeChild,
            vpnRunning = vpnState.running,
            onRequestParentUnlock = {
                pinErrorText = null
                pinModalMode = PinModalMode.UNLOCK_PARENT_MODE
            }
        )
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavRail = maxWidth >= 600.dp

            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            if (secondaryScreen != SecondaryScreen.NONE) {
                                IconButton(
                                    onClick = { secondaryScreen = SecondaryScreen.NONE },
                                    modifier = Modifier.testTag("secondary_screen_back_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.action_back_cd)
                                    )
                                }
                            }
                        },
                        title = {
                            val titleText = when (secondaryScreen) {
                                SecondaryScreen.USAGE_INSIGHTS -> stringResource(R.string.insights_screen_title)
                                SecondaryScreen.CATEGORY_LIMITS -> stringResource(R.string.cat_limits_screen_title)
                                SecondaryScreen.BLOCKED_WEBSITES -> stringResource(R.string.blocked_sites_screen_title)
                                SecondaryScreen.AI_COACH -> stringResource(R.string.ai_coach_title)
                                SecondaryScreen.NONE -> when (currentTab) {
                                    SafeNestTab.HOME -> stringResource(R.string.dashboard_title)
                                    SafeNestTab.DNS -> stringResource(R.string.dns_screen_title)
                                    SafeNestTab.CONTROLS -> stringResource(R.string.controls_title)
                                    SafeNestTab.ALERTS -> stringResource(R.string.alerts_title)
                                    SafeNestTab.SHIELD -> stringResource(R.string.shield_title)
                                }
                            }
                            Text(titleText, style = MaterialTheme.typography.titleLarge)
                        },
                        actions = {
                            TopBarDnsStatusPill(
                                running = vpnState.running,
                                onClick = {
                                    secondaryScreen = SecondaryScreen.NONE
                                    currentTab = SafeNestTab.DNS
                                }
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                },
                bottomBar = {
                    if (!useNavRail) {
                        NavigationBar(
                            windowInsets = WindowInsets.navigationBars,
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            SafeNestTab.entries.forEach { tab ->
                                val selected = secondaryScreen == SecondaryScreen.NONE && currentTab == tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        secondaryScreen = SecondaryScreen.NONE
                                        currentTab = tab
                                    },
                                    icon = {
                                        TabIconWithBadge(
                                            tab = tab,
                                            selected = selected,
                                            badgeCount = if (tab == SafeNestTab.ALERTS) unreviewedAlerts else 0
                                        )
                                    },
                                    label = { Text(stringResource(tab.labelRes)) },
                                    modifier = Modifier.testTag("nav_tab_${tab.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useNavRail) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                            SafeNestTab.entries.forEach { tab ->
                                val selected = secondaryScreen == SecondaryScreen.NONE && currentTab == tab
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = {
                                        secondaryScreen = SecondaryScreen.NONE
                                        currentTab = tab
                                    },
                                    icon = {
                                        TabIconWithBadge(
                                            tab = tab,
                                            selected = selected,
                                            badgeCount = if (tab == SafeNestTab.ALERTS) unreviewedAlerts else 0
                                        )
                                    },
                                    label = { Text(stringResource(tab.labelRes)) },
                                    modifier = Modifier.testTag("nav_rail_${tab.route}")
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 760.dp)
                        ) {
                            AnimatedContent(
                                targetState = Pair(secondaryScreen, currentTab),
                                transitionSpec = {
                                    val enteringSecondary = initialState.first == SecondaryScreen.NONE && targetState.first != SecondaryScreen.NONE
                                    val exitingSecondary = initialState.first != SecondaryScreen.NONE && targetState.first == SecondaryScreen.NONE
                                    when {
                                        enteringSecondary -> {
                                            (fadeIn(tween(240)) + slideInHorizontally(tween(240)) { it / 6 }) togetherWith
                                                (fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -it / 12 })
                                        }
                                        exitingSecondary -> {
                                            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { -it / 10 }) togetherWith
                                                (fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 6 })
                                        }
                                        else -> {
                                            val direction = if (targetState.second.ordinal >= initialState.second.ordinal) 1 else -1
                                            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 14 * direction }) togetherWith
                                                (fadeOut(tween(150)) + slideOutHorizontally(tween(150)) { -it / 14 * direction })
                                        }
                                    }
                                },
                                label = "tabContentTransition"
                            ) { (secondary, tab) ->
                                when {
                                    secondary == SecondaryScreen.USAGE_INSIGHTS -> UsageInsightsScreen(
                                        childrenState = childrenState,
                                        summariesState = summariesState,
                                        deviceCategoryHistory = deviceCategoryHistory,
                                        selectedChildId = selectedChildId,
                                        deviceHealth = deviceHealth,
                                        isSyncingUsage = isSyncingUsage,
                                        weeklySummaryReport = weeklySummaryReport,
                                        weeklyPushEnabled = weeklySummaryNotifyEnabled,
                                        onToggleWeeklyPush = viewModel::setWeeklySummaryNotifyEnabled,
                                        onSendWeeklyPushNow = triggerWeeklySummaryPush,
                                        onSelectChild = viewModel::selectChild,
                                        onSyncDeviceUsage = viewModel::syncThisDeviceUsage,
                                        onRequestUsageAccess = {
                                            pendingDisclosure = DisclosureType.USAGE_STATS
                                        },
                                        onNavigateToControls = {
                                            secondaryScreen = SecondaryScreen.NONE
                                            currentTab = SafeNestTab.CONTROLS
                                        },
                                        onOpenCategoryLimits = {
                                            secondaryScreen = SecondaryScreen.CATEGORY_LIMITS
                                        }
                                    )

                                    secondary == SecondaryScreen.CATEGORY_LIMITS -> CategoryLimitsScreen(
                                        enforcement = categoryEnforcementState,
                                        deviceHealth = deviceHealth,
                                        vpnState = vpnState,
                                        isCheckingUsage = isSyncingUsage,
                                        onUpdateCategoryLimit = viewModel::setCategoryLimit,
                                        onApplyPreset = viewModel::applyCategoryLimitPreset,
                                        onClearAllLimits = viewModel::clearAllCategoryLimits,
                                        onCheckAndEnforceNow = viewModel::refreshCategoryLimitEnforcement,
                                        onRequestUsageAccess = {
                                            pendingDisclosure = DisclosureType.USAGE_STATS
                                        },
                                        onRequestEnableVpn = {
                                            pendingDisclosure = DisclosureType.VPN
                                        }
                                    )

                                    secondary == SecondaryScreen.BLOCKED_WEBSITES -> BlockedWebsitesScreen(
                                        vpnState = vpnState,
                                        filterSnapshot = localFilterSnapshot,
                                        activeBlockedWebsites = activeBlockedWebsites,
                                        activeChildName = activeChild?.name,
                                        onToggleDnsVpn = { enable ->
                                            if (enable) {
                                                pendingDisclosure = DisclosureType.VPN
                                            } else {
                                                SafeNestDnsVpnService.stopService(context)
                                                viewModel.trackVpnToggled(started = false)
                                            }
                                        },
                                        onAddDomainsBatch = viewModel::addBlockedWebsiteBatch,
                                        onRemoveCustomDomain = viewModel::removeRealtimeBlockedDomain,
                                        onClearCustomDomains = viewModel::clearRealtimeBlockedDomains,
                                        onToggleDnsShield = viewModel::toggleLocalDnsShield,
                                        onEvaluateWebsite = viewModel::evaluateWebsiteWithLocalDns
                                    )

                                    secondary == SecondaryScreen.AI_COACH -> AiCoachScreen(
                                        activeChildName = activeChild?.name?.ifBlank { null }
                                            ?: stringResource(R.string.default_child_name),
                                        prefs = aiCoachPrefs,
                                        messages = aiCoachMessages,
                                        riskResult = aiRiskResult,
                                        weeklyBrief = aiWeeklyBrief,
                                        isThinking = aiIsThinking,
                                        isTestingKey = aiIsTestingKey,
                                        keyTestResult = aiKeyTestResult,
                                        onAskCoach = viewModel::askParentAiCoach,
                                        onClearChat = viewModel::clearAiCoachConversation,
                                        onRunRiskCheck = viewModel::runAiRiskAdvisor,
                                        onRefreshWeeklyBrief = viewModel::refreshAiWeeklyBrief,
                                        onExecuteAction = { action ->
                                            when (action.type) {
                                                AiActionType.OPEN_CATEGORY_LIMITS ->
                                                    secondaryScreen = SecondaryScreen.CATEGORY_LIMITS
                                                AiActionType.OPEN_BLOCKED_WEBSITES ->
                                                    secondaryScreen = SecondaryScreen.BLOCKED_WEBSITES
                                                else -> viewModel.executeAiAction(action)
                                            }
                                        },
                                        onSaveByokSettings = viewModel::saveAiByokSettings,
                                        onEnableEcoLite = { viewModel.setAiEcoLiteMode(true) },
                                        onClaimGraceCredits = viewModel::claimDailyAiGraceCredits,
                                        onSwitchToLocalFree = viewModel::switchAiToOnDeviceFree,
                                        onClearByokKey = viewModel::clearAiByokKey,
                                        onTestByokKey = viewModel::testAiByokKey,
                                        onClearKeyTestResult = viewModel::clearAiKeyTestResult
                                    )

                                    tab == SafeNestTab.HOME -> DashboardScreen(
                                        childrenState = childrenState,
                                        alertsState = alertsState,
                                        summariesState = summariesState,
                                        selectedChildId = selectedChildId,
                                        deviceHealth = deviceHealth,
                                        isSyncingUsage = isSyncingUsage,
                                        weeklySummaryReport = weeklySummaryReport,
                                        weeklyPushEnabled = weeklySummaryNotifyEnabled,
                                        totalBlockedWebsitesCount = activeBlockedWebsites.size,
                                        customBlockedWebsitesCount = customBlockedDomains.size,
                                        aiCoachPrefs = aiCoachPrefs,
                                        onToggleWeeklyPush = viewModel::setWeeklySummaryNotifyEnabled,
                                        onSendWeeklyPushNow = triggerWeeklySummaryPush,
                                        onSelectChild = viewModel::selectChild,
                                        onOpenAddChild = { showAddChildDialog = true },
                                        onTogglePause = { child ->
                                            viewModel.updateRuleField(
                                                child,
                                                "downtimePaused",
                                                !child.downtimePaused
                                            )
                                        },
                                        onSyncDeviceUsage = viewModel::syncThisDeviceUsage,
                                        onNavigateToAlerts = { currentTab = SafeNestTab.ALERTS },
                                        onNavigateToShield = { currentTab = SafeNestTab.SHIELD },
                                        onOpenUsageInsights = { secondaryScreen = SecondaryScreen.USAGE_INSIGHTS },
                                        onOpenBlockedWebsites = { secondaryScreen = SecondaryScreen.BLOCKED_WEBSITES },
                                        onOpenAiCoach = { secondaryScreen = SecondaryScreen.AI_COACH },
                                        onRequestChildMode = {
                                            pinErrorText = null
                                            pinModalMode = if (hasParentPin) PinModalMode.LOCK_CHILD_MODE else PinModalMode.SETUP
                                        }
                                    )

                                    tab == SafeNestTab.DNS -> DnsFilterScreen(
                                        vpnState = vpnState,
                                        customBlockedDomains = customBlockedDomains,
                                        totalBlockedWebsitesCount = activeBlockedWebsites.size,
                                        activeChildName = activeChild?.name,
                                        onOpenBlockedWebsites = { secondaryScreen = SecondaryScreen.BLOCKED_WEBSITES },
                                        onToggleDnsVpn = { enable ->
                                            if (enable) {
                                                pendingDisclosure = DisclosureType.VPN
                                            } else {
                                                SafeNestDnsVpnService.stopService(context)
                                                viewModel.trackVpnToggled(started = false)
                                            }
                                        },
                                        onAddDomain = viewModel::addRealtimeBlockedDomain,
                                        onAddPatternPack = viewModel::addRealtimePatternPack,
                                        onRemoveDomain = viewModel::removeRealtimeBlockedDomain,
                                        onClearAllDomains = viewModel::clearRealtimeBlockedDomains,
                                        onTestDomainPattern = viewModel::testDomainAgainstVpnBlocklist
                                    )

                                    tab == SafeNestTab.CONTROLS -> ControlsScreen(
                                        activeChild = activeChild,
                                        installedApps = installedApps,
                                        categoryEnforcementState = categoryEnforcementState,
                                        totalBlockedWebsitesCount = activeBlockedWebsites.size,
                                        customBlockedWebsitesCount = customBlockedDomains.size,
                                        vpnRunning = vpnState.running,
                                        onOpenCategoryLimits = { secondaryScreen = SecondaryScreen.CATEGORY_LIMITS },
                                        onOpenBlockedWebsites = { secondaryScreen = SecondaryScreen.BLOCKED_WEBSITES },
                                        onUpdateRule = viewModel::updateRuleField,
                                        onUpdateBedtime = viewModel::updateBedtime,
                                        onAddCustomDomain = viewModel::addCustomBlockedDomain,
                                        onRemoveCustomDomain = viewModel::removeCustomBlockedDomain,
                                        onToggleBlockedApp = viewModel::toggleBlockedApp,
                                        onDeleteChild = viewModel::deleteChild,
                                        onOpenAddChild = { showAddChildDialog = true }
                                    )

                                    tab == SafeNestTab.ALERTS -> AlertsScreen(
                                        alertsState = alertsState,
                                        onAcknowledge = viewModel::acknowledgeAlert,
                                        onAcknowledgeAll = viewModel::acknowledgeAllAlerts,
                                        onDelete = viewModel::deleteAlert
                                    )

                                    else -> ShieldLabScreen(
                                        activeChild = activeChild,
                                        hasParentPin = hasParentPin,
                                        isLocalMode = isLocalMode,
                                        vpnState = vpnState,
                                        deviceHealth = deviceHealth,
                                        labAiResult = labAiResult,
                                        labDnsResult = labDnsResult,
                                        engagementSnapshot = engagementSnapshot,
                                        availableUpdate = availableUpdate,
                                        recentAppEvents = recentAppEvents,
                                        lastPromptReason = lastPromptReason,
                                        aiCoachPrefs = aiCoachPrefs,
                                        onOpenAiCoach = { secondaryScreen = SecondaryScreen.AI_COACH },
                                        onShowPrivacyPolicy = viewModel::showPrivacyPolicySummary,
                                        onToggleVpnRequest = { enable ->
                                            if (enable) {
                                                pendingDisclosure = DisclosureType.VPN
                                            } else {
                                                SafeNestDnsVpnService.stopService(context)
                                                viewModel.trackVpnToggled(started = false)
                                            }
                                        },
                                        onRequestUsageAccess = {
                                            pendingDisclosure = DisclosureType.USAGE_STATS
                                        },
                                        onRequestNotificationAccess = {
                                            pendingDisclosure = DisclosureType.NOTIFICATION_LISTENER
                                        },
                                        onRequestBatteryWhitelist = {
                                            val intent = Intent(
                                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            runCatching { context.startActivity(intent) }
                                                .onFailure {
                                                    runCatching {
                                                        context.startActivity(
                                                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                        )
                                                    }
                                                }
                                        },
                                        onRunAiCheck = viewModel::runEdgeAiLabCheck,
                                        onLogAiAlert = viewModel::logLabAiAlertToFeed,
                                        onRunDnsCheck = { domain -> viewModel.runDnsLabCheck(domain, activeChild) },
                                        onOpenPinSetup = {
                                            pinErrorText = null
                                            pinModalMode = PinModalMode.SETUP
                                        },
                                        onRequestChildMode = {
                                            pinErrorText = null
                                            pinModalMode = if (hasParentPin) PinModalMode.LOCK_CHILD_MODE else PinModalMode.SETUP
                                        },
                                        onToggleAutoPrompts = viewModel::setAutoPromptsEnabled,
                                        onClearEventHistory = viewModel::clearEventHistory,
                                        onCheckForUpdates = { viewModel.openManualPrompt(EngagementPromptType.UPDATE) },
                                        onRateApp = { viewModel.openManualPrompt(EngagementPromptType.RATE) },
                                        onShareApp = { viewModel.openManualPrompt(EngagementPromptType.SHARE) },
                                        onSignOut = {
                                            signOutUser(
                                                credentialManager = credentialManager,
                                                scope = scope,
                                                beforeSignOut = {
                                                    SafeNestDnsVpnService.stopService(context)
                                                    viewModel.clearLocalSessionOnSignOut()
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Child Profile Modal
    if (showAddChildDialog) {
        AddChildDialog(
            onDismiss = { showAddChildDialog = false },
            onConfirm = { name, age, device, tone, limit, bedStart, bedEnd ->
                viewModel.addChild(name, age, device, tone, limit, bedStart, bedEnd)
                showAddChildDialog = false
            }
        )
    }

    // Play-Policy Prominent Disclosure Dialogs
    when (pendingDisclosure) {
        DisclosureType.VPN -> ProminentDisclosureDialog(
            title = stringResource(R.string.vpn_disclosure_title),
            body = stringResource(R.string.vpn_disclosure_body),
            confirmLabel = stringResource(R.string.vpn_disclosure_confirm),
            onAccept = {
                pendingDisclosure = null
                val prepIntent = VpnService.prepare(context)
                if (prepIntent != null) {
                    vpnPermissionLauncher.launch(prepIntent)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    SafeNestDnsVpnService.startService(context)
                    viewModel.trackVpnToggled(started = true)
                }
            },
            onDismiss = {
                pendingDisclosure = null
                viewModel.trackFrictionEvent(AppEventName.PERMISSION_DISCLOSURE_DECLINED, "vpn")
            }
        )

        DisclosureType.USAGE_STATS -> ProminentDisclosureDialog(
            title = stringResource(R.string.usage_disclosure_title),
            body = stringResource(R.string.usage_disclosure_body),
            confirmLabel = stringResource(R.string.usage_disclosure_confirm),
            onAccept = {
                pendingDisclosure = null
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            },
            onDismiss = {
                pendingDisclosure = null
                viewModel.trackFrictionEvent(AppEventName.PERMISSION_DISCLOSURE_DECLINED, "usage")
            }
        )

        DisclosureType.NOTIFICATION_LISTENER -> ProminentDisclosureDialog(
            title = stringResource(R.string.notif_disclosure_title),
            body = stringResource(R.string.notif_disclosure_body),
            confirmLabel = stringResource(R.string.notif_disclosure_confirm),
            onAccept = {
                pendingDisclosure = null
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            },
            onDismiss = {
                pendingDisclosure = null
                viewModel.trackFrictionEvent(AppEventName.PERMISSION_DISCLOSURE_DECLINED, "notif")
            }
        )

        null -> Unit
    }

    // Parent Anti-Tamper PIN Modal
    when (pinModalMode) {
        PinModalMode.SETUP -> ParentPinDialog(
            title = stringResource(R.string.pin_setup_title),
            subtitle = stringResource(R.string.pin_setup_subtitle),
            confirmLabel = stringResource(R.string.pin_setup_confirm),
            onDismiss = { pinModalMode = null },
            onSubmitPin = { pin ->
                viewModel.saveParentPin(pin)
                pinModalMode = null
            }
        )

        PinModalMode.LOCK_CHILD_MODE -> ParentPinDialog(
            title = stringResource(R.string.pin_lock_title),
            subtitle = stringResource(R.string.pin_lock_subtitle),
            confirmLabel = stringResource(R.string.pin_lock_confirm),
            errorMessage = pinErrorText,
            errorCounter = pinErrorCounter,
            onDismiss = {
                pinErrorText = null
                pinModalMode = null
            },
            onSubmitPin = { pin ->
                if (viewModel.verifyPinAndToggleChildMode(pin, enableChildMode = true)) {
                    pinErrorText = null
                    pinModalMode = null
                } else {
                    pinErrorText = context.getString(R.string.pin_incorrect_error)
                    pinErrorCounter++
                }
            }
        )

        PinModalMode.UNLOCK_PARENT_MODE -> ParentPinDialog(
            title = stringResource(R.string.pin_unlock_title),
            subtitle = stringResource(R.string.pin_unlock_subtitle),
            confirmLabel = stringResource(R.string.pin_unlock_confirm),
            errorMessage = pinErrorText,
            errorCounter = pinErrorCounter,
            onDismiss = {
                pinErrorText = null
                pinModalMode = null
            },
            onSubmitPin = { pin ->
                if (viewModel.verifyPinAndToggleChildMode(pin, enableChildMode = false)) {
                    pinErrorText = null
                    pinModalMode = null
                } else {
                    pinErrorText = context.getString(R.string.pin_incorrect_error)
                    pinErrorCounter++
                }
            }
        )

        null -> Unit
    }

    // Smart Auto-Popup Host (Update / Rate / Share) — suppressed if any higher-priority modal or Child Guard is active
    val canDisplayEngagementPrompt = !isChildMode &&
        !showAddChildDialog &&
        pendingDisclosure == null &&
        pinModalMode == null
    if (canDisplayEngagementPrompt) {
        EngagementPromptHost(
            request = activePrompt,
            onAcceptUpdate = {
                viewModel.recordPromptOutcome(EngagementPromptType.UPDATE, PromptOutcome.ACCEPTED)
                StoreIntentHelper.openPlayStoreListing(context)
            },
            onAcceptRate = {
                viewModel.recordPromptOutcome(EngagementPromptType.RATE, PromptOutcome.ACCEPTED)
                StoreIntentHelper.openPlayStoreListing(context)
            },
            onAcceptShare = {
                viewModel.recordPromptOutcome(EngagementPromptType.SHARE, PromptOutcome.ACCEPTED)
                StoreIntentHelper.shareApp(context)
            },
            onDismissLater = { type ->
                viewModel.recordPromptOutcome(type, PromptOutcome.DISMISSED_LATER)
            },
            onOptOut = { type ->
                viewModel.recordPromptOutcome(type, PromptOutcome.OPTED_OUT)
            }
        )
    }
}

@Composable
private fun TopBarDnsStatusPill(
    running: Boolean,
    onClick: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "topBarDnsPulse")
    val dotAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "topBarDotAlpha"
    )
    Surface(
        shape = CircleShape,
        color = if (running) StatusSafeGreen.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .padding(end = 12.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("topbar_dns_status_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(if (running) dotAlpha else 1f)
                    .clip(CircleShape)
                    .background(if (running) StatusSafeGreen else MaterialTheme.colorScheme.onSurfaceVariant)
            )
            Text(
                text = stringResource(if (running) R.string.topbar_dns_on else R.string.topbar_dns_off),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (running) StatusSafeGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TabIconWithBadge(
    tab: SafeNestTab,
    selected: Boolean,
    badgeCount: Int
) {
    val icon = if (selected) tab.selectedIcon else tab.unselectedIcon
    val desc = stringResource(tab.labelRes)
    if (badgeCount > 0) {
        BadgedBox(
            badge = {
                Badge {
                    Text(if (badgeCount > 9) "9+" else "$badgeCount")
                }
            }
        ) {
            Icon(imageVector = icon, contentDescription = desc)
        }
    } else {
        Icon(imageVector = icon, contentDescription = desc)
    }
}
