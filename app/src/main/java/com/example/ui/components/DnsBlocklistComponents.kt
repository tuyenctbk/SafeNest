package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.example.engine.DomainPatternMatch
import com.example.engine.DomainPatternType
import com.example.engine.InMemoryDomainBlocklist
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusSafeGreen
import com.example.ui.theme.StatusWarnAmber

enum class PatternComposerMode {
    DOMAIN,
    SUBDOMAIN_WILDCARD,
    KEYWORD
}

enum class PatternListFilter {
    ALL,
    DOMAINS,
    WILDCARDS,
    KEYWORDS
}

data class PatternPackPreset(
    val id: String,
    val titleRes: Int,
    val patterns: List<String>
)

val DefaultPatternPacks = listOf(
    PatternPackPreset(
        id = "short_video",
        titleRes = R.string.dns_pack_short_video,
        patterns = listOf("*.tiktok.com", "*.tiktokv.com", "*.musical.ly")
    ),
    PatternPackPreset(
        id = "ads_trackers",
        titleRes = R.string.dns_pack_ads_trackers,
        patterns = listOf("*.doubleclick.net", "*.googlesyndication.com", "*adservice*")
    ),
    PatternPackPreset(
        id = "gambling",
        titleRes = R.string.dns_pack_gambling,
        patterns = listOf("*.bet365.com", "*casino*", "*betting*")
    ),
    PatternPackPreset(
        id = "gaming",
        titleRes = R.string.dns_pack_gaming,
        patterns = listOf("*.roblox.com", "*.epicgames.com")
    )
)

/**
 * Formats user text according to the active PatternComposerMode while preserving
 * explicit wildcard patterns entered directly by power users.
 */
fun formatPatternCandidate(rawInput: String, mode: PatternComposerMode): String {
    val trimmed = rawInput.trim().lowercase()
    if (trimmed.isEmpty()) return ""
    if (trimmed.contains('*')) return trimmed
    return when (mode) {
        PatternComposerMode.DOMAIN -> trimmed
        PatternComposerMode.SUBDOMAIN_WILDCARD -> {
            val cleanedHost = InMemoryDomainBlocklist.normalizeQueryDomain(trimmed)
            if (cleanedHost.isEmpty()) "" else "*.$cleanedHost"
        }
        PatternComposerMode.KEYWORD -> {
            val cleanWord = trimmed.replace("*", "").replace(".", "").trim()
            if (cleanWord.isEmpty()) "" else "*$cleanWord*"
        }
    }
}

