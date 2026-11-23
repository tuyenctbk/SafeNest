package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.LocalFilterSnapshot
import com.example.engine.BlockedWebsiteEntry
import com.example.engine.BlockedWebsiteSource
import com.example.engine.DnsEvaluation
import com.example.engine.LocalDnsFilterEngine
import com.example.service.VpnRuntimeState
import com.example.ui.components.BlockedWebsiteRow
import com.example.ui.components.ClearBlocklistConfirmationDialog
import com.example.ui.components.LocalDnsFullCheckCard
import com.example.ui.components.LocalDnsShieldToggleChip
import com.example.ui.theme.StatusDangerRed

private enum class WebsiteSourceFilter {
    ALL,
    CUSTOM,
    ADULT,
    GAMBLING,
    MALWARE,
    SOCIAL,
    TIME_LIMIT
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlockedWebsitesScreen(
    vpnState: VpnRuntimeState,
    filterSnapshot: LocalFilterSnapshot,
    activeBlockedWebsites: List<BlockedWebsiteEntry>,
    activeChildName: String?,
    onToggleDnsVpn: (Boolean) -> Unit,
    onAddDomainsBatch: (String) -> Int,
    onRemoveCustomDomain: (String) -> Unit,
    onClearCustomDomains: () -> Unit,
    onToggleDnsShield: (String, Boolean) -> Unit,
    onEvaluateWebsite: (String) -> DnsEvaluation
) {
    var domainInput by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf(WebsiteSourceFilter.ALL) }
    var checkQuery by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }

    val parsedCandidates = remember(domainInput) {
        LocalDnsFilterEngine.parseBatchDomainInputs(domainInput)
    }
    val hasInputError = domainInput.trim().isNotEmpty() && parsedCandidates.isEmpty()

    val customCount = remember(activeBlockedWebsites) {
        activeBlockedWebsites.count { it.source == BlockedWebsiteSource.CUSTOM }
    }
    val timeLimitCount = remember(activeBlockedWebsites) {
        activeBlockedWebsites.count { it.source == BlockedWebsiteSource.CATEGORY_LIMIT }
    }
    val activeShieldCount = listOf(
        filterSnapshot.blockAdult,
        filterSnapshot.blockGambling,
        filterSnapshot.blockMalware,
        filterSnapshot.blockSocial
    ).count { it }
    val shieldCounts = remember { LocalDnsFilterEngine.builtInShieldCounts() }

    val existingDomainSet = remember(activeBlockedWebsites) {
        activeBlockedWebsites.map { it.domain }.toSet()
    }
    val quickSuggestions = remember(existingDomainSet) {
        listOf("tiktok.com", "roblox.com", "instagram.com", "youtube.com", "reddit.com", "twitch.tv")
            .filterNot { existingDomainSet.contains(it) }
    }

    val filteredWebsites = remember(activeBlockedWebsites, searchQuery, sourceFilter) {
        val q = searchQuery.trim().lowercase()
        activeBlockedWebsites.filter { entry ->
            val matchesSource = when (sourceFilter) {
                WebsiteSourceFilter.ALL -> true
                WebsiteSourceFilter.CUSTOM -> entry.source == BlockedWebsiteSource.CUSTOM
                WebsiteSourceFilter.ADULT -> entry.source == BlockedWebsiteSource.ADULT_SHIELD
                WebsiteSourceFilter.GAMBLING -> entry.source == BlockedWebsiteSource.GAMBLING_SHIELD
                WebsiteSourceFilter.MALWARE -> entry.source == BlockedWebsiteSource.MALWARE_SHIELD
                WebsiteSourceFilter.SOCIAL -> entry.source == BlockedWebsiteSource.SOCIAL_SHIELD
                WebsiteSourceFilter.TIME_LIMIT -> entry.source == BlockedWebsiteSource.CATEGORY_LIMIT
            }
            val matchesQuery = q.isEmpty() || entry.domain.contains(q)
            matchesSource && matchesQuery
        }
    }

    val liveCheckResult = remember(
        checkQuery,
        filterSnapshot,
        vpnState.lastBlocklistSyncMs,
        vpnState.lastCategoryCheckMs
    ) {
        if (checkQuery.trim().isEmpty()) null else onEvaluateWebsite(checkQuery)
    }

    val heroStart by animateColorAsState(
        targetValue = if (vpnState.running) MaterialTheme.colorScheme.primary else Color(0xFF334745),
        animationSpec = spring(),
        label = "blockedSitesHeroStart"
    )
    val heroEnd by animateColorAsState(
        targetValue = if (vpnState.running) Color(0xFF0E6861) else Color(0xFF212F2E),
        animationSpec = spring(),
        label = "blockedSitesHeroEnd"
    )

    val submitInput: () -> Unit = {
        if (parsedCandidates.isNotEmpty() && onAddDomainsBatch(domainInput) > 0) {
            domainInput = ""
        }
    }

    if (showClearDialog) {
        ClearBlocklistConfirmationDialog(
            patternCount = customCount,
            onConfirm = {
                showClearDialog = false
                onClearCustomDomains()
            },
            onDismiss = { showClearDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("blocked_websites_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Hero Header: Local DNS Website Blocking Status & Live Counts
        item {
            Card(
                shape = RoundedCornerShape(26.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(listOf(heroStart, heroEnd)))
                        .padding(20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Language,
                                        contentDescription = stringResource(R.string.blocked_sites_screen_title),
                                        tint = if (vpnState.running) Color(0xFF7BE8D7) else Color.White.copy(alpha = 0.78f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = stringResource(R.string.blocked_sites_hero_title),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(
                                            if (vpnState.running) {
                                                R.string.blocked_sites_hero_active_desc
                                            } else {
                                                R.string.blocked_sites_hero_paused_desc
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            Switch(
                                checked = vpnState.running,
                                onCheckedChange = onToggleDnsVpn,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF063431),
                                    checkedTrackColor = Color(0xFF7BE8D7),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier.testTag("blocked_websites_vpn_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SummaryMetricPill(
                                label = stringResource(R.string.blocked_sites_metric_total),
                                value = "${activeBlockedWebsites.size}",
                                modifier = Modifier.weight(1f)
                            )
                            SummaryMetricPill(
                                label = stringResource(R.string.blocked_sites_metric_custom),
                                value = "$customCount",
                                modifier = Modifier.weight(1f)
                            )
                            SummaryMetricPill(
                                label = stringResource(R.string.blocked_sites_metric_shields),
                                value = "$activeShieldCount/4",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 2. Domain Input Card (Single or Multi-Domain / URL Paste)
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Block,
                            contentDescription = null,
                            tint = StatusDangerRed
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.blocked_sites_input_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (activeChildName != null) {
                                    stringResource(R.string.blocked_sites_input_subtitle_child, activeChildName)
                                } else {
                                    stringResource(R.string.blocked_sites_input_subtitle_local)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        label = { Text(stringResource(R.string.blocked_sites_input_label)) },
                        placeholder = { Text(stringResource(R.string.blocked_sites_input_placeholder)) },
                        isError = hasInputError,
                        minLines = 1,
                        maxLines = 3,
                        trailingIcon = {
                            if (domainInput.isNotEmpty()) {
                                IconButton(
                                    onClick = { domainInput = "" },
                                    modifier = Modifier.testTag("blocked_websites_clear_input")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.dns_clear_input_cd)
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitInput() }),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("blocked_websites_domain_input")
                    )

                    AnimatedVisibility(visible = parsedCandidates.isNotEmpty() || hasInputError) {
                        if (parsedCandidates.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.blocked_sites_ready_preview,
                                            parsedCandidates.size
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        parsedCandidates.forEach { domain ->
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.surface
                                            ) {
                                                Text(
                                                    text = domain,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (hasInputError) {
                            Text(
                                text = stringResource(R.string.blocked_sites_input_error),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Button(
                        onClick = submitInput,
                        enabled = parsedCandidates.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("blocked_websites_add_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (parsedCandidates.size > 1) {
                                stringResource(R.string.blocked_sites_add_multiple_cta, parsedCandidates.size)
                            } else {
                                stringResource(R.string.blocked_sites_add_single_cta)
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (quickSuggestions.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.dns_quick_add),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                quickSuggestions.forEach { domain ->
                                    AssistChip(
                                        onClick = { onAddDomainsBatch(domain) },
                                        label = {
                                            Text(
                                                text = domain,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.Add,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        },
                                        modifier = Modifier.testTag("blocked_websites_quick_$domain")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Built-in Local DNS Category Shields Strip
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.blocked_sites_shields_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.blocked_sites_shields_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LocalDnsShieldToggleChip(
                            label = stringResource(R.string.blocked_sites_source_adult),
                            count = shieldCounts[BlockedWebsiteSource.ADULT_SHIELD] ?: 0,
                            enabled = filterSnapshot.blockAdult,
                            tag = "blocked_sites_shield_adult",
                            onToggle = { onToggleDnsShield("blockAdult", it) }
                        )
                        LocalDnsShieldToggleChip(
                            label = stringResource(R.string.blocked_sites_source_gambling),
                            count = shieldCounts[BlockedWebsiteSource.GAMBLING_SHIELD] ?: 0,
                            enabled = filterSnapshot.blockGambling,
                            tag = "blocked_sites_shield_gambling",
                            onToggle = { onToggleDnsShield("blockGambling", it) }
                        )
                        LocalDnsShieldToggleChip(
                            label = stringResource(R.string.blocked_sites_source_malware),
                            count = shieldCounts[BlockedWebsiteSource.MALWARE_SHIELD] ?: 0,
                            enabled = filterSnapshot.blockMalware,
                            tag = "blocked_sites_shield_malware",
                            onToggle = { onToggleDnsShield("blockMalware", it) }
                        )
                        LocalDnsShieldToggleChip(
                            label = stringResource(R.string.blocked_sites_source_social),
                            count = shieldCounts[BlockedWebsiteSource.SOCIAL_SHIELD] ?: 0,
                            enabled = filterSnapshot.blockSocial,
                            tag = "blocked_sites_shield_social",
                            onToggle = { onToggleDnsShield("blockSocial", it) }
                        )
                    }
                }
            }
        }

        // 4. Unified "Currently Blocked Websites" Header + Search + Source Filters
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
                                text = stringResource(
                                    R.string.blocked_sites_list_title,
                                    activeBlockedWebsites.size
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.blocked_sites_list_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (customCount > 0) {
                            TextButton(
                                onClick = { showClearDialog = true },
                                modifier = Modifier.testTag("blocked_websites_clear_custom")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteSweep,
                                    contentDescription = stringResource(R.string.dns_clear_all_cd),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.blocked_sites_clear_custom))
                            }
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(stringResource(R.string.blocked_sites_search_hint)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.dns_clear_input_cd)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("blocked_websites_search_input")
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = sourceFilter == WebsiteSourceFilter.ALL,
                            onClick = { sourceFilter = WebsiteSourceFilter.ALL },
                            label = {
                                Text("${stringResource(R.string.dns_filter_all)} (${activeBlockedWebsites.size})")
                            },
                            modifier = Modifier.testTag("blocked_sites_filter_all")
                        )
                        FilterChip(
                            selected = sourceFilter == WebsiteSourceFilter.CUSTOM,
                            onClick = { sourceFilter = WebsiteSourceFilter.CUSTOM },
                            label = {
                                Text("${stringResource(R.string.blocked_sites_source_custom)} ($customCount)")
                            },
                            modifier = Modifier.testTag("blocked_sites_filter_custom")
                        )
                        if (filterSnapshot.blockAdult) {
                            FilterChip(
                                selected = sourceFilter == WebsiteSourceFilter.ADULT,
                                onClick = { sourceFilter = WebsiteSourceFilter.ADULT },
                                label = { Text(stringResource(R.string.blocked_sites_source_adult)) },
                                modifier = Modifier.testTag("blocked_sites_filter_adult")
                            )
                        }
                        if (filterSnapshot.blockGambling) {
                            FilterChip(
                                selected = sourceFilter == WebsiteSourceFilter.GAMBLING,
                                onClick = { sourceFilter = WebsiteSourceFilter.GAMBLING },
                                label = { Text(stringResource(R.string.blocked_sites_source_gambling)) },
                                modifier = Modifier.testTag("blocked_sites_filter_gambling")
                            )
                        }
                        if (filterSnapshot.blockMalware) {
                            FilterChip(
                                selected = sourceFilter == WebsiteSourceFilter.MALWARE,
                                onClick = { sourceFilter = WebsiteSourceFilter.MALWARE },
                                label = { Text(stringResource(R.string.blocked_sites_source_malware)) },
                                modifier = Modifier.testTag("blocked_sites_filter_malware")
                            )
                        }
                        if (filterSnapshot.blockSocial) {
                            FilterChip(
                                selected = sourceFilter == WebsiteSourceFilter.SOCIAL,
                                onClick = { sourceFilter = WebsiteSourceFilter.SOCIAL },
                                label = { Text(stringResource(R.string.blocked_sites_source_social)) },
                                modifier = Modifier.testTag("blocked_sites_filter_social")
                            )
                        }
                        if (timeLimitCount > 0) {
                            FilterChip(
                                selected = sourceFilter == WebsiteSourceFilter.TIME_LIMIT,
                                onClick = { sourceFilter = WebsiteSourceFilter.TIME_LIMIT },
                                label = {
                                    Text("${stringResource(R.string.blocked_sites_source_time_limit)} ($timeLimitCount)")
                                },
                                modifier = Modifier.testTag("blocked_sites_filter_limit")
                            )
                        }
                    }
                }
            }
        }

        // 5. Currently Blocked Websites Items (smooth LazyColumn virtualization)
        if (filteredWebsites.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = if (activeBlockedWebsites.isEmpty()) {
                                stringResource(R.string.blocked_sites_empty_all)
                            } else {
                                stringResource(R.string.blocked_sites_empty_filter)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(
                items = filteredWebsites,
                key = { "${it.source.name}_${it.domain}" }
            ) { entry ->
                BlockedWebsiteRow(
                    entry = entry,
                    onRemove = if (entry.removable) {
                        { onRemoveCustomDomain(entry.domain) }
                    } else {
                        null
                    }
                )
            }
        }

        // 6. Live Full Local DNS Website Checker
        item {
            LocalDnsFullCheckCard(
                query = checkQuery,
                onQueryChange = { checkQuery = it },
                evaluation = liveCheckResult,
                onQuickBlockDomain = { domain ->
                    onAddDomainsBatch(domain)
                }
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SummaryMetricPill(
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
            AnimatedContent(
                targetState = value,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "blockedSitesMetric"
            ) { target ->
                Text(
                    text = target,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
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
