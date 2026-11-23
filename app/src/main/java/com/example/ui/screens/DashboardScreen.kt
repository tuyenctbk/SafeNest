package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.analytics.WeeklySummaryReport
import com.example.data.AiCoachPreferences
import com.example.data.AlertSeverity
import com.example.data.ChildProfile
import com.example.data.DailySummary
import com.example.data.SafetyAlert
import com.example.data.UiState
import com.example.service.DeviceHealthStatus
import com.example.ui.components.AiCoachEntryCard
import com.example.ui.components.BlockedWebsitesEntryCard
import com.example.ui.components.WeeklySummaryCard
import com.example.ui.components.avatarColorFor
import com.example.ui.theme.HeroEmeraldEnd
import com.example.ui.theme.HeroEmeraldStart
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusSafeGreen
import com.example.ui.theme.StatusWarnAmber

@Composable
fun DashboardScreen(
    childrenState: UiState<List<ChildProfile>>,
    alertsState: UiState<List<SafetyAlert>>,
    summariesState: UiState<List<DailySummary>>,
    selectedChildId: String?,
    deviceHealth: DeviceHealthStatus,
    isSyncingUsage: Boolean,
    weeklySummaryReport: WeeklySummaryReport?,
    weeklyPushEnabled: Boolean,
    totalBlockedWebsitesCount: Int = 0,
    customBlockedWebsitesCount: Int = 0,
    aiCoachPrefs: AiCoachPreferences = AiCoachPreferences(),
    onToggleWeeklyPush: (Boolean) -> Unit,
    onSendWeeklyPushNow: () -> Unit,
    onSelectChild: (String) -> Unit,
    onOpenAddChild: () -> Unit,
    onTogglePause: (ChildProfile) -> Unit,
    onSyncDeviceUsage: (ChildProfile) -> Unit,
    onNavigateToAlerts: () -> Unit,
    onNavigateToShield: () -> Unit,
    onOpenUsageInsights: () -> Unit,
    onOpenBlockedWebsites: () -> Unit = {},
    onOpenAiCoach: () -> Unit = {},
    onRequestChildMode: () -> Unit
) {
    when (childrenState) {
        is UiState.Loading -> {
            DashboardLoadingSkeleton()
        }

        is UiState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_load_error),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = childrenState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        is UiState.Success -> {
            val children = childrenState.data
            if (children.isEmpty()) {
                EmptyFamilyOnboarding(onOpenAddChild = onOpenAddChild)
                return
            }

            val activeChild = children.firstOrNull { it.id == selectedChildId } ?: children.first()
            val allAlerts = (alertsState as? UiState.Success)?.data.orEmpty()
            val childAlerts = allAlerts.filter { it.childId == activeChild.id }
            val unreviewedCount = childAlerts.count { !it.acknowledged }
            val latestSummary = (summariesState as? UiState.Success)?.data
                ?.firstOrNull { it.childId == activeChild.id }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Child Switcher Row + Add Child Button
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(children, key = { it.id }) { child ->
                            val isSelected = child.id == activeChild.id
                            val toneColor = avatarColorFor(child.tone())
                            val pillBg by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                label = "childPillBg"
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = pillBg,
                                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onSelectChild(child.id) }
                                    .testTag("child_pill_${child.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) Color.White.copy(alpha = 0.25f) else toneColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = child.name.take(1).uppercase(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = child.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        item {
                            FilledTonalButton(
                                onClick = onOpenAddChild,
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("add_child_chip_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.add_child),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.add_child))
                            }
                        }
                    }
                }

                // Hero Protection Status Card with Animated Crossfade & Radial Screen Time Gauge
                item {
                    AnimatedContent(
                        targetState = activeChild,
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                        },
                        label = "heroChildCrossfade"
                    ) { targetChild ->
                        HeroChildStatusCard(
                            child = targetChild,
                            vpnRunning = deviceHealth.vpnRunning,
                            onTogglePause = { onTogglePause(targetChild) },
                            onOpenUsageInsights = onOpenUsageInsights,
                            onRequestChildMode = onRequestChildMode
                        )
                    }
                }

                // Quick Metrics Row (Shield Readiness + Unreviewed Alerts + Bedtime)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricTile(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigateToShield() }
                                .testTag("shield_readiness_tile"),
                            icon = Icons.Filled.Shield,
                            iconTint = if (deviceHealth.readinessScore >= 50) StatusSafeGreen else StatusWarnAmber,
                            label = stringResource(R.string.metric_device_shield),
                            value = "${deviceHealth.readinessScore}%",
                            sublabel = stringResource(
                                if (deviceHealth.vpnRunning) R.string.metric_dns_active else R.string.metric_tap_to_setup
                            )
                        )
                        MetricTile(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigateToAlerts() }
                                .testTag("alerts_summary_tile"),
                            icon = Icons.Outlined.NotificationsActive,
                            iconTint = if (unreviewedCount > 0) StatusDangerRed else StatusSafeGreen,
                            label = stringResource(R.string.metric_open_alerts),
                            value = "$unreviewedCount",
                            sublabel = stringResource(
                                if (unreviewedCount == 0) R.string.metric_all_clear else R.string.metric_needs_review
                            )
                        )
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Filled.Bedtime,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            label = stringResource(R.string.metric_downtime),
                            value = activeChild.bedtimeStart,
                            sublabel = stringResource(R.string.metric_downtime_to, activeChild.bedtimeEnd)
                        )
                    }
                }

                // Weekly Screen Time Trends Card (This Week vs Last Week + Push Digest)
                if (weeklySummaryReport != null) {
                    item {
                        WeeklySummaryCard(
                            report = weeklySummaryReport,
                            weeklyPushEnabled = weeklyPushEnabled,
                            onToggleWeeklyPush = onToggleWeeklyPush,
                            onSendPushNow = onSendWeeklyPushNow,
                            onOpenDetails = onOpenUsageInsights
                        )
                    }
                }

                // Blocked Websites Manager Entry Card (Local DNS Filter)
                item {
                    BlockedWebsitesEntryCard(
                        totalBlockedCount = totalBlockedWebsitesCount,
                        customCount = customBlockedWebsitesCount,
                        vpnRunning = deviceHealth.vpnRunning,
                        onClick = onOpenBlockedWebsites
                    )
                }

                // Hybrid On-Device + BYOK Parent AI Safety Coach Entry Card
                item {
                    AiCoachEntryCard(
                        prefs = aiCoachPrefs,
                        onClick = onOpenAiCoach
                    )
                }

                // Today's Usage & On-Device Z-Score Habit Insight Card
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.habit_card_title),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = stringResource(R.string.habit_card_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { onSyncDeviceUsage(activeChild) },
                                    enabled = !isSyncingUsage,
                                    modifier = Modifier.testTag("sync_device_usage_button")
                                ) {
                                    if (isSyncingUsage) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = stringResource(R.string.sync_usage_cd),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.sync_button))
                                    }
                                }
                            }

                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = latestSummary?.anomalyNote?.ifBlank { null }
                                        ?: stringResource(R.string.habit_empty_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }

                            val topApps = latestSummary?.parsedTopApps().orEmpty()
                            if (topApps.isNotEmpty()) {
                                val maxMin = topApps.maxOf { it.second }.coerceAtLeast(1)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    topApps.forEach { (appName, mins) ->
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = appName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = stringResource(R.string.minutes_short, mins),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            val animatedBar by animateFloatAsState(
                                                targetValue = (mins.toFloat() / maxMin.toFloat()).coerceIn(0.05f, 1f),
                                                animationSpec = spring(stiffness = Spring.StiffnessLow),
                                                label = "appUsageBar"
                                            )
                                            LinearProgressIndicator(
                                                progress = { animatedBar },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(50)),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = onOpenUsageInsights,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("open_usage_insights_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Insights,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.dashboard_open_category_insights))
                            }
                        }
                    }
                }

                // Recent Redacted Alerts Preview
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.recent_signals_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.view_all),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onNavigateToAlerts() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            if (childAlerts.isEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = stringResource(R.string.all_clear_cd),
                                        tint = StatusSafeGreen
                                    )
                                    Text(
                                        text = stringResource(R.string.no_alerts_for_child, activeChild.name),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                childAlerts.take(3).forEach { alert ->
                                    val alertTitle = stringResource(alert.parsedType().labelRes)
                                    val sevColor = when (alert.parsedSeverity()) {
                                        AlertSeverity.HIGH -> StatusDangerRed
                                        AlertSeverity.MEDIUM -> StatusWarnAmber
                                        AlertSeverity.LOW -> StatusSafeGreen
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.Top,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.WarningAmber,
                                                contentDescription = alertTitle,
                                                tint = sevColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = "$alertTitle • ${alert.appSource}",
                                                    style = MaterialTheme.typography.titleSmall
                                                )
                                                Text(
                                                    text = alert.redactedSnippet,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun HeroChildStatusCard(
    child: ChildProfile,
    vpnRunning: Boolean,
    onTogglePause: () -> Unit,
    onOpenUsageInsights: () -> Unit,
    onRequestChildMode: () -> Unit
) {
    val targetProgress = (child.todayScreenTimeMin.toFloat() / child.dailyLimitMin.coerceAtLeast(1L).toFloat())
        .coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "screenTimeGaugeProgress"
    )
    val gaugeColor by animateColorAsState(
        targetValue = when {
            child.downtimePaused -> Color(0xFFFFB4AB)
            targetProgress >= 0.9f -> Color(0xFFFFB68B)
            else -> Color(0xFF7BE8D7)
        },
        animationSpec = spring(),
        label = "screenTimeGaugeColor"
    )

    Card(
        shape = RoundedCornerShape(26.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            HeroEmeraldStart,
                            HeroEmeraldEnd
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.child_name_age, child.name, child.age),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                        Text(
                            text = child.deviceModel,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill(
                                text = stringResource(if (child.downtimePaused) R.string.status_paused else R.string.status_active),
                                bg = if (child.downtimePaused) Color(0xFF93000A) else Color.White.copy(alpha = 0.2f)
                            )
                            StatusPill(
                                text = "${child.batteryPercent}%",
                                bg = Color.White.copy(alpha = 0.16f),
                                showBattery = true
                            )
                            if (vpnRunning) {
                                StatusPill(text = stringResource(R.string.topbar_dns_on), bg = Color(0xFF0A473E))
                            }
                        }
                    }

                    // Radial Screen Time Gauge (tap to open category charts)
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onOpenUsageInsights)
                            .testTag("hero_screen_time_gauge"),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
                            drawArc(
                                color = Color.White.copy(alpha = 0.2f),
                                startAngle = 140f,
                                sweepAngle = 260f,
                                useCenter = false,
                                style = stroke
                            )
                            drawArc(
                                color = gaugeColor,
                                startAngle = 140f,
                                sweepAngle = 260f * animatedProgress,
                                useCenter = false,
                                style = stroke
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.minutes_short, child.todayScreenTimeMin),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.minutes_limit_short, child.dailyLimitMin),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onTogglePause,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (child.downtimePaused) Color(0xFF7BE8D7) else Color.White,
                            contentColor = Color(0xFF063431)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("toggle_pause_device_button")
                    ) {
                        Icon(
                            imageVector = if (child.downtimePaused) Icons.Filled.PlayCircle else Icons.Filled.PauseCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(if (child.downtimePaused) R.string.resume_device else R.string.pause_device),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = onRequestChildMode,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("enter_child_guard_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.child_mode_short),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    bg: Color,
    showBattery: Boolean = false
) {
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (showBattery) {
                Icon(
                    imageVector = Icons.Filled.BatteryStd,
                    contentDescription = stringResource(R.string.battery_cd),
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    sublabel: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyFamilyOnboarding(
    onOpenAddChild: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = stringResource(R.string.setup_icon_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.empty_children_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = stringResource(R.string.empty_children_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onOpenAddChild,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("onboarding_add_child_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.add_child_profile))
                }
            }
        }
    }
}

@Composable
private fun DashboardLoadingSkeleton() {
    val shimmerTransition = rememberInfiniteTransition(label = "dashboardShimmer")
    val alpha by shimmerTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .width(104.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(placeholderColor)
            )
            Box(
                modifier = Modifier
                    .width(108.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(placeholderColor)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(placeholderColor)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(110.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(placeholderColor)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(placeholderColor)
        )
    }
}