@Composable
fun VpnSyncStatusBanner(
    vpnRunning: Boolean,
    inMemoryCount: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (vpnRunning) Color(0xFF0A4F48).copy(alpha = 0.65f) else Color.White.copy(alpha = 0.12f),
        border = BorderStroke(
            width = 1.dp,
            color = if (vpnRunning) Color(0xFF7BE8D7).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.2f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("dns_vpn_sync_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (vpnRunning) Icons.Filled.CheckCircle else Icons.Filled.Sync,
                contentDescription = null,
                tint = if (vpnRunning) Color(0xFF6CF0BE) else Color(0xFFFFD6A5),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (vpnRunning) {
                    stringResource(R.string.dns_sync_live_active, inMemoryCount)
                } else {
                    stringResource(R.string.dns_sync_live_standby, inMemoryCount)
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatternComposerCard(
    domainInput: String,
    onDomainInputChange: (String) -> Unit,
    composerMode: PatternComposerMode,
    onComposerModeChange: (PatternComposerMode) -> Unit,
    activeChildName: String?,
    existingPatterns: List<String>,
    onSubmitPattern: (String) -> Unit,
    onApplyPatternPack: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val candidate = remember(domainInput, composerMode) {
        formatPatternCandidate(domainInput, composerMode)
    }
    val validatedPattern = remember(candidate) {
        if (candidate.isEmpty()) null else InMemoryDomainBlocklist.normalizeAndValidatePattern(candidate)
    }
    val hasInputError = domainInput.trim().isNotEmpty() && validatedPattern == null

    val quickSuggestions = remember(existingPatterns) {
        listOf("*.tiktok.com", "*casino*", "*.doubleclick.net", "roblox.com", "instagram.com")
            .filterNot { existingPatterns.contains(it) }
    }

    val submit: () -> Unit = {
        val valid = validatedPattern
        if (valid != null) {
            onSubmitPattern(valid)
        }
    }

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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dns_pattern_composer_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (activeChildName != null) {
                            stringResource(R.string.dns_add_domain_synced, activeChildName)
                        } else {
                            stringResource(R.string.dns_add_domain_local)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Pattern type mode chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = composerMode == PatternComposerMode.DOMAIN,
                    onClick = { onComposerModeChange(PatternComposerMode.DOMAIN) },
                    label = { Text(stringResource(R.string.dns_pattern_mode_domain)) },
                    modifier = Modifier.testTag("dns_mode_domain")
                )
                FilterChip(
                    selected = composerMode == PatternComposerMode.SUBDOMAIN_WILDCARD,
                    onClick = { onComposerModeChange(PatternComposerMode.SUBDOMAIN_WILDCARD) },
                    label = { Text(stringResource(R.string.dns_pattern_mode_subdomain)) },
                    modifier = Modifier.testTag("dns_mode_subdomain")
                )
                FilterChip(
                    selected = composerMode == PatternComposerMode.KEYWORD,
                    onClick = { onComposerModeChange(PatternComposerMode.KEYWORD) },
                    label = { Text(stringResource(R.string.dns_pattern_mode_keyword)) },
                    modifier = Modifier.testTag("dns_mode_keyword")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = domainInput,
                    onValueChange = onDomainInputChange,
                    label = {
                        Text(
                            text = when (composerMode) {
                                PatternComposerMode.DOMAIN -> stringResource(R.string.dns_pattern_hint_domain)
                                PatternComposerMode.SUBDOMAIN_WILDCARD -> stringResource(R.string.dns_pattern_hint_subdomain)
                                PatternComposerMode.KEYWORD -> stringResource(R.string.dns_pattern_hint_keyword)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    isError = hasInputError,
                    trailingIcon = {
                        if (domainInput.isNotEmpty()) {
                            IconButton(
                                onClick = { onDomainInputChange("") },
                                modifier = Modifier.testTag("dns_clear_domain_input")
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
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dns_domain_input")
                )

                Button(
                    onClick = submit,
                    enabled = validatedPattern != null,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("dns_add_domain_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.dns_block_cd),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.dns_block_button), fontWeight = FontWeight.SemiBold)
                }
            }

            // Live normalized preview or inline validation error
            AnimatedVisibility(visible = validatedPattern != null || hasInputError) {
                if (validatedPattern != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PatternTypeTag(InMemoryDomainBlocklist.classifyPattern(validatedPattern))
                            Text(
                                text = stringResource(R.string.dns_pattern_preview_label, validatedPattern),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else if (hasInputError) {
                    Text(
                        text = stringResource(R.string.dns_pattern_invalid_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // One-Tap Pattern Packs
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.dns_pattern_packs_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DefaultPatternPacks.forEach { pack ->
                        val alreadyFullyApplied = pack.patterns.all { existingPatterns.contains(it) }
                        AssistChip(
                            onClick = { onApplyPatternPack(pack.patterns) },
                            enabled = !alreadyFullyApplied,
                            label = {
                                Text(
                                    text = "${stringResource(pack.titleRes)} (${pack.patterns.size})",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (alreadyFullyApplied) Icons.Filled.CheckCircle else Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            },
                            modifier = Modifier.testTag("dns_pattern_pack_${pack.id}")
                        )
                    }
                }
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
                        quickSuggestions.forEach { suggestion ->
                            AssistChip(
                                onClick = { onSubmitPattern(suggestion) },
                                label = {
                                    Text(
                                        text = suggestion,
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
                                modifier = Modifier.testTag("dns_quick_add_$suggestion")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LivePatternTesterCard(
    testQuery: String,
    onTestQueryChange: (String) -> Unit,
    testMatch: DomainPatternMatch?,
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Science,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = stringResource(R.string.dns_tester_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.dns_tester_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = testQuery,
                onValueChange = onTestQueryChange,
                label = { Text(stringResource(R.string.dns_tester_input_label)) },
                singleLine = true,
                trailingIcon = {
                    if (testQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onTestQueryChange("") },
                            modifier = Modifier.testTag("dns_clear_tester_input")
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
                    .testTag("dns_live_pattern_tester_input")
            )

            AnimatedVisibility(visible = testMatch != null && testQuery.trim().isNotEmpty()) {
                testMatch?.let { result ->
                    val bgColor = if (result.blocked) {
                        StatusDangerRed.copy(alpha = 0.12f)
                    } else {
                        StatusSafeGreen.copy(alpha = 0.12f)
                    }
                    val accentColor = if (result.blocked) StatusDangerRed else StatusSafeGreen

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = bgColor,
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dns_live_pattern_tester_result")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (result.blocked) Icons.Filled.Block else Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = if (result.blocked) {
                                        stringResource(
                                            R.string.dns_tester_result_blocked,
                                            result.matchedPattern.orEmpty()
                                        )
                                    } else {
                                        stringResource(R.string.dns_tester_result_allowed)
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor
                                )
                            }
                            result.patternType?.let { type ->
                                PatternTypeTag(type)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PatternTypeTag(
    type: DomainPatternType,
    modifier: Modifier = Modifier
) {
    val (labelRes, bgColor, textColor) = when (type) {
        DomainPatternType.DOMAIN_SUFFIX -> Triple(
            R.string.dns_pattern_type_domain,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        DomainPatternType.SUBDOMAIN_WILDCARD -> Triple(
            R.string.dns_pattern_type_subdomain,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        DomainPatternType.WILDCARD -> Triple(
            R.string.dns_pattern_type_wildcard,
            StatusWarnAmber.copy(alpha = 0.18f),
            StatusWarnAmber
        )
        DomainPatternType.KEYWORD -> Triple(
            R.string.dns_pattern_type_keyword,
            StatusDangerRed.copy(alpha = 0.14f),
            StatusDangerRed
        )
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor,
        modifier = modifier
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun BlockedPatternRow(
    pattern: String,
    onRemove: () -> Unit
) {
    val patternType = remember(pattern) { InMemoryDomainBlocklist.classifyPattern(pattern) }
    val scopeDescriptionRes = when (patternType) {
        DomainPatternType.DOMAIN_SUFFIX -> R.string.dns_pattern_scope_domain
        DomainPatternType.SUBDOMAIN_WILDCARD -> R.string.dns_pattern_scope_subdomain
        DomainPatternType.WILDCARD -> R.string.dns_pattern_scope_wildcard
        DomainPatternType.KEYWORD -> R.string.dns_pattern_scope_keyword
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dns_blocked_pattern_row_$pattern")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(StatusDangerRed.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = null,
                        tint = StatusDangerRed,
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
                            text = pattern,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        PatternTypeTag(patternType)
                    }
                    Text(
                        text = stringResource(scopeDescriptionRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.testTag("dns_remove_domain_$pattern")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.dns_unblock_cd, pattern),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ClearBlocklistConfirmationDialog(
    patternCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.dns_clear_dialog_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(stringResource(R.string.dns_clear_dialog_body, patternCount))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("dns_confirm_clear_all_button")
            ) {
                Text(stringResource(R.string.dns_clear_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dns_cancel_clear_all_button")
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
