package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.DnsVerdict
import com.example.engine.DomainPatternMatch
import com.example.engine.DomainPatternType
import com.example.engine.InMemoryDomainBlocklist
import com.example.service.DnsLogEntry
import com.example.service.VpnRuntimeState
import com.example.ui.components.BlockedPatternRow
import com.example.ui.components.BlockedWebsitesEntryCard
import com.example.ui.components.ClearBlocklistConfirmationDialog
import com.example.ui.components.LivePatternTesterCard
import com.example.ui.components.PatternComposerCard
import com.example.ui.components.PatternComposerMode
import com.example.ui.components.PatternListFilter
import com.example.ui.components.VpnSyncStatusBanner
import com.example.ui.theme.HeroEmeraldEnd
import com.example.ui.theme.HeroEmeraldStart
import com.example.ui.theme.HeroSlateEnd
import com.example.ui.theme.HeroSlateStart
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusSafeGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DnsFilterScreen(
    vpnState: VpnRuntimeState,
    customBlockedDomains: List<String>,
    totalBlockedWebsitesCount: Int = customBlockedDomains.size,
    activeChildName: String?,
    onOpenBlockedWebsites: () -> Unit = {},
    onToggleDnsVpn: (Boolean) -> Unit,
    onAddDomain: (String) -> Boolean,
    onAddPatternPack: (List<String>) -> Unit,
    onRemoveDomain: (String) -> Unit,
    onClearAllDomains: () -> Unit,
    onTestDomainPattern: (String) -> DomainPatternMatch
) {
    var domainInput by remember { mutableStateOf("") }
    var composerMode by remember { mutableStateOf(PatternComposerMode.DOMAIN) }
    var searchFilter by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(PatternListFilter.ALL) }
    var testQuery by remember { mutableStateOf("") }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    // Prefer live in-memory snapshot from running VpnService when populated, keeping UI truthful
    val effectivePatterns = remember(vpnState.activeInMemoryPatterns, customBlockedDomains) {
        if (vpnState.activeInMemoryPatterns.isNotEmpty() || customBlockedDomains.isEmpty()) {
            vpnState.activeInMemoryPatterns.ifEmpty { customBlockedDomains }
        } else {
            customBlockedDomains
        }
    }

    val liveTestMatch = remember(testQuery, effectivePatterns, vpnState.lastBlocklistSyncMs) {
        val cleaned = testQuery.trim()
        if (cleaned.isEmpty()) null else onTestDomainPattern(cleaned)
    }

    val filteredPatterns = remember(effectivePatterns, searchFilter, activeFilter) {
        val query = searchFilter.trim().lowercase()
        effectivePatterns.filter { pattern ->
            val matchesQuery = query.isEmpty() || pattern.contains(query)
            val type = InMemoryDomainBlocklist.classifyPattern(pattern)
            val matchesType = when (activeFilter) {
                PatternListFilter.ALL -> true
                PatternListFilter.DOMAINS -> type == DomainPatternType.DOMAIN_SUFFIX
                PatternListFilter.WILDCARDS ->
                    type == DomainPatternType.SUBDOMAIN_WILDCARD || type == DomainPatternType.WILDCARD
                PatternListFilter.KEYWORDS -> type == DomainPatternType.KEYWORD
            }
            matchesQuery && matchesType
        }
    }

    val heroStartColor by animateColorAsState(
        targetValue = if (vpnState.running) HeroEmeraldStart else HeroSlateStart,
        animationSpec = spring(),
        label = "dnsHeroStartColor"
    )
    val heroEndColor by animateColorAsState(
        targetValue = if (vpnState.running) HeroEmeraldEnd else HeroSlateEnd,
        animationSpec = spring(),
        label = "dnsHeroEndColor"
    )
    val livePulseTransition = rememberInfiniteTransition(label = "dnsLivePulse")
    val liveDotAlpha by livePulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveDotAlpha"
    )

    if (showClearConfirmDialog) {
        ClearBlocklistConfirmationDialog(
            patternCount = effectivePatterns.size,
            onConfirm = {
                showClearConfirmDialog = false
                onClearAllDomains()
            },
            onDismiss = { showClearConfirmDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Live Local DNS Filtering Service Toggle Hero Card + VpnService Sync Indicator
        item {
            Card(
                shape = RoundedCornerShape(26.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(listOf(heroStartColor, heroEndColor)))
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
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Shield,
                                        contentDescription = stringResource(R.string.dns_status_cd),
                                        tint = if (vpnState.running) Color(0xFF7BE8D7) else Color.White.copy(alpha = 0.75f),
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(9.dp)
                                                .alpha(if (vpnState.running) liveDotAlpha else 1f)
                                                .clip(CircleShape)
                                                .background(if (vpnState.running) Color(0xFF6CF0BE) else Color(0xFFFFB68B))
                                        )
                                        Text(
                                            text = stringResource(
                                                if (vpnState.running) R.string.dns_proxy_active else R.string.dns_proxy_paused
                                            ),
                                            style = MaterialTheme.typography.titleLarge,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = stringResource(
                                            if (vpnState.running) R.string.dns_proxy_active_desc else R.string.dns_proxy_paused_desc
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.84f)
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
                                modifier = Modifier.testTag("dns_service_master_switch")
                            )
                        }

                        // Real-time counters strip
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            HeroMetricPill(
                                modifier = Modifier.weight(1f),
                                label = stringResource(R.string.dns_metric_inspected),
                                value = "${vpnState.queriesInspected}"
                            )
                            HeroMetricPill(
                                modifier = Modifier.weight(1f),
                                label = stringResource(R.string.dns_metric_blocked),
                                value = "${vpnState.queriesBlocked}"
                            )
                            HeroMetricPill(
                                modifier = Modifier.weight(1f),
                                label = stringResource(R.string.dns_metric_custom_rules),
                                value = "${effectivePatterns.size}"
                            )
                        }

                        // Live VpnService in-memory sync confirmation banner
                        VpnSyncStatusBanner(
                            vpnRunning = vpnState.running,
                            inMemoryCount = effectivePatterns.size
                        )
                    }
                }
            }
        }

        // 1b. Full Blocked Websites Directory Entry Card (Custom + Built-in DNS Shields)
        item {
            BlockedWebsitesEntryCard(
                totalBlockedCount = totalBlockedWebsitesCount,
                customCount = effectivePatterns.size,
                vpnRunning = vpnState.running,
                onClick = onOpenBlockedWebsites
            )
        }

        // 2. Guided Domain Pattern Composer (Domain / *.Subdomains / *Keyword* + Preset Packs)
        item {
            PatternComposerCard(
                domainInput = domainInput,
                onDomainInputChange = { domainInput = it },
                composerMode = composerMode,
                onComposerModeChange = { composerMode = it },
                activeChildName = activeChildName,
                existingPatterns = effectivePatterns,
                onSubmitPattern = { pattern ->
                    if (onAddDomain(pattern)) {
                        domainInput = ""
                    }
                },
                onApplyPatternPack = onAddPatternPack
            )
        }

        // 3. In-Memory Domain Pattern Blocklist Manager Card
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Block,
                                contentDescription = null,
                                tint = StatusDangerRed
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.dns_blocklist_title, effectivePatterns.size),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.dns_blocklist_capacity, effectivePatterns.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (effectivePatterns.isNotEmpty()) {
                            TextButton(
                                onClick = { showClearConfirmDialog = true },
                                modifier = Modifier.testTag("dns_clear_blocklist_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteSweep,
                                    contentDescription = stringResource(R.string.dns_clear_all_cd),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.dns_clear_all))
                            }
                        }
                    }

                    if (effectivePatterns.isNotEmpty()) {
                        // Filter by pattern type
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = activeFilter == PatternListFilter.ALL,
                                onClick = { activeFilter = PatternListFilter.ALL },
                                label = { Text(stringResource(R.string.dns_filter_all)) },
                                modifier = Modifier.testTag("dns_filter_all")
                            )
                            FilterChip(
                                selected = activeFilter == PatternListFilter.DOMAINS,
                                onClick = { activeFilter = PatternListFilter.DOMAINS },
                                label = { Text(stringResource(R.string.dns_filter_domains)) },
                                modifier = Modifier.testTag("dns_filter_domains")
                            )
                            FilterChip(
                                selected = activeFilter == PatternListFilter.WILDCARDS,
                                onClick = { activeFilter = PatternListFilter.WILDCARDS },
                                label = { Text(stringResource(R.string.dns_filter_wildcards)) },
                                modifier = Modifier.testTag("dns_filter_wildcards")
                            )
                            FilterChip(
                                selected = activeFilter == PatternListFilter.KEYWORDS,
                                onClick = { activeFilter = PatternListFilter.KEYWORDS },
                                label = { Text(stringResource(R.string.dns_filter_keywords)) },
                                modifier = Modifier.testTag("dns_filter_keywords")
                            )
                        }
                    }

                    if (effectivePatterns.size >= 3) {
                        OutlinedTextField(
                            value = searchFilter,
                            onValueChange = { searchFilter = it },
                            label = { Text(stringResource(R.string.dns_filter_blocklist_label)) },
                            singleLine = true,
                            trailingIcon = {
                                if (searchFilter.isNotEmpty()) {
                                    IconButton(onClick = { searchFilter = "" }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.dns_clear_input_cd)
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dns_search_blocklist_input")
                        )
                    }

                    if (effectivePatterns.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Public,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.dns_empty_blocklist),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (filteredPatterns.isEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.dns_no_matching_domains,
                                searchFilter.ifBlank { activeFilter.name.lowercase() }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            filteredPatterns.forEach { pattern ->
                                BlockedPatternRow(
                                    pattern = pattern,
                                    onRemove = { onRemoveDomain(pattern) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Live Pattern Tester against Running VpnService
        item {
            LivePatternTesterCard(
                testQuery = testQuery,
                onTestQueryChange = { testQuery = it },
                testMatch = liveTestMatch
            )
        }

        // 5. Real-Time Intercepted DNS Queries Feed (Port 53)
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
                            imageVector = Icons.Filled.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.dns_live_feed_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.dns_live_feed_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (vpnState.recentLogs.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(
                                    if (vpnState.running) R.string.dns_live_feed_listening else R.string.dns_live_feed_off
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            vpnState.recentLogs.take(15).forEachIndexed { index, entry ->
                                val alreadyBlocked = effectivePatterns.contains(entry.domain) ||
                                    effectivePatterns.contains("*.${entry.domain}")
                                LiveDnsLogRow(
                                    entry = entry,
                                    alreadyCustomBlocked = alreadyBlocked,
                                    onQuickBlockWildcard = { onAddDomain("*.${entry.domain}") }
                                )
                                if (index < vpnState.recentLogs.take(15).lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun HeroMetricPill(
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
                label = "heroMetricValue"
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
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LiveDnsLogRow(
    entry: DnsLogEntry,
    alreadyCustomBlocked: Boolean,
    onQuickBlockWildcard: () -> Unit
) {
    val isBlocked = entry.verdict != DnsVerdict.ALLOWED && entry.verdict != DnsVerdict.SAFESEARCH_ENFORCED
    val timeText = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMs))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (isBlocked) Icons.Filled.Block else Icons.Filled.CheckCircle,
                contentDescription = stringResource(
                    if (isBlocked) R.string.dns_status_blocked else R.string.dns_status_allowed
                ),
                tint = if (isBlocked) StatusDangerRed else StatusSafeGreen,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.domain,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        isBlocked && !entry.matchedPattern.isNullOrBlank() ->
                            stringResource(R.string.dns_log_matched_pattern, timeText, entry.matchedPattern)
                        isBlocked -> stringResource(R.string.dns_log_sinkholed, timeText)
                        else -> stringResource(R.string.dns_log_forwarded, timeText)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isBlocked) StatusDangerRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!isBlocked && !alreadyCustomBlocked) {
            TextButton(
                onClick = onQuickBlockWildcard,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.testTag("dns_log_block_${entry.domain}")
            ) {
                Text(
                    text = stringResource(R.string.dns_block_wildcard_button),
                    style = MaterialTheme.typography.labelMedium,
                    color = StatusDangerRed,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
