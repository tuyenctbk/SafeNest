package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.analytics.AppUsageCategory
import com.example.engine.CategoryEnforcementSnapshot
import com.example.engine.CategoryLimitPreset
import com.example.service.DeviceHealthStatus
import com.example.service.VpnRuntimeState
import com.example.ui.components.CategoryEnforcementHeroCard
import com.example.ui.components.CategoryLimitRuleCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryLimitsScreen(
    enforcement: CategoryEnforcementSnapshot,
    deviceHealth: DeviceHealthStatus,
    vpnState: VpnRuntimeState,
    isCheckingUsage: Boolean,
    onUpdateCategoryLimit: (AppUsageCategory, Boolean, Int) -> Unit,
    onApplyPreset: (CategoryLimitPreset) -> Unit,
    onClearAllLimits: () -> Unit,
    onCheckAndEnforceNow: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestEnableVpn: () -> Unit
) {
    // Auto-refresh live UsageStats when opening this screen if Usage Access is already granted
    LaunchedEffect(deviceHealth.usageAccessGranted) {
        if (deviceHealth.usageAccessGranted) {
            onCheckAndEnforceNow()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("category_limits_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Live UsageStats + VpnService Enforcement Hero Card
        item {
            CategoryEnforcementHeroCard(
                enforcement = enforcement,
                usageAccessGranted = deviceHealth.usageAccessGranted,
                vpnRunning = vpnState.running,
                vpnAutoBlockedCount = vpnState.categoryBlockedPatterns.size,
                isChecking = isCheckingUsage,
                onRequestUsageAccess = onRequestUsageAccess,
                onToggleVpn = onRequestEnableVpn,
                onCheckAndEnforceNow = onCheckAndEnforceNow
            )
        }

        // 2. Quick Schedule Presets Card
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.cat_limits_presets_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.cat_limits_presets_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (enforcement.activeRuleCount > 0) {
                            TextButton(
                                onClick = onClearAllLimits,
                                modifier = Modifier.testTag("cat_limits_reset_all_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.RestartAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(stringResource(R.string.cat_limits_reset_all))
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(
                            onClick = { onApplyPreset(CategoryLimitPreset.SCHOOL_NIGHT) },
                            label = { Text(stringResource(R.string.cat_limits_preset_school)) },
                            modifier = Modifier.testTag("cat_limits_preset_school")
                        )
                        AssistChip(
                            onClick = { onApplyPreset(CategoryLimitPreset.BALANCED) },
                            label = { Text(stringResource(R.string.cat_limits_preset_balanced)) },
                            modifier = Modifier.testTag("cat_limits_preset_balanced")
                        )
                        AssistChip(
                            onClick = { onApplyPreset(CategoryLimitPreset.WEEKEND) },
                            label = { Text(stringResource(R.string.cat_limits_preset_weekend)) },
                            modifier = Modifier.testTag("cat_limits_preset_weekend")
                        )
                    }
                }
            }
        }

        // 3. Per-Category Hard Daily Limit Cards
        items(
            items = enforcement.statuses,
            key = { it.category.name }
        ) { status ->
            CategoryLimitRuleCard(
                status = status,
                vpnRunning = vpnState.running,
                onUpdateLimit = { enabled, minutes ->
                    onUpdateCategoryLimit(status.category, enabled, minutes)
                }
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}
