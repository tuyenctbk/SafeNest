package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.analytics.AppUsageCategory
import com.example.analytics.CategorySlice
import com.example.analytics.DailyCategoryUsage
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusWarnAmber
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot

@Composable
fun formatDurationLabel(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val hrs = safe / 60
    val rem = safe % 60
    return if (hrs > 0) {
        stringResource(R.string.insights_duration_hm, hrs, rem)
    } else {
        stringResource(R.string.minutes_short, rem)
    }
}

/**
 * Interactive Donut Chart built natively on Compose Canvas with slice tap hit-testing,
 * smooth spring reveal, and animated center callout.
 */
@Composable
fun CategoryDonutChart(
    slices: List<CategorySlice>,
    totalMinutes: Int,
    selectedCategory: AppUsageCategory?,
    onSelectCategory: (AppUsageCategory?) -> Unit,
    modifier: Modifier = Modifier
) {
    val revealProgress = remember { Animatable(0f) }
    LaunchedEffect(slices) {
        revealProgress.snapTo(0f)
        revealProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    val totalForSweep = slices.sumOf { it.minutes }.coerceAtLeast(1).toFloat()
    val sliceAngles = remember(slices, totalForSweep) {
        var cursor = -90f
        slices.map { slice ->
            val sweep = (slice.minutes / totalForSweep) * 360f
            val start = cursor
            cursor += sweep
            Triple(slice, start, sweep)
        }
    }

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val donutCd = stringResource(R.string.insights_donut_chart_cd)

    Box(
        modifier = modifier
            .size(216.dp)
            .semantics { contentDescription = donutCd }
            .testTag("category_donut_chart"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(sliceAngles, selectedCategory) {
                    detectTapGestures { offset ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val dx = offset.x - cx
                        val dy = offset.y - cy
                        val dist = hypot(dx, dy)
                        val outerRadius = minOf(size.width, size.height) / 2f
                        val innerRadius = outerRadius * 0.48f
                        if (dist < innerRadius || dist > outerRadius) {
                            onSelectCategory(null)
                            return@detectTapGestures
                        }
                        var angleDeg = (atan2(dy, dx) * 180.0 / PI).toFloat()
                        if (angleDeg < -90f) angleDeg += 360f
                        val hit = sliceAngles.firstOrNull { (_, start, sweep) ->
                            angleDeg >= start && angleDeg <= start + sweep
                        }?.first?.category
                        onSelectCategory(if (hit == selectedCategory) null else hit)
                    }
                }
        ) {
            val baseStroke = 26.dp.toPx()
            val selectedStroke = 33.dp.toPx()
            val maxStroke = selectedStroke
            val diameter = minOf(size.width, size.height) - maxStroke - 8.dp.toPx()
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)

            // Soft background ring
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = baseStroke)
            )

            val gapDeg = if (sliceAngles.size > 1) 2.5f else 0f
            val anim = revealProgress.value

            sliceAngles.forEach { (slice, start, rawSweep) ->
                val sweep = (rawSweep * anim - gapDeg).coerceAtLeast(1.2f)
                val isSelected = selectedCategory == slice.category
                val isDimmed = selectedCategory != null && !isSelected
                val strokeWidth = if (isSelected) selectedStroke else baseStroke
                val drawColor = if (isDimmed) {
                    slice.category.chartColor.copy(alpha = 0.28f)
                } else {
                    slice.category.chartColor
                }

                drawArc(
                    color = drawColor,
                    startAngle = start + gapDeg / 2f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                )
            }
        }

        // Animated Center Callout
        val activeSlice = slices.firstOrNull { it.category == selectedCategory }
        AnimatedContent(
            targetState = activeSlice,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
            label = "donutCenterCallout"
        ) { slice ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 28.dp)
            ) {
                if (slice != null) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(slice.category.chartColor)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatDurationLabel(slice.minutes),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(slice.category.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.insights_percent_of_total, slice.sharePercent),
                        style = MaterialTheme.typography.labelSmall,
                        color = slice.category.chartColor,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = formatDurationLabel(totalMinutes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.insights_total_screen_time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Interactive 7-Day Stacked Column Chart colored by AppUsageCategory, with a dashed
 * daily-limit reference line, linked category highlighting, and tap-to-inspect day columns.
 */
@Composable
fun DailyStackedBarChart(
    days: List<DailyCategoryUsage>,
    dailyLimitMin: Int,
    selectedDayIndex: Int,
    selectedCategory: AppUsageCategory?,
    onSelectDay: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val barAnim = remember { Animatable(0f) }
    LaunchedEffect(days) {
        barAnim.snapTo(0f)
        barAnim.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    val maxDayMin = days.maxOfOrNull { it.totalMinutes } ?: 0
    val rawCeiling = maxOf(maxDayMin, dailyLimitMin, 60)
    // Round ceiling nicely to a multiple of 30 minutes
    val yAxisMax = (ceil(rawCeiling / 30.0).toInt() * 30).coerceAtLeast(60)
    val midTick = yAxisMax / 2

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val limitLineColor = if (maxDayMin > dailyLimitMin) StatusDangerRed else StatusWarnAmber
    val selectedColumnBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    val barChartCd = stringResource(R.string.insights_stacked_bar_cd)
    val categoryOrder = remember { AppUsageCategory.entries }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = barChartCd }
            .testTag("daily_stacked_bar_chart")
    ) {
        // Reference Legend Row (Y-axis top & Daily limit badge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatDurationLabel(yAxisMax),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = limitLineColor.copy(alpha = 0.14f)
            ) {
                Text(
                    text = stringResource(R.string.insights_limit_line_label, dailyLimitMin),
                    style = MaterialTheme.typography.labelSmall,
                    color = limitLineColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(188.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(days) {
                        detectTapGestures { offset ->
                            if (days.isEmpty()) return@detectTapGestures
                            val leftGutter = 34.dp.toPx()
                            val plotW = (size.width - leftGutter).coerceAtLeast(1f)
                            val relX = (offset.x - leftGutter).coerceIn(0f, plotW - 1f)
                            val colIndex = ((relX / plotW) * days.size).toInt().coerceIn(0, days.lastIndex)
                            onSelectDay(colIndex)
                        }
                    }
            ) {
                val leftGutter = 34.dp.toPx()
                val topPad = 8.dp.toPx()
                val bottomPad = 6.dp.toPx()
                val plotWidth = (size.width - leftGutter).coerceAtLeast(1f)
                val plotHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)
                val baselineY = topPad + plotHeight

                // Horizontal gridlines (0%, 50%, 100%)
                listOf(0f, 0.5f, 1f).forEach { frac ->
                    val y = topPad + plotHeight * frac
                    drawLine(
                        color = gridColor,
                        start = Offset(leftGutter, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Stacked columns
                if (days.isNotEmpty()) {
                    val colSlotWidth = plotWidth / days.size
                    val barWidth = (colSlotWidth * 0.54f).coerceIn(14.dp.toPx(), 34.dp.toPx())
                    val cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    val anim = barAnim.value

                    days.forEachIndexed { index, day ->
                        val slotLeft = leftGutter + index * colSlotWidth
                        val barLeft = slotLeft + (colSlotWidth - barWidth) / 2f

                        // Selected column highlight pillar
                        if (index == selectedDayIndex) {
                            drawRoundRect(
                                color = selectedColumnBg,
                                topLeft = Offset(slotLeft + 2.dp.toPx(), topPad),
                                size = Size(colSlotWidth - 4.dp.toPx(), plotHeight),
                                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
                            )
                        }

                        var stackedHeight = 0f
                        val nonZeroSegments = categoryOrder.mapNotNull { cat ->
                            val mins = day.categoryMinutes[cat] ?: 0
                            if (mins > 0) cat to mins else null
                        }

                        nonZeroSegments.forEachIndexed { segIdx, (cat, mins) ->
                            val segH = ((mins.toFloat() / yAxisMax.toFloat()) * plotHeight * anim)
                                .coerceAtLeast(2.dp.toPx())
                            val segTop = (baselineY - stackedHeight - segH).coerceAtLeast(topPad)
                            val actualH = (baselineY - stackedHeight) - segTop
                            if (actualH > 0.5f) {
                                val isCatHighlighted = selectedCategory == null || selectedCategory == cat
                                val segColor = if (isCatHighlighted) {
                                    cat.chartColor
                                } else {
                                    cat.chartColor.copy(alpha = 0.22f)
                                }
                                val isTopSeg = segIdx == nonZeroSegments.lastIndex
                                drawRoundRect(
                                    color = segColor,
                                    topLeft = Offset(barLeft, segTop),
                                    size = Size(barWidth, actualH),
                                    cornerRadius = if (isTopSeg) cornerRadius else CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                )
                            }
                            stackedHeight += segH + 1.dp.toPx()
                        }
                    }
                }

                // Dashed Daily Limit Reference Line
                val limitRatio = (dailyLimitMin.toFloat() / yAxisMax.toFloat()).coerceIn(0f, 1f)
                val limitY = baselineY - (limitRatio * plotHeight)
                drawLine(
                    color = limitLineColor,
                    start = Offset(leftGutter, limitY),
                    end = Offset(size.width, limitY),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )
            }

            // Left Y-axis middle and zero labels aligned cleanly
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = stringResource(R.string.minutes_short, midTick),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.minutes_short, 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Interactive Day Axis Buttons (minimum accessible touch target)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 34.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            days.forEachIndexed { index, day ->
                val isSelected = index == selectedDayIndex
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectDay(index) }
                        .testTag("day_bar_label_$index")
                ) {
                    Text(
                        text = day.shortDayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}
