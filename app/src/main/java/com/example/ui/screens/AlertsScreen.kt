package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.annotation.StringRes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AlertSeverity
import com.example.data.SafetyAlert
import com.example.data.UiState
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusSafeGreen
import com.example.ui.theme.StatusWarnAmber
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Locale

private enum class AlertFilter(@StringRes val labelRes: Int) {
    ALL(R.string.alert_filter_all),
    UNREVIEWED(R.string.alert_filter_unreviewed),
    HIGH(R.string.alert_filter_high)
}

@Composable
fun AlertsScreen(
    alertsState: UiState<List<SafetyAlert>>,
    onAcknowledge: (SafetyAlert, Boolean) -> Unit,
    onAcknowledgeAll: () -> Unit,
    onDelete: (SafetyAlert) -> Unit
) {
    var activeFilter by remember { mutableStateOf(AlertFilter.ALL) }

    when (alertsState) {
        is UiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        is UiState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = alertsState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        is UiState.Success -> {
            val allAlerts = alertsState.data
            val filtered = remember(allAlerts, activeFilter) {
                when (activeFilter) {
                    AlertFilter.ALL -> allAlerts
                    AlertFilter.UNREVIEWED -> allAlerts.filter { !it.acknowledged }
                    AlertFilter.HIGH -> allAlerts.filter { it.parsedSeverity() == AlertSeverity.HIGH }
                }
            }

            val unreviewedCount = remember(allAlerts) { allAlerts.count { !it.acknowledged } }

            Column(modifier = Modifier.fillMaxSize()) {
                // Filter Row + Mark All Reviewed Action
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AlertFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = activeFilter == filter,
                                onClick = { activeFilter = filter },
                                label = { Text(stringResource(filter.labelRes)) },
                                modifier = Modifier.testTag("alert_filter_${filter.name.lowercase()}")
                            )
                        }
                    }
                    AnimatedVisibility(visible = unreviewedCount > 0) {
                        TextButton(
                            onClick = onAcknowledgeAll,
                            modifier = Modifier.testTag("mark_all_alerts_reviewed_button")
                        ) {
                            Text(stringResource(R.string.alerts_review_all))
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(58.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Shield,
                                        contentDescription = stringResource(R.string.all_clear_cd),
                                        tint = StatusSafeGreen,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.alerts_empty_title),
                                    style = MaterialTheme.typography.headlineSmall
                                )
                                Text(
                                    text = stringResource(R.string.alerts_empty_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filtered, key = { it.id }) { alert ->
                            SafetyAlertCard(
                                alert = alert,
                                onAcknowledge = { onAcknowledge(alert, !alert.acknowledged) },
                                onDelete = { onDelete(alert) },
                                modifier = Modifier.animateItem()
                            )
                        }
                        item { Spacer(modifier = Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyAlertCard(
    alert: SafetyAlert,
    onAcknowledge: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val severity = alert.parsedSeverity()
    val accentColor = when (severity) {
        AlertSeverity.HIGH -> StatusDangerRed
        AlertSeverity.MEDIUM -> StatusWarnAmber
        AlertSeverity.LOW -> StatusSafeGreen
    }
    val cardBg by animateColorAsState(
        targetValue = if (alert.acknowledged) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = spring(),
        label = "alertCardBg"
    )
    val cardBorder by animateColorAsState(
        targetValue = if (alert.acknowledged) {
            MaterialTheme.colorScheme.outlineVariant
        } else {
            accentColor.copy(alpha = 0.45f)
        },
        animationSpec = spring(),
        label = "alertCardBorder"
    )
    val formatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val timestampText = remember(alert.createdAt) {
        val ts = alert.createdAt ?: Timestamp.now()
        formatter.format(ts.toDate())
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(width = 1.dp, color = cardBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("safety_alert_card_${alert.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val severityLabel = stringResource(severity.labelRes)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (alert.acknowledged) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                        contentDescription = severityLabel,
                        tint = if (alert.acknowledged) StatusSafeGreen else accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(alert.parsedType().labelRes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${alert.childName} • ${alert.appSource} • $timestampText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    color = accentColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = severityLabel.uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = alert.redactedSnippet,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }

            // Confidence Meter
            val pct = (alert.confidence * 100).toInt().coerceIn(0, 100)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.alert_confidence_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$pct%",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor
                    )
                }
                LinearProgressIndicator(
                    progress = { alert.confidence.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onAcknowledge,
                    modifier = Modifier.testTag("acknowledge_alert_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DoneAll,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(if (alert.acknowledged) R.string.alert_reviewed else R.string.alert_mark_reviewed))
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_alert_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.alert_delete_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
