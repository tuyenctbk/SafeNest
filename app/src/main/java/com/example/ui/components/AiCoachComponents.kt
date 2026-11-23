package com.example.ui.components

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AiCoachPreferences
import com.example.data.AiEngineMode
import com.example.data.ByokGeminiModel
import com.example.engine.AiActionType
import com.example.engine.AiFallbackReason
import com.example.engine.AiRecommendedAction
import com.example.engine.AiResponseOrigin
import com.example.engine.ByokTestResult
import com.example.engine.ByokTestStatus
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusSafeGreen
import com.example.ui.theme.StatusWarnAmber
import kotlin.math.roundToInt

@Composable
fun AiCoachEntryCard(
    prefs: AiCoachPreferences,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCloud = prefs.isCloudActive
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (isCloud) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .testTag("ai_coach_entry_card")
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
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = stringResource(R.string.ai_coach_title),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
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
                        text = stringResource(R.string.ai_coach_title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isCloud) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else StatusSafeGreen.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = stringResource(
                                when {
                                    isCloud && prefs.shouldAutoUseEcoLite -> R.string.ai_coach_badge_eco_short
                                    isCloud -> R.string.ai_coach_badge_byok_short
                                    else -> R.string.ai_coach_badge_free_short
                                }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCloud) MaterialTheme.colorScheme.primary else StatusSafeGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.ai_coach_entry_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.ai_coach_open_cd),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun AiEngineStatusBanner(
    prefs: AiCoachPreferences,
    onOpenSettings: () -> Unit,
    onEnableEcoLite: () -> Unit,
    onClaimGraceCredits: () -> Unit,
    onSwitchToLocalFree: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCloud = prefs.isCloudActive
    val (resetHours, resetMins) = remember(prefs.usageDayKey, prefs.cloudRequestsToday) {
        AiCoachPreferences.timeUntilMidnightReset()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isCloud) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                }
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = when {
                                isCloud && prefs.shouldAutoUseEcoLite -> Icons.Filled.Eco
                                isCloud -> Icons.Filled.Cloud
                                else -> Icons.Filled.Shield
                            },
                            contentDescription = null,
                            tint = if (isCloud) MaterialTheme.colorScheme.primary else StatusSafeGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = if (isCloud) {
                                    stringResource(
                                        R.string.ai_coach_mode_byok_active,
                                        stringResource(prefs.resolvedCloudModel().labelRes)
                                    )
                                } else {
                                    stringResource(R.string.ai_coach_mode_on_device_active)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.ai_coach_privacy_pii_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = onOpenSettings,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("ai_coach_open_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.ai_coach_plan_button),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                if (prefs.mode == AiEngineMode.BYOK_CLOUD_WITH_FALLBACK) {
                    val progress = (prefs.cloudRequestsToday.toFloat() / prefs.effectiveDailyLimit.coerceAtLeast(1))
                        .coerceIn(0f, 1f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                R.string.ai_coach_daily_budget_counter,
                                prefs.cloudRequestsToday,
                                prefs.effectiveDailyLimit
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.ai_coach_reset_countdown_short, resetHours, resetMins),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(50)),
                        color = when {
                            prefs.isQuotaExhausted -> StatusDangerRed
                            prefs.isSoftNudgeActive -> StatusWarnAmber
                            else -> MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                }
            }
        }

        // Stage 1: 80% Soft Nudge Banner (with 1-tap Eco Lite stretch)
        if (prefs.isSoftNudgeActive && !prefs.ecoLiteMode) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = StatusWarnAmber.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, StatusWarnAmber.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_soft_nudge_banner")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Eco,
                        contentDescription = null,
                        tint = StatusWarnAmber,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.ai_coach_soft_nudge_title,
                                prefs.usagePercent,
                                prefs.remainingCloudToday
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.ai_coach_soft_nudge_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = onEnableEcoLite,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("ai_nudge_enable_eco_button")
                    ) {
                        Text(
                            text = stringResource(R.string.ai_coach_enable_eco_short),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // Stage 2: Smart Quota Recovery Card (100% cap reached -> Grace Credits + Eco Lite + On-Device + Countdown)
        if (prefs.isQuotaExhausted) {
            AiSmartQuotaRecoveryCard(
                prefs = prefs,
                resetHours = resetHours,
                resetMins = resetMins,
                onClaimGraceCredits = onClaimGraceCredits,
                onEnableEcoLite = onEnableEcoLite,
                onSwitchToLocalFree = onSwitchToLocalFree
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSmartQuotaRecoveryCard(
    prefs: AiCoachPreferences,
    resetHours: Int,
    resetMins: Int,
    onClaimGraceCredits: () -> Unit,
    onEnableEcoLite: () -> Unit,
    onSwitchToLocalFree: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.2.dp, StatusWarnAmber.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_quota_recovery_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_coach_recovery_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // Personal Today's AI Value Receipt
            if (prefs.totalValueActionsToday > 0) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            R.string.ai_coach_value_receipt,
                            prefs.chatUsedToday,
                            prefs.riskChecksUsedToday,
                            prefs.briefsUsedToday
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Suggestion 1: Claim +3 Free Grace Credits (once/day)
                if (!prefs.graceClaimedToday) {
                    Button(
                        onClick = onClaimGraceCredits,
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("ai_claim_grace_credits_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CardGiftcard,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.ai_coach_claim_grace_cta),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // Suggestion 2: Enable Eco Lite AI
                if (!prefs.ecoLiteMode) {
                    FilledTonalButton(
                        onClick = onEnableEcoLite,
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("ai_recovery_eco_lite_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Eco,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.ai_coach_recovery_eco_cta),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // Suggestion 3: Zero-Quota Local Alternative
                OutlinedButton(
                    onClick = onSwitchToLocalFree,
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("ai_recovery_local_free_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.ai_coach_recovery_local_cta),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // Suggestion 4: Visible Reset Countdown
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = stringResource(R.string.ai_coach_reset_countdown_full, resetHours, resetMins),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AiOriginBadge(
    origin: AiResponseOrigin,
    fallbackReason: AiFallbackReason
) {
    val (label, tint, icon) = when (origin) {
        AiResponseOrigin.ON_DEVICE_FREE ->
            Triple(stringResource(R.string.ai_coach_origin_on_device), StatusSafeGreen, Icons.Filled.Shield)
        AiResponseOrigin.CLOUD_BYOK_STANDARD ->
            Triple(stringResource(R.string.ai_coach_origin_cloud_byok), MaterialTheme.colorScheme.primary, Icons.Filled.Cloud)
        AiResponseOrigin.CLOUD_BYOK_ECO_LITE ->
            Triple(stringResource(R.string.ai_coach_origin_eco_lite), MaterialTheme.colorScheme.secondary, Icons.Filled.Eco)
        AiResponseOrigin.CACHED_ZERO_TOKEN ->
            Triple(stringResource(R.string.ai_coach_origin_cached), StatusSafeGreen, Icons.Filled.Bolt)
        AiResponseOrigin.ON_DEVICE_FALLBACK -> {
            val suffix = when (fallbackReason) {
                AiFallbackReason.DAILY_QUOTA_REACHED -> stringResource(R.string.ai_coach_fallback_quota)
                AiFallbackReason.NO_KEY -> stringResource(R.string.ai_coach_fallback_no_key)
                AiFallbackReason.AUTH_OR_RATE_LIMIT -> stringResource(R.string.ai_coach_fallback_auth)
                AiFallbackReason.TIMEOUT -> stringResource(R.string.ai_coach_fallback_timeout)
                else -> stringResource(R.string.ai_coach_fallback_offline)
            }
            Triple(stringResource(R.string.ai_coach_origin_fallback, suffix), StatusWarnAmber, Icons.Filled.CloudOff)
        }
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = tint.copy(alpha = 0.13f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiRecommendedActionsRow(
    actions: List<AiRecommendedAction>,
    onActionClick: (AiRecommendedAction) -> Unit
) {
    if (actions.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        actions.forEach { action ->
            val (icon, label) = when (action.type) {
                AiActionType.BLOCK_DOMAIN ->
                    Icons.Filled.Block to stringResource(R.string.ai_coach_action_block_domain, action.domain)
                AiActionType.APPLY_SCHOOL_NIGHT_PRESET ->
                    Icons.Filled.NightlightRound to stringResource(R.string.ai_coach_action_school_night)
                AiActionType.OPEN_CATEGORY_LIMITS ->
                    Icons.Filled.Tune to stringResource(R.string.ai_coach_action_open_limits)
                AiActionType.OPEN_BLOCKED_WEBSITES ->
                    Icons.Filled.Shield to stringResource(R.string.ai_coach_action_open_blocked_sites)
            }
            AssistChip(
                onClick = { onActionClick(action) },
                leadingIcon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.testTag("ai_action_${action.type.name.lowercase()}")
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiByokSettingsDialog(
    prefs: AiCoachPreferences,
    isTestingKey: Boolean,
    keyTestResult: ByokTestResult?,
    onDismiss: () -> Unit,
    onTestKey: (String, ByokGeminiModel) -> Unit,
    onClearKey: () -> Unit,
    onSave: (AiEngineMode, String, ByokGeminiModel, Int, Boolean) -> Unit
) {
    var selectedMode by remember(prefs) { mutableStateOf(prefs.mode) }
    var apiKeyInput by remember(prefs) { mutableStateOf(prefs.byokApiKey) }
    var showKey by remember { mutableStateOf(false) }
    var selectedModel by remember(prefs) { mutableStateOf(prefs.model) }
    var ecoLiteEnabled by remember(prefs) { mutableStateOf(prefs.ecoLiteMode) }
    var dailyLimitSlider by remember(prefs) { mutableFloatStateOf(prefs.dailyCloudLimit.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.ai_coach_settings_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.ai_coach_settings_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Value Receipt Summary inside settings
                if (prefs.totalValueActionsToday > 0) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(
                                R.string.ai_coach_value_receipt,
                                prefs.chatUsedToday,
                                prefs.riskChecksUsedToday,
                                prefs.briefsUsedToday
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // Mode selector
                Text(
                    text = stringResource(R.string.ai_coach_mode_section_label),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedMode == AiEngineMode.ON_DEVICE_ONLY,
                        onClick = { selectedMode = AiEngineMode.ON_DEVICE_ONLY },
                        label = { Text(stringResource(R.string.ai_coach_mode_free_option)) },
                        modifier = Modifier.testTag("ai_mode_on_device_chip")
                    )
                    FilterChip(
                        selected = selectedMode == AiEngineMode.BYOK_CLOUD_WITH_FALLBACK,
                        onClick = { selectedMode = AiEngineMode.BYOK_CLOUD_WITH_FALLBACK },
                        label = { Text(stringResource(R.string.ai_coach_mode_byok_option)) },
                        modifier = Modifier.testTag("ai_mode_byok_chip")
                    )
                }

                if (selectedMode == AiEngineMode.BYOK_CLOUD_WITH_FALLBACK) {
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text(stringResource(R.string.ai_coach_api_key_label)) },
                        placeholder = { Text(stringResource(R.string.ai_coach_api_key_placeholder)) },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(R.string.ai_coach_toggle_key_cd)
                                )
                            }
                        },
                        supportingText = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(stringResource(R.string.ai_coach_key_local_only_hint))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_byok_key_input")
                    )

                    // Model tier selector
                    Text(
                        text = stringResource(R.string.ai_coach_model_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ByokGeminiModel.entries.forEach { model ->
                            FilterChip(
                                selected = selectedModel == model,
                                onClick = { selectedModel = model },
                                label = { Text(stringResource(model.labelRes)) },
                                modifier = Modifier.testTag("ai_model_${model.name.lowercase()}")
                            )
                        }
                    }

                    // Eco Lite Mode switch
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.ai_coach_eco_lite_label),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = stringResource(R.string.ai_coach_eco_lite_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = ecoLiteEnabled,
                                onCheckedChange = { ecoLiteEnabled = it },
                                modifier = Modifier.testTag("ai_eco_lite_switch")
                            )
                        }
                    }

                    // Daily budget cap slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.ai_coach_daily_cap_label),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = stringResource(
                                R.string.ai_coach_daily_cap_value,
                                dailyLimitSlider.roundToInt()
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = dailyLimitSlider,
                        onValueChange = { dailyLimitSlider = it },
                        valueRange = AiCoachPreferences.MIN_DAILY_CLOUD_LIMIT.toFloat()..AiCoachPreferences.MAX_DAILY_CLOUD_LIMIT.toFloat(),
                        steps = 8,
                        modifier = Modifier.testTag("ai_daily_cap_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onTestKey(apiKeyInput, selectedModel) },
                            enabled = !isTestingKey && apiKeyInput.trim().isNotEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("ai_test_key_button")
                        ) {
                            if (isTestingKey) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.ai_coach_test_key_button))
                            }
                        }
                        if (prefs.byokApiKey.isNotBlank() || apiKeyInput.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    apiKeyInput = ""
                                    selectedMode = AiEngineMode.ON_DEVICE_ONLY
                                    onClearKey()
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("ai_clear_key_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteOutline,
                                    contentDescription = stringResource(R.string.ai_coach_remove_key_cd),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (keyTestResult != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (keyTestResult.success) {
                                StatusSafeGreen.copy(alpha = 0.14f)
                            } else {
                                StatusDangerRed.copy(alpha = 0.12f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (keyTestResult.success) Icons.Filled.CheckCircle else Icons.Filled.CloudOff,
                                    contentDescription = null,
                                    tint = if (keyTestResult.success) StatusSafeGreen else StatusDangerRed,
                                    modifier = Modifier.size(18.dp)
                                )
                                val localizedTestMessage = when (keyTestResult.status) {
                                    ByokTestStatus.SUCCESS -> stringResource(
                                        R.string.ai_test_key_success,
                                        stringResource((keyTestResult.testedModel ?: ByokGeminiModel.PRIMARY_STANDARD).labelRes)
                                    )
                                    ByokTestStatus.KEY_TOO_SHORT -> stringResource(R.string.ai_test_key_too_short)
                                    ByokTestStatus.AUTH_OR_RATE_LIMIT -> stringResource(
                                        R.string.ai_test_key_auth_or_quota,
                                        keyTestResult.detail
                                    )
                                    ByokTestStatus.TIMEOUT -> stringResource(R.string.ai_test_key_timeout)
                                    ByokTestStatus.NETWORK_ERROR -> stringResource(
                                        R.string.ai_test_key_network_error,
                                        keyTestResult.detail
                                    )
                                    ByokTestStatus.TIMEOUT_LOCAL_FALLBACK -> stringResource(R.string.ai_test_key_timeout_local)
                                }
                                Text(
                                    text = localizedTestMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("ai_settings_cancel_button")
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        selectedMode,
                        apiKeyInput,
                        selectedModel,
                        dailyLimitSlider.roundToInt(),
                        ecoLiteEnabled
                    )
                    onDismiss()
                },
                modifier = Modifier.testTag("ai_settings_save_button")
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    )
}
