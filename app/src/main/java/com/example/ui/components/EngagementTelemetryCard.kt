package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.analytics.AppEventName
import com.example.analytics.AppUpdateInfo
import com.example.analytics.ChannelEligibilityStatus
import com.example.analytics.EngagementSnapshot
import com.example.analytics.PromptSchedulerEngine
import com.example.analytics.SuppressionReason
import com.example.analytics.TrackedAppEvent
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusSafeGreen
import com.example.ui.theme.StatusWarnAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EngagementTelemetryCard(
    engagementSnapshot: EngagementSnapshot,
    availableUpdate: AppUpdateInfo,
    recentAppEvents: List<TrackedAppEvent>,
    lastPromptReason: SuppressionReason,
    onToggleAutoPrompts: (Boolean) -> Unit,
    onClearEventHistory: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onRateApp: () -> Unit,
    onShareApp: () -> Unit
) {
    val nowMs = System.currentTimeMillis()
    val readiness = remember(engagementSnapshot, availableUpdate) {
        PromptSchedulerEngine.summarizeReadiness(engagementSnapshot, availableUpdate, nowMs)
    }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var showAllEvents by remember { mutableStateOf(false) }

    val animatedReadiness by animateFloatAsState(
        targetValue = (readiness.readinessPercent / 100f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "promptReadinessBar"
    )

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
            // Header + Master Auto-Suggestions Switch
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
                    Icon(
                        imageVector = Icons.Filled.Timeline,
                        contentDescription = stringResource(R.string.telemetry_card_title),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.telemetry_card_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.telemetry_card_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = engagementSnapshot.autoPromptsEnabled,
                    onCheckedChange = onToggleAutoPrompts,
                    modifier = Modifier.testTag("auto_prompts_toggle_switch")
                )
            }

            // Key telemetry metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryMetricTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.telemetry_sessions_label),
                    value = "${engagementSnapshot.totalSessions}"
                )
                TelemetryMetricTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.telemetry_active_days_label),
                    value = "${engagementSnapshot.activeDaysCount}"
                )
                TelemetryMetricTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.telemetry_score_label),
                    value = "${engagementSnapshot.engagementScore}"
                )
                TelemetryMetricTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.telemetry_events_label),
                    value = "${recentAppEvents.size}"
                )
            }

            // Readiness progress + live anti-spam status
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.telemetry_readiness_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${readiness.readinessPercent}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { animatedReadiness },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = if (engagementSnapshot.autoPromptsEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                    Text(
                        text = when {
                            !engagementSnapshot.autoPromptsEnabled ->
                                stringResource(
                                    R.string.telemetry_scheduler_status,
                                    stringResource(R.string.scheduler_reason_user_disabled)
                                )
                            readiness.effectiveCooldownHoursRemaining > 0L ->
                                stringResource(
                                    R.string.telemetry_cooldown_active,
                                    readiness.effectiveCooldownHoursRemaining
                                )
                            else ->
                                stringResource(
                                    R.string.telemetry_scheduler_status,
                                    stringResource(suppressionReasonLabelRes(lastPromptReason))
                                )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ChannelStatusBadge(
                            channelName = stringResource(R.string.action_update_short),
                            status = readiness.updateStatus
                        )
                        ChannelStatusBadge(
                            channelName = stringResource(R.string.action_rate_short),
                            status = readiness.rateStatus
                        )
                        ChannelStatusBadge(
                            channelName = stringResource(R.string.action_share_short),
                            status = readiness.shareStatus
                        )
                    }
                }
            }

            // Voluntary manual actions (always available even when auto-prompts are paused)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onCheckForUpdates,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("manual_check_update_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.action_update_short),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilledTonalButton(
                    onClick = onRateApp,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("manual_rate_app_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.action_rate_short),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilledTonalButton(
                    onClick = onShareApp,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("manual_share_app_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.action_share_short),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Persisted Recent Event Log
            if (recentAppEvents.isNotEmpty()) {
                val visibleEvents = if (showAllEvents) recentAppEvents.take(15) else recentAppEvents.take(5)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.telemetry_recent_events_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = onClearEventHistory,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("clear_event_log_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteSweep,
                                    contentDescription = stringResource(R.string.telemetry_clear_log),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.telemetry_clear_log),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        visibleEvents.forEach { event ->
                            val accentColor = when {
                                event.name.isFriction -> StatusDangerRed
                                event.name.positiveWeight > 0 -> StatusSafeGreen
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(eventLabelRes(event.name)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (event.name.isFriction) accentColor else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (event.name.positiveWeight > 0) {
                                    Text(
                                        text = "+${event.name.positiveWeight}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StatusSafeGreen,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    )
                                }
                                Text(
                                    text = timeFormatter.format(Date(event.timestampMs)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (recentAppEvents.size > 5) {
                            TextButton(
                                onClick = { showAllEvents = !showAllEvents },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .testTag("toggle_all_events_button")
                            ) {
                                Text(
                                    text = stringResource(
                                        if (showAllEvents) R.string.telemetry_show_less else R.string.telemetry_show_more
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChannelStatusBadge(
    channelName: String,
    status: ChannelEligibilityStatus
) {
    val (statusRes, color) = when (status) {
        ChannelEligibilityStatus.READY -> R.string.channel_status_ready to StatusSafeGreen
        ChannelEligibilityStatus.COMPLETED -> R.string.channel_status_done to MaterialTheme.colorScheme.primary
        ChannelEligibilityStatus.COOLDOWN -> R.string.channel_status_cooldown to StatusWarnAmber
        ChannelEligibilityStatus.OPTED_OUT -> R.string.channel_status_muted to MaterialTheme.colorScheme.outline
        ChannelEligibilityStatus.LOCKED -> R.string.channel_status_waiting to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = "$channelName: ${stringResource(statusRes)}",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

private fun suppressionReasonLabelRes(reason: SuppressionReason): Int = when (reason) {
    SuppressionReason.ELIGIBLE -> R.string.scheduler_reason_eligible
    SuppressionReason.BLOCKED_USER_DISABLED -> R.string.scheduler_reason_user_disabled
    SuppressionReason.BLOCKED_APP_START -> R.string.scheduler_reason_app_start
    SuppressionReason.BLOCKED_SENSITIVE_CONTEXT -> R.string.scheduler_reason_sensitive
    SuppressionReason.BLOCKED_SESSION_CAP -> R.string.scheduler_reason_session_cap
    SuppressionReason.BLOCKED_GLOBAL_COOLDOWN -> R.string.scheduler_reason_cooldown
    SuppressionReason.BLOCKED_FATIGUE_COOLDOWN -> R.string.scheduler_reason_fatigue
    SuppressionReason.BLOCKED_MONTHLY_CAP -> R.string.scheduler_reason_monthly_cap
    SuppressionReason.BLOCKED_RECENT_FRICTION -> R.string.scheduler_reason_friction
    SuppressionReason.BLOCKED_MIN_DWELL_TIME -> R.string.scheduler_reason_dwell
    SuppressionReason.BLOCKED_INSUFFICIENT_ENGAGEMENT -> R.string.scheduler_reason_engagement
    SuppressionReason.BLOCKED_NO_CANDIDATE_READY -> R.string.scheduler_reason_waiting
}

private fun eventLabelRes(name: AppEventName): Int = when (name) {
    AppEventName.APP_SESSION_STARTED -> R.string.event_session_started
    AppEventName.APP_FOREGROUNDED -> R.string.event_app_foregrounded
    AppEventName.APP_BACKGROUNDED -> R.string.event_app_backgrounded
    AppEventName.TAB_VIEWED -> R.string.event_tab_viewed
    AppEventName.SIGN_IN_COMPLETED -> R.string.event_sign_in
    AppEventName.SIGN_OUT -> R.string.event_sign_out
    AppEventName.CHILD_PROFILE_CREATED -> R.string.event_child_created
    AppEventName.DNS_SHIELD_STARTED -> R.string.event_dns_started
    AppEventName.DNS_SHIELD_STOPPED -> R.string.event_dns_stopped
    AppEventName.DNS_SHIELD_RULE_TOGGLED -> R.string.event_dns_rule_toggled
    AppEventName.CUSTOM_DOMAIN_BLOCKED -> R.string.event_domain_blocked
    AppEventName.BEDTIME_SCHEDULE_UPDATED -> R.string.event_bedtime_updated
    AppEventName.CATEGORY_LIMIT_UPDATED -> R.string.event_category_limit_updated
    AppEventName.CATEGORY_PRESET_APPLIED -> R.string.event_category_preset_applied
    AppEventName.USAGE_SYNC_COMPLETED -> R.string.event_usage_synced
    AppEventName.WEEKLY_SUMMARY_VIEWED -> R.string.event_weekly_summary_viewed
    AppEventName.WEEKLY_PUSH_TRIGGERED -> R.string.event_weekly_push_triggered
    AppEventName.ALERT_REVIEWED -> R.string.event_alert_reviewed
    AppEventName.ALL_ALERTS_REVIEWED -> R.string.event_all_alerts_reviewed
    AppEventName.EDGE_AI_LAB_SCAN_RUN -> R.string.event_ai_lab_scan
    AppEventName.DNS_LAB_TEST_RUN -> R.string.event_dns_lab_test
    AppEventName.AI_COACH_QUERY -> R.string.event_ai_coach_query
    AppEventName.AI_RISK_CHECK_RUN -> R.string.event_ai_risk_check
    AppEventName.AI_BYOK_CONFIGURED -> R.string.event_ai_byok_configured
    AppEventName.PARENT_PIN_CONFIGURED -> R.string.event_pin_configured
    AppEventName.CHILD_GUARD_MODE_ENABLED -> R.string.event_child_mode_on
    AppEventName.ACTION_ERROR_OCCURRED -> R.string.event_error_friction
    AppEventName.PERMISSION_DISCLOSURE_DECLINED -> R.string.event_disclosure_declined
    AppEventName.PIN_ENTRY_FAILED -> R.string.event_pin_failed
    AppEventName.PROMPT_SHOWN -> R.string.event_prompt_shown
    AppEventName.PROMPT_ACCEPTED -> R.string.event_prompt_accepted
    AppEventName.PROMPT_DISMISSED -> R.string.event_prompt_dismissed
    AppEventName.PROMPT_OPTED_OUT -> R.string.event_prompt_opted_out
}
