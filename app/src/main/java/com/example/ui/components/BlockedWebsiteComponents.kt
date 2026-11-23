package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.engine.BlockedWebsiteEntry
import com.example.engine.BlockedWebsiteSource
import com.example.engine.DnsEvaluation
import com.example.engine.DnsVerdict
import com.example.engine.DomainPatternType
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusSafeGreen
import com.example.ui.theme.StatusWarnAmber
import java.util.Locale

@Composable
fun BlockedWebsitesEntryCard(
    totalBlockedCount: Int,
    customCount: Int,
    vpnRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .testTag("open_blocked_websites_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(StatusDangerRed.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Language,
                    contentDescription = stringResource(R.string.blocked_sites_screen_title),
                    tint = StatusDangerRed,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.blocked_sites_screen_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (vpnRunning) StatusSafeGreen.copy(alpha = 0.14f) else StatusWarnAmber.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = stringResource(R.string.blocked_sites_count_pill, totalBlockedCount),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (vpnRunning) StatusSafeGreen else StatusWarnAmber,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.blocked_sites_entry_subtitle, customCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.blocked_sites_screen_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun LocalDnsShieldToggleChip(
    label: String,
    count: Int,
    enabled: Boolean,
    tag: String,
    onToggle: (Boolean) -> Unit
) {
    FilterChip(
        selected = enabled,
        onClick = { onToggle(!enabled) },
        label = {
            Text(
                text = "$label ($count)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        leadingIcon = {
            Icon(
                imageVector = if (enabled) Icons.Filled.Shield else Icons.Filled.Block,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier.testTag(tag)
    )
}

@Composable
fun BlockedWebsiteRow(
    entry: BlockedWebsiteEntry,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val (sourceLabelRes, badgeTint) = when (entry.source) {
        BlockedWebsiteSource.CUSTOM -> R.string.blocked_sites_source_custom to StatusDangerRed
        BlockedWebsiteSource.CATEGORY_LIMIT -> R.string.blocked_sites_source_time_limit to StatusWarnAmber
        BlockedWebsiteSource.ADULT_SHIELD -> R.string.blocked_sites_source_adult to Color(0xFFC2385B)
        BlockedWebsiteSource.GAMBLING_SHIELD -> R.string.blocked_sites_source_gambling to Color(0xFFB36B00)
        BlockedWebsiteSource.MALWARE_SHIELD -> R.string.blocked_sites_source_malware to Color(0xFF8B2E2E)
        BlockedWebsiteSource.SOCIAL_SHIELD -> R.string.blocked_sites_source_social to Color(0xFF4C5FD5)
    }

    val leadingIcon = when (entry.source) {
        BlockedWebsiteSource.CUSTOM -> Icons.Filled.Block
        BlockedWebsiteSource.CATEGORY_LIMIT -> Icons.Filled.Timer
        else -> Icons.Filled.Shield
    }

    val detailText = if (entry.source == BlockedWebsiteSource.CUSTOM) {
        val scopeRes = when (entry.patternType) {
            DomainPatternType.DOMAIN_SUFFIX -> R.string.dns_pattern_scope_domain
            DomainPatternType.SUBDOMAIN_WILDCARD -> R.string.dns_pattern_scope_subdomain
            DomainPatternType.WILDCARD -> R.string.dns_pattern_scope_wildcard
            DomainPatternType.KEYWORD -> R.string.dns_pattern_scope_keyword
        }
        stringResource(scopeRes)
    } else if (entry.source == BlockedWebsiteSource.CATEGORY_LIMIT) {
        stringResource(R.string.blocked_sites_auto_limit_desc)
    } else {
        stringResource(R.string.blocked_sites_builtin_shield_desc)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("blocked_website_row_${entry.source.name.lowercase()}_${entry.domain}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(badgeTint.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = badgeTint,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = entry.domain,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeTint.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = stringResource(sourceLabelRes),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = badgeTint,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (entry.removable && onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.testTag("blocked_website_remove_${entry.domain}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.dns_unblock_cd, entry.domain),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.blocked_sites_builtin_lock_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LocalDnsFullCheckCard(
    query: String,
    onQueryChange: (String) -> Unit,
    evaluation: DnsEvaluation?,
    onQuickBlockDomain: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
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
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.blocked_sites_checker_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.blocked_sites_checker_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.blocked_sites_checker_hint)) },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.testTag("blocked_sites_checker_clear")
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
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("blocked_sites_checker_input")
            )

            AnimatedVisibility(visible = evaluation != null && query.trim().isNotEmpty()) {
                evaluation?.let { eval ->
                    val accent = when {
                        eval.blocked -> StatusDangerRed
                        eval.verdict == DnsVerdict.SAFESEARCH_ENFORCED -> MaterialTheme.colorScheme.primary
                        else -> StatusSafeGreen
                    }
                    val verdictTitle = when (eval.verdict) {
                        DnsVerdict.BLOCKED_CUSTOM -> stringResource(
                            R.string.blocked_sites_verdict_custom,
                            eval.matchedPattern ?: eval.domain
                        )
                        DnsVerdict.BLOCKED_ADULT -> stringResource(R.string.blocked_sites_verdict_adult)
                        DnsVerdict.BLOCKED_GAMBLING -> stringResource(R.string.blocked_sites_verdict_gambling)
                        DnsVerdict.BLOCKED_MALWARE -> stringResource(R.string.blocked_sites_verdict_malware)
                        DnsVerdict.BLOCKED_SOCIAL -> stringResource(R.string.blocked_sites_verdict_social)
                        DnsVerdict.BLOCKED_CATEGORY_LIMIT -> stringResource(R.string.blocked_sites_verdict_time_limit)
                        DnsVerdict.SAFESEARCH_ENFORCED -> stringResource(R.string.blocked_sites_verdict_safesearch)
                        DnsVerdict.ALLOWED -> stringResource(R.string.blocked_sites_verdict_allowed)
                    }
                    val latencyLabel = String.format(Locale.US, "%.2f", eval.latencyMs)

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = accent.copy(alpha = 0.11f),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.32f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("blocked_sites_checker_result")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        eval.blocked -> Icons.Filled.Block
                                        eval.verdict == DnsVerdict.SAFESEARCH_ENFORCED -> Icons.Filled.VerifiedUser
                                        else -> Icons.Filled.CheckCircle
                                    },
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = verdictTitle,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = accent
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.blocked_sites_verdict_meta,
                                            eval.domain,
                                            latencyLabel
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (!eval.blocked && eval.domain.contains('.')) {
                                FilledTonalButton(
                                    onClick = { onQuickBlockDomain(eval.domain) },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("blocked_sites_checker_block_now")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.blocked_sites_block_now_cta, eval.domain),
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
}
