package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.analytics.DailyCategoryUsage
import com.example.analytics.WeeklySummaryEngine
import com.example.analytics.WeeklySummaryReport
import com.example.analytics.WeeklyTrendDirection
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusSafeGreen
import com.example.ui.theme.StatusWarnAmber
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeeklySummaryCard(
    report: WeeklySummaryReport,
    weeklyPushEnabled: Boolean,
    onToggleWeeklyPush: (Boolean) -> Unit,
    onSendPushNow: () -> Unit,
    onOpenDetails: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val trendTint = when (report.trendDirection) {
        WeeklyTrendDirection.DOWN_IMPROVED -> StatusSafeGreen
        WeeklyTrendDirection.UP_INCREASED -> if ((report.deltaPercent ?: 0) >= 20) StatusDangerRed else StatusWarnAmber
        WeeklyTrendDirection.STEADY -> MaterialTheme.colorScheme.primary
        WeeklyTrendDirection.FIRST_WEEK -> MaterialTheme.colorScheme.secondary
        WeeklyTrendDirection.NO_DATA -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val trendIcon = when (report.trendDirection) {
        WeeklyTrendDirection.DOWN_IMPROVED -> Icons.AutoMirrored.Filled.TrendingDown
        WeeklyTrendDirection.UP_INCREASED -> Icons.AutoMirrored.Filled.TrendingUp
        WeeklyTrendDirection.STEADY,
        WeeklyTrendDirection.FIRST_WEEK,
        WeeklyTrendDirection.NO_DATA -> Icons.AutoMirrored.Filled.TrendingFlat
    }

    val trendBadgeText = when (report.trendDirection) {
        WeeklyTrendDirection.DOWN_IMPROVED -> stringResource(
            R.string.weekly_trend_down_badge,
            abs(report.deltaPercent ?: 0)
        )
        WeeklyTrendDirection.UP_INCREASED -> stringResource(
            R.string.weekly_trend_up_badge,
            abs(report.deltaPercent ?: 0)
        )
        WeeklyTrendDirection.STEADY -> stringResource(R.string.weekly_trend_steady_badge)
        WeeklyTrendDirection.FIRST_WEEK -> stringResource(R.string.weekly_trend_first_week_badge)
        WeeklyTrendDirection.NO_DATA -> stringResource(R.string.weekly_trend_no_data_badge)
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("weekly_summary_card")
    ) {
        Column {
            // Top Header Band with soft Nordic gradient wash
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                trendTint.copy(alpha = 0.12f)
                            )
                        )
                    )
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CalendarMonth,
                                contentDescription = stringResource(R.string.weekly_summary_title),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.weekly_summary_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (report.weekRangeLabel.isNotBlank()) {
                                    stringResource(
                                        R.string.weekly_summary_subtitle_range,
                                        report.childName,
                                        report.weekRangeLabel
                                    )
                                } else {
                                    report.childName
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = trendTint.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, trendTint.copy(alpha = 0.35f)),
                        modifier = Modifier.testTag("weekly_trend_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = trendIcon,
                                contentDescription = null,
                                tint = trendTint,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = trendBadgeText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = trendTint
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!report.hasAnyData) {
                    // Empty state with friendly tip
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BarChart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                            Text(
                                text = stringResource(R.string.weekly_summary_empty_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // This Week vs Previous Week Side-by-Side Metric Tiles
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        WeeklyCompareBox(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.weekly_metric_this_week),
                            totalText = WeeklySummaryEngine.formatCompactDuration(report.thisWeekTotalMin),
                            avgText = stringResource(
                                R.string.weekly_metric_daily_avg,
                                WeeklySummaryEngine.formatCompactDuration(report.thisWeekDailyAvgMin)
                            ),
                            accentColor = MaterialTheme.colorScheme.primary,
                            highlighted = true
                        )
                        WeeklyCompareBox(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.weekly_metric_last_week),
                            totalText = if (report.hasPreviousWeekBaseline) {
                                WeeklySummaryEngine.formatCompactDuration(report.previousWeekTotalMin)
                            } else {
                                "—"
                            },
                            avgText = if (report.hasPreviousWeekBaseline) {
                                stringResource(
                                    R.string.weekly_metric_daily_avg,
                                    WeeklySummaryEngine.formatCompactDuration(report.previousWeekDailyAvgMin)
                                )
                            } else {
                                stringResource(R.string.weekly_metric_building_baseline)
                            },
                            accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            highlighted = false
                        )
                    }

                    // Human-readable trend takeaway line
                    val deltaDuration = WeeklySummaryEngine.formatCompactDuration(report.deltaMinutes)
                    val headlineMessage = when (report.trendDirection) {
                        WeeklyTrendDirection.DOWN_IMPROVED -> stringResource(
                            R.string.weekly_summary_msg_down,
                            deltaDuration,
                            abs(report.deltaPercent ?: 0)
                        )
                        WeeklyTrendDirection.UP_INCREASED -> stringResource(
                            R.string.weekly_summary_msg_up,
                            deltaDuration,
                            abs(report.deltaPercent ?: 0)
                        )
                        WeeklyTrendDirection.STEADY -> stringResource(R.string.weekly_summary_msg_steady)
                        WeeklyTrendDirection.FIRST_WEEK -> stringResource(
                            R.string.weekly_summary_msg_first_week,
                            WeeklySummaryEngine.formatCompactDuration(report.thisWeekDailyAvgMin)
                        )
                        WeeklyTrendDirection.NO_DATA -> stringResource(R.string.weekly_summary_empty_hint)
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = trendTint.copy(alpha = 0.09f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = headlineMessage,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }

                    // Dual-Week Grouped Bar Sparkline Chart (This Week vs Last Week)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.weekly_chart_title),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LegendDot(
                                    color = MaterialTheme.colorScheme.primary,
                                    label = stringResource(R.string.weekly_legend_this_week)
                                )
                                LegendDot(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                    label = stringResource(R.string.weekly_legend_last_week)
                                )
                            }
                        }

                        WeeklyComparisonMiniChart(
                            thisWeekDays = report.thisWeekDays,
                            previousWeekDays = report.previousWeekDays,
                            dailyLimitMin = report.dailyLimitMin
                        )
                    }

                    // Category Shifts + On-Target Days Chips
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (report.activeDaysThisWeek > 0) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = StatusSafeGreen.copy(alpha = 0.12f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = StatusSafeGreen,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.weekly_chip_days_on_target,
                                            report.daysWithinLimitThisWeek,
                                            report.activeDaysThisWeek
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = StatusSafeGreen
                                    )
                                }
                            }
                        }

                        report.topRisingCategory?.let { rising ->
                            val catName = stringResource(rising.category.labelRes)
                            val diff = WeeklySummaryEngine.formatCompactDuration(rising.deltaMinutes)
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = StatusWarnAmber.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = stringResource(R.string.weekly_chip_category_up, catName, diff),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StatusWarnAmber,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        report.topImprovedCategory?.let { improved ->
                            val catName = stringResource(improved.category.labelRes)
                            val diff = WeeklySummaryEngine.formatCompactDuration(improved.deltaMinutes)
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = StatusSafeGreen.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = stringResource(R.string.weekly_chip_category_down, catName, diff),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StatusSafeGreen,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

                // Weekly Push Digest Toggle Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (weeklyPushEnabled) {
                                Icons.Filled.NotificationsActive
                            } else {
                                Icons.Filled.NotificationsNone
                            },
                            contentDescription = null,
                            tint = if (weeklyPushEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.weekly_push_toggle_title),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.weekly_push_toggle_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = weeklyPushEnabled,
                        onCheckedChange = onToggleWeeklyPush,
                        modifier = Modifier.testTag("weekly_summary_push_toggle")
                    )
                }

                // Action Buttons Row (Send Push Notification + optional View Breakdown)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onSendPushNow,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("send_weekly_summary_push_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NotificationsActive,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.weekly_send_push_cta),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (onOpenDetails != null) {
                        OutlinedButton(
                            onClick = onOpenDetails,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("weekly_summary_view_details_button")
                        ) {
                            Text(
                                text = stringResource(R.string.weekly_view_details_cta),
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

@Composable
private fun WeeklyCompareBox(
    modifier: Modifier = Modifier,
    label: String,
    totalText: String,
    avgText: String,
    accentColor: Color,
    highlighted: Boolean
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        },
        border = if (highlighted) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
        } else {
            null
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = totalText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Text(
                text = avgText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WeeklyComparisonMiniChart(
    thisWeekDays: List<DailyCategoryUsage>,
    previousWeekDays: List<DailyCategoryUsage>,
    dailyLimitMin: Int
) {
    val thisBarColor = MaterialTheme.colorScheme.primary
    val overLimitColor = StatusWarnAmber
    val prevBarColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
    val limitLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    val maxMinutes = maxOf(
        dailyLimitMin,
        thisWeekDays.maxOfOrNull { it.totalMinutes } ?: 0,
        previousWeekDays.maxOfOrNull { it.totalMinutes } ?: 0,
        30
    )

    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(thisWeekDays, previousWeekDays) {
        animProgress.snapTo(0f)
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp)
                .testTag("weekly_comparison_canvas")
        ) {
            val count = maxOf(thisWeekDays.size, 7)
            val slotWidth = size.width / count.toFloat()
            val pairGap = 3.dp.toPx()
            val barWidth = (slotWidth * 0.3f).coerceIn(6.dp.toPx(), 16.dp.toPx())
            val corner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            val chartBottom = size.height - 2.dp.toPx()
            val chartTop = 6.dp.toPx()
            val usableHeight = (chartBottom - chartTop).coerceAtLeast(10f)

            // Dashed daily limit reference line
            val limitY = chartBottom - ((dailyLimitMin.toFloat() / maxMinutes.toFloat()).coerceIn(0f, 1f) * usableHeight)
            drawLine(
                color = limitLineColor,
                start = Offset(0f, limitY),
                end = Offset(size.width, limitY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            )

            for (i in 0 until count) {
                val centerX = slotWidth * i + slotWidth / 2f
                val prevMin = previousWeekDays.getOrNull(i)?.totalMinutes ?: 0
                val currMin = thisWeekDays.getOrNull(i)?.totalMinutes ?: 0

                val prevLeft = centerX - pairGap / 2f - barWidth
                val currLeft = centerX + pairGap / 2f

                // Subtle background track for empty days
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(prevLeft, chartBottom - 4.dp.toPx()),
                    size = Size(barWidth, 4.dp.toPx()),
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(currLeft, chartBottom - 4.dp.toPx()),
                    size = Size(barWidth, 4.dp.toPx()),
                    cornerRadius = corner
                )

                if (prevMin > 0) {
                    val h = ((prevMin.toFloat() / maxMinutes.toFloat()) * usableHeight * animProgress.value)
                        .coerceAtLeast(4.dp.toPx())
                    drawRoundRect(
                        color = prevBarColor,
                        topLeft = Offset(prevLeft, chartBottom - h),
                        size = Size(barWidth, h),
                        cornerRadius = corner
                    )
                }

                if (currMin > 0) {
                    val h = ((currMin.toFloat() / maxMinutes.toFloat()) * usableHeight * animProgress.value)
                        .coerceAtLeast(4.dp.toPx())
                    val color = if (currMin > dailyLimitMin) overLimitColor else thisBarColor
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(currLeft, chartBottom - h),
                        size = Size(barWidth, h),
                        cornerRadius = corner
                    )
                }
            }
        }

        // Weekday Labels Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            thisWeekDays.forEach { day ->
                Text(
                    text = day.shortDayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
