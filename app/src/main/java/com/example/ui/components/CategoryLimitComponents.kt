package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.CategoryEnforcementSnapshot
import com.example.engine.CategoryLimitStatus
import com.example.engine.CategoryTimeLimitEngine
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusSafeGreen
import com.example.ui.theme.StatusWarnAmber
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryEnforcementHeroCard(
    enforcement: CategoryEnforcementSnapshot,
    usageAccessGranted: Boolean,
    vpnRunning: Boolean,
    vpnAutoBlockedCount: Int,
    isChecking: Boolean,
    onRequestUsageAccess: () -> Unit,
    onToggleVpn: () -> Unit,
    onCheckAndEnforceNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lockedCount = enforcement.exceededCategories.size
    val fullyReady = usageAccessGranted && vpnRunning
    val heroStart by animateColorAsState(
        targetValue = when {
            lockedCount > 0 && vpnRunning -> Color(0xFFB3392B)
            fullyReady -> MaterialTheme.colorScheme.primary
            else -> Color(0xFF344947)
        },
        animationSpec = spring(),
        label = "catHeroStart"
    )
    val heroEnd by animateColorAsState(
        targetValue = when {
            lockedCount > 0 && vpnRunning -> Color(0xFF7D241C)
            fullyReady -> Color(0xFF167B72)
            else -> Color(0xFF213130)
        },
        animationSpec = spring(),
        label = "catHeroEnd"
    )

    Card(
        shape = RoundedCornerShape(26.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(heroStart, heroEnd)))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (lockedCount > 0) Icons.Filled.Block else Icons.Filled.HourglassBottom,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = when {
                                lockedCount > 0 -> stringResource(R.string.cat_limits_hero_enforcing, lockedCount)
                                fullyReady -> stringResource(R.string.cat_limits_hero_armed)
                                else -> stringResource(R.string.cat_limits_hero_setup)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.cat_limits_hero_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.86f)
                        )
                    }
                }

                // Live Enforcement Metrics Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CategoryHeroMetric(
                        label = stringResource(R.string.cat_limits_metric_active),
                        value = "${enforcement.activeRuleCount}",
                        modifier = Modifier.weight(1f)
                    )
                    CategoryHeroMetric(
                        label = stringResource(R.string.cat_limits_metric_locked),
                        value = "$lockedCount",
                        modifier = Modifier.weight(1f)
                    )
                    CategoryHeroMetric(
                        label = stringResource(R.string.cat_limits_metric_vpn_rules),
                        value = "${maxOf(vpnAutoBlockedCount, enforcement.autoBlockedPatterns.size)}",
                        modifier = Modifier.weight(1f)
                    )
                }

                // Readiness Status Pills (UsageStats + VpnService)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReadinessPill(
                        ready = usageAccessGranted,
                        text = stringResource(
                            if (usageAccessGranted) R.string.cat_limits_chip_usage_ok else R.string.cat_limits_chip_usage_grant
                        ),
                        onClick = if (usageAccessGranted) null else onRequestUsageAccess,
                        testTag = "cat_limits_usage_status_chip"
                    )
                    ReadinessPill(
                        ready = vpnRunning,
                        text = stringResource(
                            if (vpnRunning) R.string.cat_limits_chip_vpn_ok else R.string.cat_limits_chip_vpn_enable
                        ),
                        onClick = if (vpnRunning) null else onToggleVpn,
                        testTag = "cat_limits_vpn_status_chip"
                    )
                }

                Button(
                    onClick = onCheckAndEnforceNow,
                    enabled = !isChecking,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF0E3D39)
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("cat_limits_check_enforce_button")
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF0E3D39)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.cat_limits_check_now_cta),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryHeroMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.14f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReadinessPill(
    ready: Boolean,
    text: String,
    onClick: (() -> Unit)?,
    testTag: String
) {
    val bgColor = if (ready) Color(0xFF0B4F47).copy(alpha = 0.65f) else Color(0xFF5A3716).copy(alpha = 0.8f)
    val borderColor = if (ready) Color(0xFF7BE8D7).copy(alpha = 0.55f) else Color(0xFFFFCA8A).copy(alpha = 0.65f)
    val tint = if (ready) Color(0xFF6CF0BE) else Color(0xFFFFD199)

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .testTag(testTag)
            .let { mod -> if (onClick != null) mod.clickable(onClick = onClick) else mod }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (ready) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryLimitRuleCard(
    status: CategoryLimitStatus,
    vpnRunning: Boolean,
    onUpdateLimit: (enabled: Boolean, limitMinutes: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderValue by remember(status.category, status.limitMinutes) {
        mutableFloatStateOf(status.limitMinutes.toFloat())
    }
    var rulesExpanded by remember(status.category) { mutableStateOf(false) }

    val animatedProgress by animateFloatAsState(
        targetValue = if (status.enabled) status.progressFraction else 0f,
        animationSpec = spring(),
        label = "catProgress_${status.category.name}"
    )

    val quickMinuteOptions = listOf(15, 30, 45, 60, 90, 120)
    val cardBorderColor = when {
        status.isLimitReached -> StatusDangerRed.copy(alpha = 0.55f)
        status.enabled -> status.category.chartColor.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (status.isLimitReached) 1.5.dp else 1.dp, cardBorderColor),
        modifier = modifier
            .fillMaxWidth()
            .testTag("category_limit_card_${status.category.name.lowercase()}")
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row: Category Icon + Name + Live Status Badge + Master Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(status.category.chartColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = status.category.icon,
                            contentDescription = stringResource(status.category.labelRes),
                            tint = status.category.chartColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(status.category.labelRes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        CategoryStatusBadge(status = status)
                    }
                }

                Switch(
                    checked = status.enabled,
                    onCheckedChange = { enabled ->
                        onUpdateLimit(enabled, status.limitMinutes)
                    },
                    modifier = Modifier.testTag("category_limit_switch_${status.category.name.lowercase()}")
                )
            }

            // Real UsageStats Today Progress Bar + Caption
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (status.enabled) {
                        stringResource(R.string.cat_limits_used_of_limit, status.usedMinutes, status.limitMinutes)
                    } else {
                        stringResource(R.string.cat_limits_used_unlimited, status.usedMinutes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.isLimitReached) StatusDangerRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (status.isLimitReached) FontWeight.SemiBold else FontWeight.Normal
                )
                if (status.enabled) {
                    val barColor = when {
                        status.isLimitReached -> StatusDangerRed
                        status.progressFraction >= 0.8f -> StatusWarnAmber
                        else -> status.category.chartColor
                    }
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        color = barColor,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                }
                if (status.topApps.isNotEmpty()) {
                    val topAppParts = ArrayList<String>(status.topApps.size)
                    for (app in status.topApps) {
                        val minLabel = stringResource(R.string.minutes_short, app.minutes)
                        topAppParts.add("${app.appName} ($minLabel)")
                    }
                    val topAppsSummary = topAppParts.joinToString(", ")
                    Text(
                        text = stringResource(R.string.cat_limits_top_apps_today, topAppsSummary),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Expandable Limit Controls & Enforced VPN Domain Rules
            AnimatedVisibility(visible = status.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val snappedPreview = ((sliderValue / 5f).roundToInt() * 5)
                        .coerceIn(CategoryTimeLimitEngine.MIN_LIMIT_MINUTES, CategoryTimeLimitEngine.MAX_LIMIT_MINUTES)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.hours_minutes_per_day, snappedPreview / 60, snappedPreview % 60),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${snappedPreview}m",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            val snapped = ((sliderValue / 5f).roundToInt() * 5)
                                .coerceIn(CategoryTimeLimitEngine.MIN_LIMIT_MINUTES, CategoryTimeLimitEngine.MAX_LIMIT_MINUTES)
                            onUpdateLimit(true, snapped)
                        },
                        valueRange = CategoryTimeLimitEngine.MIN_LIMIT_MINUTES.toFloat()..CategoryTimeLimitEngine.MAX_LIMIT_MINUTES.toFloat(),
                        modifier = Modifier.testTag("category_limit_slider_${status.category.name.lowercase()}")
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        quickMinuteOptions.forEach { mins ->
                            FilterChip(
                                selected = status.limitMinutes == mins,
                                onClick = {
                                    sliderValue = mins.toFloat()
                                    onUpdateLimit(true, mins)
                                },
                                label = { Text("${mins}m", style = MaterialTheme.typography.labelMedium) },
                                modifier = Modifier.testTag("category_limit_chip_${status.category.name.lowercase()}_$mins")
                            )
                        }
                    }

                    // Enforced VPN DNS Block Rules Preview Accordion
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (status.isLimitReached && vpnRunning) {
                            StatusDangerRed.copy(alpha = 0.09f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        border = BorderStroke(
                            1.dp,
                            if (status.isLimitReached && vpnRunning) StatusDangerRed.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { rulesExpanded = !rulesExpanded }
                            .testTag("category_vpn_rules_toggle_${status.category.name.lowercase()}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Shield,
                                        contentDescription = null,
                                        tint = if (status.isLimitReached) StatusDangerRed else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column {
                                        Text(
                                            text = stringResource(
                                                R.string.cat_limits_vpn_rules_toggle,
                                                status.enforcedDomainPatterns.size
                                            ),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = stringResource(
                                                if (status.isLimitReached && vpnRunning) R.string.cat_limits_vpn_rules_active_note
                                                else R.string.cat_limits_vpn_rules_standby_note
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (status.isLimitReached && vpnRunning) StatusDangerRed
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = if (rulesExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            AnimatedVisibility(visible = rulesExpanded) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    status.enforcedDomainPatterns.forEach { pattern ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                        ) {
                                            Text(
                                                text = pattern,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryStatusBadge(status: CategoryLimitStatus) {
    val (bg, fg, text) = when {
        status.isLimitReached -> Triple(
            StatusDangerRed.copy(alpha = 0.14f),
            StatusDangerRed,
            stringResource(R.string.cat_limits_status_locked)
        )
        status.enabled -> Triple(
            StatusSafeGreen.copy(alpha = 0.14f),
            StatusSafeGreen,
            stringResource(R.string.cat_limits_status_remaining, status.remainingMinutes)
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.cat_limits_status_unlimited)
        )
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * Compact launcher card rendered on ControlsScreen to jump straight to Hard Category Time Limits.
 */
@Composable
fun CategoryLimitsEntryCard(
    enforcement: CategoryEnforcementSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lockedCount = enforcement.exceededCategories.size
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (lockedCount > 0) 1.5.dp else 1.dp,
            color = if (lockedCount > 0) StatusDangerRed.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("open_category_limits_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (lockedCount > 0) StatusDangerRed.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.HourglassBottom,
                    contentDescription = null,
                    tint = if (lockedCount > 0) StatusDangerRed else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cat_limits_entry_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (lockedCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = StatusDangerRed.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = stringResource(R.string.cat_limits_entry_locked_badge, lockedCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusDangerRed,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    } else if (enforcement.activeRuleCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.cat_limits_entry_active_badge, enforcement.activeRuleCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.cat_limits_entry_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
