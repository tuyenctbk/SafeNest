package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.analytics.AppCategoryAnalytics
import com.example.analytics.AppUsageCategory
import com.example.analytics.BehaviorInsight
import com.example.analytics.BehaviorInsightCode
import com.example.analytics.CategorySlice
import com.example.analytics.DailyCategoryUsage
import com.example.analytics.UsageInsightsReport
import com.example.analytics.WeeklySummaryReport
import com.example.data.ChildProfile
import com.example.data.DailySummary
import com.example.data.UiState
import com.example.service.DeviceHealthStatus
import com.example.ui.components.CategoryDonutChart
import com.example.ui.components.DailyStackedBarChart
import com.example.ui.components.WeeklySummaryCard
import com.example.ui.components.avatarColorFor
import com.example.ui.components.formatDurationLabel
import com.example.ui.theme.HeroEmeraldStart
import com.example.ui.theme.HeroInsightsEnd
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusSafeGreen
import com.example.ui.theme.StatusWarnAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsageInsightsScreen(
    childrenState: UiState<List<ChildProfile>>,
    summariesState: UiState<List<DailySummary>>,
    deviceCategoryHistory: List<DailyCategoryUsage>,
    selectedChildId: String?,
    deviceHealth: DeviceHealthStatus,
    isSyncingUsage: Boolean,
    weeklySummaryReport: WeeklySummaryReport?,
    weeklyPushEnabled: Boolean,
    onToggleWeeklyPush: (Boolean) -> Unit,
    onSendWeeklyPushNow: () -> Unit,
    onSelectChild: (String) -> Unit,
    onSyncDeviceUsage: (ChildProfile) -> Unit,
    onRequestUsageAccess: () -> Unit,
    onNavigateToControls: () -> Unit,
    onOpenCategoryLimits: () -> Unit
) {
    val children = (childrenState as? UiState.Success)?.data.orEmpty()
    val activeChild = children.firstOrNull { it.id == selectedChildId } ?: children.firstOrNull()

    if (activeChild == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.insights_no_child_selected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var todayOnly by rememberSaveable { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf<AppUsageCategory?>(null) }

    val cloudSummaries = remember(summariesState, activeChild.id) {
        (summariesState as? UiState.Success)?.data
            ?.filter { it.childId == activeChild.id }
            .orEmpty()
    }

    val timeline = remember(cloudSummaries, deviceCategoryHistory) {
        AppCategoryAnalytics.buildSevenDayTimeline(
            cloudSummaries = cloudSummaries,
            deviceHistory = deviceCategoryHistory
        )
    }

    val report = remember(timeline, activeChild.dailyLimitMin, todayOnly) {
        AppCategoryAnalytics.buildReport(
            days = timeline,
            dailyLimitMin = activeChild.dailyLimitMin.toInt(),
            todayOnly = todayOnly
        )
    }

    var selectedDayIndex by remember(timeline) { mutableIntStateOf((timeline.lastIndex).coerceAtLeast(0)) }
    val inspectedDay = timeline.getOrNull(selectedDayIndex)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("usage_insights_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Child Selector Pills
        if (children.size > 1) {
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
                            label = "insightsChildPill"
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = pillBg,
                            border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { onSelectChild(child.id) }
                                .testTag("insights_child_${child.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) Color.White.copy(alpha = 0.25f) else toneColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = child.name.take(1).uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
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
                }
            }
        }

        // 2. Range Selector (Today / 7 Days) + Sync Button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    SegmentedButton(
                        selected = todayOnly,
                        onClick = { todayOnly = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        modifier = Modifier.testTag("insights_range_today")
                    ) {
                        Text(stringResource(R.string.insights_range_today))
                    }
                    SegmentedButton(
                        selected = !todayOnly,
                        onClick = { todayOnly = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        modifier = Modifier.testTag("insights_range_7d")
                    ) {
                        Text(stringResource(R.string.insights_range_7days))
                    }
                }

                FilledTonalButton(
                    onClick = {
                        if (deviceHealth.usageAccessGranted) {
                            onSyncDeviceUsage(activeChild)
                        } else {
                            onRequestUsageAccess()
                        }
                    },
                    enabled = !isSyncingUsage,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("insights_sync_button")
                ) {
                    if (isSyncingUsage) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.sync_usage_cd),
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.sync_button))
                    }
                }
            }
        }

        // 3. Hero Screen-Time & Digital Balance Summary Card
        item {
            InsightsHeroCard(
                child = activeChild,
                report = report,
                todayOnly = todayOnly
            )
        }

        // 3b. Week-over-Week Comparison & Weekly Push Digest Card
        if (weeklySummaryReport != null) {
            item {
                WeeklySummaryCard(
                    report = weeklySummaryReport,
                    weeklyPushEnabled = weeklyPushEnabled,
                    onToggleWeeklyPush = onToggleWeeklyPush,
                    onSendPushNow = onSendWeeklyPushNow,
                    onOpenDetails = null
                )
            }
        }

        // 4. Empty / Usage Access Setup Guide when no data exists yet
        if (!report.hasData) {
            item {
                InsightsEmptyStateCard(
                    usageAccessGranted = deviceHealth.usageAccessGranted,
                    onRequestUsageAccess = onRequestUsageAccess,
                    onSyncNow = { onSyncDeviceUsage(activeChild) }
                )
            }
        } else {
            // 5. Interactive Category Share Donut Chart Card
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.insights_category_share_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.insights_category_tap_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (selectedCategory != null) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable { selectedCategory = null }
                                        .testTag("clear_category_filter_chip")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.insights_clear_filter),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.insights_clear_filter),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CategoryDonutChart(
                                slices = report.slices,
                                totalMinutes = report.totalMinutes,
                                selectedCategory = selectedCategory,
                                onSelectCategory = { selectedCategory = it }
                            )
                        }

                        // Interactive Category Legend Chips (linked brushing)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            report.slices.forEach { slice ->
                                val isSelected = selectedCategory == slice.category
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (isSelected) {
                                        slice.category.chartColor.copy(alpha = 0.2f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                    },
                                    border = if (isSelected) {
                                        BorderStroke(1.5.dp, slice.category.chartColor)
                                    } else {
                                        null
                                    },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable {
                                            selectedCategory = if (isSelected) null else slice.category
                                        }
                                        .testTag("category_legend_${slice.category.name.lowercase()}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(9.dp)
                                                .clip(CircleShape)
                                                .background(slice.category.chartColor)
                                        )
                                        Text(
                                            text = stringResource(slice.category.labelRes),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                        Text(
                                            text = "${slice.sharePercent}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. Interactive 7-Day Daily Stacked Column Trend Card + Day Inspector Tooltip
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.insights_daily_trend_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.insights_daily_trend_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        DailyStackedBarChart(
                            days = timeline,
                            dailyLimitMin = activeChild.dailyLimitMin.toInt(),
                            selectedDayIndex = selectedDayIndex,
                            selectedCategory = selectedCategory,
                            onSelectDay = { selectedDayIndex = it }
                        )

                        // Interactive Day Inspector Tooltip Card
                        if (inspectedDay != null) {
                            DayInspectorTooltipCard(
                                day = inspectedDay,
                                dailyLimitMin = activeChild.dailyLimitMin.toInt()
                            )
                        }
                    }
                }
            }

            // 7. Ranked Category Breakdown & Top Apps
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.insights_category_breakdown_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        val maxMinutes = report.slices.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
                        report.slices.forEach { slice ->
                            CategoryBreakdownRow(
                                slice = slice,
                                maxMinutes = maxMinutes,
                                isHighlighted = selectedCategory == slice.category,
                                onClick = {
                                    selectedCategory = if (selectedCategory == slice.category) null else slice.category
                                }
                            )
                        }
                    }
                }
            }

            // 8. Behavioral Insights for Parents
            if (report.insights.isNotEmpty()) {
                item {
                    ParentBehaviorInsightsCard(
                        insights = report.insights,
                        onNavigateToControls = onNavigateToControls,
                        onOpenCategoryLimits = onOpenCategoryLimits
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun InsightsHeroCard(
    child: ChildProfile,
    report: UsageInsightsReport,
    todayOnly: Boolean
) {
    val balanceColor = when {
        report.balanceScore >= 75 -> Color(0xFF7BE8D7)
        report.balanceScore >= 50 -> Color(0xFFFFD494)
        else -> Color(0xFFFFB4AB)
    }

    Card(
        shape = RoundedCornerShape(26.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            HeroEmeraldStart,
                            HeroInsightsEnd
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (todayOnly) R.string.insights_hero_today_label else R.string.insights_hero_7d_avg_label,
                                child.name
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        Text(
                            text = formatDurationLabel(report.averageDailyMinutes),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = stringResource(R.string.insights_hero_limit_caption, child.dailyLimitMin),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }

                    // Digital Balance Score Pill
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.14f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "${report.balanceScore}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = balanceColor
                            )
                            Text(
                                text = stringResource(R.string.insights_balance_score),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }

                // Bottom quick stats strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.14f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Bedtime,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = formatDurationLabel(report.lateNightMinutes),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = stringResource(R.string.insights_late_night_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.14f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = report.dominantCategory?.icon ?: Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = report.dominantCategory?.let { stringResource(it.labelRes) }
                                        ?: stringResource(R.string.insights_none_yet),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.insights_top_category_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                    maxLines = 1,
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

@Composable
private fun DayInspectorTooltipCard(
    day: DailyCategoryUsage,
    dailyLimitMin: Int
) {
    val overLimit = day.totalMinutes > dailyLimitMin
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("day_inspector_tooltip")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${day.shortDayLabel} • ${day.dateKey}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (overLimit) StatusDangerRed.copy(alpha = 0.14f) else StatusSafeGreen.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = formatDurationLabel(day.totalMinutes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (overLimit) StatusDangerRed else StatusSafeGreen,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            val sortedCats = day.categoryMinutes.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }

            if (sortedCats.isEmpty()) {
                Text(
                    text = stringResource(R.string.insights_day_no_usage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    sortedCats.forEach { (cat, mins) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(cat.chartColor)
                                )
                                Text(
                                    text = stringResource(cat.labelRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = formatDurationLabel(mins),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBreakdownRow(
    slice: CategorySlice,
    maxMinutes: Int,
    isHighlighted: Boolean,
    onClick: () -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = (slice.minutes.toFloat() / maxMinutes.toFloat()).coerceIn(0.05f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "categoryRowProgress"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isHighlighted) {
            slice.category.chartColor.copy(alpha = 0.1f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
        },
        border = if (isHighlighted) BorderStroke(1.dp, slice.category.chartColor) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("category_breakdown_${slice.category.name.lowercase()}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(slice.category.chartColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = slice.category.icon,
                        contentDescription = stringResource(slice.category.labelRes),
                        tint = slice.category.chartColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(slice.category.labelRes),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.insights_percent_of_total, slice.sharePercent),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = formatDurationLabel(slice.minutes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = slice.category.chartColor
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(50)),
                color = slice.category.chartColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            AnimatedVisibility(visible = isHighlighted || slice.apps.isNotEmpty()) {
                if (slice.apps.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        val appsToShow = if (isHighlighted) slice.apps else slice.apps.take(2)
                        appsToShow.forEach { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatDurationLabel(app.minutes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParentBehaviorInsightsCard(
    insights: List<BehaviorInsight>,
    onNavigateToControls: () -> Unit,
    onOpenCategoryLimits: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Insights,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.insights_behavior_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            insights.forEach { insight ->
                val message = when (insight.code) {
                    BehaviorInsightCode.OVER_DAILY_LIMIT -> stringResource(
                        R.string.insights_msg_over_limit,
                        insight.primaryArg,
                        insight.secondaryArg
                    )
                    BehaviorInsightCode.HEALTHY_WITHIN_LIMIT -> stringResource(
                        R.string.insights_msg_within_limit,
                        insight.primaryArg,
                        insight.secondaryArg
                    )
                    BehaviorInsightCode.LATE_NIGHT_ACTIVITY -> stringResource(
                        R.string.insights_msg_late_night,
                        insight.primaryArg
                    )
                    BehaviorInsightCode.DOMINANT_ENTERTAINMENT -> stringResource(
                        R.string.insights_msg_dominant_category,
                        insight.category?.let { stringResource(it.labelRes) }.orEmpty(),
                        insight.primaryArg
                    )
                    BehaviorInsightCode.STRONG_LEARNING_BALANCE -> stringResource(
                        R.string.insights_msg_learning_balance,
                        insight.primaryArg,
                        insight.secondaryArg
                    )
                }

                val tint = if (insight.isWarning) StatusWarnAmber else StatusSafeGreen
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = tint.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = if (insight.isWarning) Icons.Filled.WarningAmber else Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(19.dp)
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Button(
                onClick = onOpenCategoryLimits,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("insights_open_category_limits_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.cat_limits_open_from_insights_cta))
            }

            OutlinedButton(
                onClick = onNavigateToControls,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("insights_adjust_limits_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.insights_adjust_rules_cta))
            }
        }
    }
}

@Composable
private fun InsightsEmptyStateCard(
    usageAccessGranted: Boolean,
    onRequestUsageAccess: () -> Unit,
    onSyncNow: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Insights,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Text(
                text = stringResource(R.string.insights_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    if (usageAccessGranted) R.string.insights_empty_sync_body else R.string.insights_empty_permission_body
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = if (usageAccessGranted) onSyncNow else onRequestUsageAccess,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("insights_empty_cta_button")
            ) {
                Text(
                    text = stringResource(
                        if (usageAccessGranted) R.string.insights_sync_now_cta else R.string.insights_grant_access_cta
                    )
                )
            }
        }
    }
}
