package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ChildProfile
import com.example.engine.CategoryEnforcementSnapshot
import com.example.service.InstalledLauncherApp
import com.example.ui.components.BlockedWebsitesEntryCard
import com.example.ui.components.CategoryLimitsEntryCard
import com.example.ui.components.ConfirmDeleteChildDialog
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsScreen(
    activeChild: ChildProfile?,
    installedApps: List<InstalledLauncherApp>,
    categoryEnforcementState: CategoryEnforcementSnapshot,
    totalBlockedWebsitesCount: Int = 0,
    customBlockedWebsitesCount: Int = 0,
    vpnRunning: Boolean = false,
    onOpenCategoryLimits: () -> Unit,
    onOpenBlockedWebsites: () -> Unit = {},
    onUpdateRule: (ChildProfile, String, Any) -> Unit,
    onUpdateBedtime: (ChildProfile, String, String) -> Unit,
    onAddCustomDomain: (ChildProfile, String) -> Boolean,
    onRemoveCustomDomain: (ChildProfile, String) -> Unit,
    onToggleBlockedApp: (ChildProfile, String) -> Unit,
    onDeleteChild: (ChildProfile) -> Unit,
    onOpenAddChild: () -> Unit
) {
    if (activeChild == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.controls_no_child), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onOpenAddChild) {
                    Text(stringResource(R.string.add_child_profile))
                }
                Spacer(Modifier.height(8.dp))
                BlockedWebsitesEntryCard(
                    totalBlockedCount = totalBlockedWebsitesCount,
                    customCount = customBlockedWebsitesCount,
                    vpnRunning = vpnRunning,
                    onClick = onOpenBlockedWebsites
                )
                CategoryLimitsEntryCard(
                    enforcement = categoryEnforcementState,
                    onClick = onOpenCategoryLimits
                )
            }
        }
        return
    }

    var sliderMinutes by remember(activeChild.id, activeChild.dailyLimitMin) {
        mutableFloatStateOf(activeChild.dailyLimitMin.toFloat())
    }
    var customDomainInput by remember { mutableStateOf("") }
    var appSearchQuery by remember { mutableStateOf("") }
    var showAllApps by remember { mutableStateOf(false) }
    var confirmDeleteOpen by remember { mutableStateOf(false) }

    val bedtimePresets = listOf(
        Triple(R.string.bedtime_preset_early, "20:30", "06:30"),
        Triple(R.string.bedtime_preset_school, "21:30", "06:30"),
        Triple(R.string.bedtime_preset_teen, "22:30", "07:00")
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Screen Time Limit Card
        item {
            RuleSectionCard(
                icon = Icons.Filled.Timer,
                title = stringResource(R.string.daily_limit_title),
                subtitle = stringResource(R.string.daily_limit_subtitle, activeChild.name)
            ) {
                val displayMins = ((sliderMinutes / 15f).roundToInt() * 15).coerceIn(15, 720)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.hours_minutes_per_day, displayMins / 60, displayMins % 60),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.used_minutes_label, activeChild.todayScreenTimeMin),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = sliderMinutes,
                    onValueChange = { sliderMinutes = it },
                    onValueChangeFinished = {
                        val snapped = ((sliderMinutes / 15f).roundToInt() * 15).toLong().coerceIn(15L, 720L)
                        onUpdateRule(activeChild, "dailyLimitMin", snapped)
                    },
                    valueRange = 30f..480f,
                    modifier = Modifier.testTag("daily_limit_slider")
                )
            }
        }

        // Hard Daily Category Time Limits (UsageStats + VpnService Enforcement)
        item {
            CategoryLimitsEntryCard(
                enforcement = categoryEnforcementState,
                onClick = onOpenCategoryLimits
            )
        }

        // Blocked Websites Manager (Local DNS Filtering)
        item {
            BlockedWebsitesEntryCard(
                totalBlockedCount = totalBlockedWebsitesCount,
                customCount = customBlockedWebsitesCount,
                vpnRunning = vpnRunning,
                onClick = onOpenBlockedWebsites
            )
        }

        // Bedtime Schedule Card
        item {
            RuleSectionCard(
                icon = Icons.Filled.Bedtime,
                title = stringResource(R.string.bedtime_title),
                subtitle = stringResource(R.string.bedtime_window_subtitle, activeChild.bedtimeStart, activeChild.bedtimeEnd)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    bedtimePresets.forEach { (labelRes, start, end) ->
                        val isSelected = activeChild.bedtimeStart == start && activeChild.bedtimeEnd == end
                        FilterChip(
                            selected = isSelected,
                            onClick = { onUpdateBedtime(activeChild, start, end) },
                            label = { Text(stringResource(labelRes)) },
                            modifier = Modifier.testTag("bedtime_preset_${start.replace(":", "")}")
                        )
                    }
                }
            }
        }

        // Local DNS Web & Edge AI Protection Toggles
        item {
            RuleSectionCard(
                icon = Icons.Filled.Dns,
                title = stringResource(R.string.dns_categories_title),
                subtitle = stringResource(R.string.dns_categories_subtitle)
            ) {
                RuleToggleRow(
                    title = stringResource(R.string.rule_block_adult_title),
                    subtitle = stringResource(R.string.rule_block_adult_sub),
                    checked = activeChild.blockAdult,
                    tag = "toggle_block_adult",
                    onCheckedChange = { onUpdateRule(activeChild, "blockAdult", it) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RuleToggleRow(
                    title = stringResource(R.string.rule_block_gambling_title),
                    subtitle = stringResource(R.string.rule_block_gambling_sub),
                    checked = activeChild.blockGambling,
                    tag = "toggle_block_gambling",
                    onCheckedChange = { onUpdateRule(activeChild, "blockGambling", it) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RuleToggleRow(
                    title = stringResource(R.string.rule_block_malware_title),
                    subtitle = stringResource(R.string.rule_block_malware_sub),
                    checked = activeChild.blockMalware,
                    tag = "toggle_block_malware",
                    onCheckedChange = { onUpdateRule(activeChild, "blockMalware", it) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RuleToggleRow(
                    title = stringResource(R.string.rule_block_social_title),
                    subtitle = stringResource(R.string.rule_block_social_sub),
                    checked = activeChild.blockSocial,
                    tag = "toggle_block_social",
                    onCheckedChange = { onUpdateRule(activeChild, "blockSocial", it) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RuleToggleRow(
                    title = stringResource(R.string.rule_safesearch_title),
                    subtitle = stringResource(R.string.rule_safesearch_sub),
                    checked = activeChild.safeSearchEnabled,
                    tag = "toggle_safesearch",
                    onCheckedChange = { onUpdateRule(activeChild, "safeSearchEnabled", it) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RuleToggleRow(
                    title = stringResource(R.string.rule_edge_ai_title),
                    subtitle = stringResource(R.string.rule_edge_ai_sub),
                    checked = activeChild.aiProtectionEnabled,
                    tag = "toggle_edge_ai",
                    onCheckedChange = { onUpdateRule(activeChild, "aiProtectionEnabled", it) }
                )
            }
        }

        // Custom Blocked Domains
        item {
            RuleSectionCard(
                icon = Icons.Filled.Dns,
                title = stringResource(R.string.custom_domains_title),
                subtitle = stringResource(R.string.custom_domains_subtitle)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val submitCustomDomain = {
                        if (customDomainInput.trim().length >= 3) {
                            if (onAddCustomDomain(activeChild, customDomainInput)) {
                                customDomainInput = ""
                            }
                        }
                    }
                    OutlinedTextField(
                        value = customDomainInput,
                        onValueChange = { customDomainInput = it },
                        label = { Text(stringResource(R.string.custom_domain_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitCustomDomain() }),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_domain_input")
                    )
                    Button(
                        onClick = submitCustomDomain,
                        enabled = customDomainInput.trim().length >= 3,
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("add_custom_domain_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_domain_cd))
                    }
                }

                if (activeChild.customBlockedDomains.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        activeChild.customBlockedDomains.forEach { domain ->
                            InputChip(
                                selected = true,
                                onClick = { onRemoveCustomDomain(activeChild, domain) },
                                label = {
                                    Text(
                                        text = domain,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.remove_domain_cd, domain),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.custom_domains_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // App Restrictions (Real installed launcher apps via Play-compliant <queries>)
        item {
            RuleSectionCard(
                icon = Icons.Filled.Apps,
                title = stringResource(R.string.app_restrictions_title, activeChild.blockedAppPackages.size),
                subtitle = stringResource(R.string.app_restrictions_subtitle)
            ) {
                OutlinedTextField(
                    value = appSearchQuery,
                    onValueChange = { appSearchQuery = it },
                    label = { Text(stringResource(R.string.filter_installed_apps_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val allMatchingApps = remember(installedApps, appSearchQuery, activeChild.blockedAppPackages) {
                    val q = appSearchQuery.trim().lowercase()
                    installedApps
                        .filter {
                            q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                        }
                        .sortedByDescending { activeChild.blockedAppPackages.contains(it.packageName) }
                }
                val visibleApps = if (showAllApps || appSearchQuery.isNotBlank()) {
                    allMatchingApps
                } else {
                    allMatchingApps.take(10)
                }
                if (visibleApps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_matching_apps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        visibleApps.forEach { app ->
                            val isBlocked = activeChild.blockedAppPackages.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(
                                    checked = isBlocked,
                                    onCheckedChange = { onToggleBlockedApp(activeChild, app.packageName) }
                                )
                            }
                        }
                        if (allMatchingApps.size > 10 && appSearchQuery.isBlank()) {
                            TextButton(
                                onClick = { showAllApps = !showAllApps },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text(
                                    if (showAllApps) stringResource(R.string.show_fewer_apps)
                                    else stringResource(R.string.show_all_apps, allMatchingApps.size)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Remove Child Profile (with Confirmation Dialog)
        item {
            OutlinedButton(
                onClick = { confirmDeleteOpen = true },
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("delete_child_profile_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.delete_profile_cd),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.remove_child_profile, activeChild.name),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }

    if (confirmDeleteOpen) {
        ConfirmDeleteChildDialog(
            childName = activeChild.name,
            onDismiss = { confirmDeleteOpen = false },
            onConfirmDelete = {
                confirmDeleteOpen = false
                onDeleteChild(activeChild)
            }
        )
    }
}

@Composable
private fun RuleSectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun RuleToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}
