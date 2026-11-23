package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.analytics.AppUpdateInfo
import com.example.analytics.EngagementSnapshot
import com.example.analytics.SuppressionReason
import com.example.analytics.TrackedAppEvent
import com.example.data.AiCoachPreferences
import com.example.data.ChildProfile
import com.example.engine.DnsEvaluation
import com.example.engine.DnsVerdict
import com.example.engine.SafetyClassificationResult
import com.example.service.DeviceHealthStatus
import com.example.service.VpnRuntimeState
import com.example.ui.components.AiCoachEntryCard
import com.example.ui.components.EngagementTelemetryCard
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusSafeGreen
import com.example.ui.theme.StatusWarnAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShieldLabScreen(
    activeChild: ChildProfile?,
    hasParentPin: Boolean,
    vpnState: VpnRuntimeState,
    deviceHealth: DeviceHealthStatus,
    labAiResult: SafetyClassificationResult?,
    labDnsResult: DnsEvaluation?,
    engagementSnapshot: EngagementSnapshot,
    availableUpdate: AppUpdateInfo,
    recentAppEvents: List<TrackedAppEvent>,
    lastPromptReason: SuppressionReason,
    aiCoachPrefs: AiCoachPreferences = AiCoachPreferences(),
    onOpenAiCoach: () -> Unit = {},
    onToggleVpnRequest: (Boolean) -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onRunAiCheck: (String) -> Unit,
    onLogAiAlert: (ChildProfile, SafetyClassificationResult) -> Unit,
    onRunDnsCheck: (String) -> Unit,
    onOpenPinSetup: () -> Unit,
    onRequestChildMode: () -> Unit,
    onToggleAutoPrompts: (Boolean) -> Unit,
    onClearEventHistory: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onRateApp: () -> Unit,
    onShareApp: () -> Unit,
    onSignOut: () -> Unit
) {
    var aiSampleInput by remember { mutableStateOf("") }
    var dnsSampleInput by remember { mutableStateOf("") }

    val quickAiSamples = listOf(
        R.string.ai_sample_en_bully to "Nobody likes you @minh, you are a worthless loser!",
        R.string.ai_sample_vn_slang to "Đồ ngu vcl, không ai chơi với mày đâu biến đi!",
        R.string.ai_sample_grooming to "Don't tell your parents, keep this secret and send me a pic",
        R.string.ai_sample_vn_scam to "Chơi tài xỉu nổ hũ nhận quà gửi mã OTP 482910 ngay",
        R.string.ai_sample_safe to "Hey! Want to study math homework together at 5pm?"
    )

    val quickDnsSamples = listOf(
        "bet365.com",
        "adult-test.example",
        "google.com",
        "khanacademy.org"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 0. Hybrid On-Device + BYOK Parent AI Safety Coach Entry Card
        item {
            AiCoachEntryCard(
                prefs = aiCoachPrefs,
                onClick = onOpenAiCoach
            )
        }

        // 1. Live Pure-Kotlin Local DNS VPN Shield Card
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(
                    1.dp,
                    if (vpnState.running) StatusSafeGreen.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
                ),
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (vpnState.running) StatusSafeGreen.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Dns,
                                    contentDescription = stringResource(R.string.shield_vpn_title),
                                    tint = if (vpnState.running) StatusSafeGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column {
                                Text(stringResource(R.string.shield_vpn_title), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = stringResource(
                                        if (vpnState.running) R.string.shield_vpn_active_sub else R.string.shield_vpn_stopped_sub
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Button(
                            onClick = { onToggleVpnRequest(!vpnState.running) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (vpnState.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("toggle_local_dns_vpn_button")
                        ) {
                            Icon(
                                imageVector = if (vpnState.running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(if (vpnState.running) R.string.action_stop else R.string.action_start))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MiniCounter(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.shield_dns_inspected),
                            value = "${vpnState.queriesInspected}"
                        )
                        MiniCounter(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.shield_blocked_locally),
                            value = "${vpnState.queriesBlocked}",
                            accent = if (vpnState.queriesBlocked > 0) StatusDangerRed else MaterialTheme.colorScheme.primary
                        )
                    }

                    if (vpnState.recentLogs.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.shield_live_activity),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                vpnState.recentLogs.take(4).forEach { entry ->
                                    val blocked = entry.verdict != DnsVerdict.ALLOWED &&
                                        entry.verdict != DnsVerdict.SAFESEARCH_ENFORCED
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = entry.domain,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = if (blocked) "0.0.0.0" else stringResource(R.string.shield_status_ok),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (blocked) StatusDangerRed else StatusSafeGreen
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Interactive 3-Stage On-Device Edge AI Lab
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Psychology,
                            contentDescription = stringResource(R.string.shield_ai_lab_title),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(stringResource(R.string.shield_ai_lab_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(R.string.shield_ai_lab_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickAiSamples.forEach { (labelRes, sample) ->
                            AssistChip(
                                onClick = {
                                    aiSampleInput = sample
                                    onRunAiCheck(sample)
                                },
                                label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = aiSampleInput,
                        onValueChange = { aiSampleInput = it },
                        label = { Text(stringResource(R.string.shield_ai_input_label)) },
                        placeholder = { Text(stringResource(R.string.shield_ai_input_hint)) },
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edge_ai_lab_input")
                    )

                    Button(
                        onClick = { onRunAiCheck(aiSampleInput) },
                        enabled = aiSampleInput.trim().isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("run_edge_ai_scan_button")
                    ) {
                        Text(stringResource(R.string.shield_run_ai_scan))
                    }

                    AnimatedVisibility(
                        visible = labAiResult != null,
                        enter = fadeIn(tween(220)) + expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                    ) {
                        val result = labAiResult ?: return@AnimatedVisibility
                        val badgeColor = if (result.flagged) StatusDangerRed else StatusSafeGreen
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
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
                                    val flaggedCategoryTitle = result.topCategory?.let { stringResource(it.labelRes) }.orEmpty()
                                    Text(
                                        text = if (result.flagged) {
                                            stringResource(R.string.shield_ai_flagged, flaggedCategoryTitle)
                                        } else {
                                            stringResource(R.string.shield_ai_safe)
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = badgeColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.shield_latency_ms, result.inferenceLatencyMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                val triggersText = if (result.matchedTriggers.isEmpty()) {
                                    stringResource(R.string.shield_no_triggers)
                                } else {
                                    result.matchedTriggers.joinToString(", ")
                                }
                                Text(
                                    text = stringResource(R.string.shield_stage1_prefix, triggersText),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                result.classScores.forEach { (cls, score) ->
                                    val localizedClassName = when (cls) {
                                        "Safe" -> stringResource(R.string.ai_class_safe)
                                        "Cyberbullying" -> stringResource(R.string.alert_type_cyberbullying)
                                        "Self-Harm" -> stringResource(R.string.alert_type_self_harm)
                                        "Grooming / Explicit" -> stringResource(R.string.alert_type_grooming)
                                        "Scam / Gambling" -> stringResource(R.string.alert_type_scam)
                                        else -> cls
                                    }
                                    val animatedScore by animateFloatAsState(
                                        targetValue = score.toFloat().coerceIn(0f, 1f),
                                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                                        label = "aiScore_$cls"
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(localizedClassName, style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                "${(score * 100).toInt()}%",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        LinearProgressIndicator(
                                            progress = { animatedScore },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(5.dp)
                                                .clip(RoundedCornerShape(50)),
                                            color = if (cls == "Safe") StatusSafeGreen else MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.surface
                                        )
                                    }
                                }

                                Text(
                                    text = stringResource(R.string.shield_stage3_output, result.redactedSnippet),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                if (result.flagged && activeChild != null) {
                                    FilledTonalButton(
                                        onClick = { onLogAiAlert(activeChild, result) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("log_lab_alert_button")
                                    ) {
                                        Text(stringResource(R.string.shield_save_alert_to_feed, activeChild.name))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Local DNS Rule Simulator
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
                    Text(stringResource(R.string.shield_dns_tester_title), style = MaterialTheme.typography.titleMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickDnsSamples.forEach { domain ->
                            AssistChip(
                                onClick = {
                                    dnsSampleInput = domain
                                    onRunDnsCheck(domain)
                                },
                                label = { Text(domain, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = dnsSampleInput,
                            onValueChange = { dnsSampleInput = it },
                            label = { Text(stringResource(R.string.shield_dns_input_label)) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dns_lab_input")
                        )
                        Button(
                            onClick = { onRunDnsCheck(dnsSampleInput) },
                            enabled = dnsSampleInput.trim().isNotEmpty(),
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("run_dns_check_button")
                        ) {
                            Text(stringResource(R.string.action_test))
                        }
                    }
                    AnimatedVisibility(
                        visible = labDnsResult != null,
                        enter = fadeIn(tween(200)) + expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                    ) {
                        val dnsRes = labDnsResult ?: return@AnimatedVisibility
                        val color = if (dnsRes.blocked) StatusDangerRed else StatusSafeGreen
                        val localizedReason = when {
                            dnsRes.domain.isEmpty() -> stringResource(R.string.dns_reason_empty)
                            dnsRes.verdict == DnsVerdict.BLOCKED_CUSTOM -> stringResource(R.string.dns_reason_custom)
                            dnsRes.verdict == DnsVerdict.BLOCKED_CATEGORY_LIMIT -> stringResource(R.string.dns_reason_category_limit)
                            dnsRes.verdict == DnsVerdict.BLOCKED_ADULT -> stringResource(R.string.dns_reason_adult)
                            dnsRes.verdict == DnsVerdict.BLOCKED_GAMBLING -> stringResource(R.string.dns_reason_gambling)
                            dnsRes.verdict == DnsVerdict.BLOCKED_MALWARE -> stringResource(R.string.dns_reason_malware)
                            dnsRes.verdict == DnsVerdict.BLOCKED_SOCIAL -> stringResource(R.string.dns_reason_social)
                            dnsRes.verdict == DnsVerdict.SAFESEARCH_ENFORCED -> stringResource(R.string.dns_reason_safesearch)
                            else -> stringResource(R.string.dns_reason_allowed)
                        }
                        Surface(
                            color = color.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (dnsRes.blocked) R.string.shield_dns_blocked_badge else R.string.shield_dns_allowed_badge
                                        ),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = color
                                    )
                                    Text(
                                        text = stringResource(R.string.shield_latency_ms, dnsRes.latencyMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color
                                    )
                                }
                                Text(
                                    text = if (dnsRes.domain.isEmpty()) localizedReason else "${dnsRes.domain} — $localizedReason",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Device Health & OEM Keep-Alive Wizard
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
                        Column {
                            Text(stringResource(R.string.shield_health_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(
                                    R.string.shield_health_subtitle,
                                    deviceHealth.manufacturer,
                                    deviceHealth.deviceModel,
                                    deviceHealth.readinessScore
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = if (deviceHealth.readinessScore >= 75) StatusSafeGreen else StatusWarnAmber
                        )
                    }

                    val animatedReadiness by animateFloatAsState(
                        targetValue = (deviceHealth.readinessScore / 100f).coerceIn(0f, 1f),
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "readinessBar"
                    )
                    LinearProgressIndicator(
                        progress = { animatedReadiness },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = if (deviceHealth.readinessScore >= 75) StatusSafeGreen else StatusWarnAmber,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    PermissionWizardRow(
                        icon = Icons.Filled.QueryStats,
                        title = stringResource(R.string.perm_usage_title),
                        subtitle = stringResource(R.string.perm_usage_sub),
                        granted = deviceHealth.usageAccessGranted,
                        actionLabel = stringResource(R.string.action_grant),
                        tag = "grant_usage_access_button",
                        onClick = onRequestUsageAccess
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionWizardRow(
                        icon = Icons.Filled.NotificationsActive,
                        title = stringResource(R.string.perm_notif_title),
                        subtitle = stringResource(R.string.perm_notif_sub),
                        granted = deviceHealth.notificationAccessGranted,
                        actionLabel = stringResource(R.string.action_enable),
                        tag = "grant_notif_access_button",
                        onClick = onRequestNotificationAccess
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionWizardRow(
                        icon = Icons.Filled.BatteryChargingFull,
                        title = stringResource(R.string.perm_battery_title),
                        subtitle = stringResource(R.string.perm_battery_sub),
                        granted = deviceHealth.batteryOptimizationIgnored,
                        actionLabel = stringResource(R.string.action_allow),
                        tag = "grant_battery_whitelist_button",
                        onClick = onRequestBatteryWhitelist
                    )
                }
            }
        }

        // 5. Anti-Tamper Parent PIN & Account
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.pin_cd),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(stringResource(R.string.shield_pin_card_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(
                                    if (hasParentPin) R.string.shield_pin_configured else R.string.shield_pin_not_set
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onOpenPinSetup,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("setup_parent_pin_button")
                        ) {
                            Text(stringResource(if (hasParentPin) R.string.action_change_pin else R.string.action_set_pin))
                        }
                        Button(
                            onClick = onRequestChildMode,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("shield_child_mode_button")
                        ) {
                            Text(stringResource(R.string.child_mode_short))
                        }
                    }

                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = stringResource(R.string.sign_out),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.sign_out_parent_account))
                    }
                }
            }
        }

        // 6. Community Actions (Update, Rate, Share) & Privacy-First Event Telemetry
        item {
            EngagementTelemetryCard(
                engagementSnapshot = engagementSnapshot,
                availableUpdate = availableUpdate,
                recentAppEvents = recentAppEvents,
                lastPromptReason = lastPromptReason,
                onToggleAutoPrompts = onToggleAutoPrompts,
                onClearEventHistory = onClearEventHistory,
                onCheckForUpdates = onCheckForUpdates,
                onRateApp = onRateApp,
                onShareApp = onShareApp
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

@Composable
private fun MiniCounter(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = accent,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionWizardRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    tag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = stringResource(if (granted) R.string.perm_granted_cd else R.string.perm_not_granted_cd),
            tint = if (granted) StatusSafeGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!granted) {
            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier.testTag(tag)
            ) {
                Text(actionLabel)
            }
        } else {
            Text(
                text = stringResource(R.string.status_active),
                style = MaterialTheme.typography.labelLarge,
                color = StatusSafeGreen
            )
        }
    }
}
