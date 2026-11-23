package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.analytics.ActivePromptRequest
import com.example.analytics.AppUpdateInfo
import com.example.analytics.EngagementPromptType
import com.example.ui.theme.StatusWarnAmber

@Composable
fun EngagementPromptHost(
    request: ActivePromptRequest?,
    onAcceptUpdate: () -> Unit,
    onAcceptRate: () -> Unit,
    onAcceptShare: () -> Unit,
    onDismissLater: (EngagementPromptType) -> Unit,
    onOptOut: (EngagementPromptType) -> Unit
) {
    val active = request ?: return
    when (active.type) {
        EngagementPromptType.UPDATE -> UpdateSuggestionDialog(
            updateInfo = active.updateInfo,
            onUpdateNow = onAcceptUpdate,
            onLater = { onDismissLater(EngagementPromptType.UPDATE) },
            onSkipVersion = { onOptOut(EngagementPromptType.UPDATE) }
        )

        EngagementPromptType.RATE -> RateAppSuggestionDialog(
            onRateOnStore = onAcceptRate,
            onLater = { onDismissLater(EngagementPromptType.RATE) },
            onNeverAsk = { onOptOut(EngagementPromptType.RATE) }
        )

        EngagementPromptType.SHARE -> ShareAppSuggestionDialog(
            onShareNow = onAcceptShare,
            onLater = { onDismissLater(EngagementPromptType.SHARE) },
            onNeverAsk = { onOptOut(EngagementPromptType.SHARE) }
        )
    }
}

@Composable
private fun UpdateSuggestionDialog(
    updateInfo: AppUpdateInfo?,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
    onSkipVersion: () -> Unit
) {
    val hasNewer = updateInfo?.hasUpdate == true
    val versionLabel = updateInfo?.latestVersionName.orEmpty().ifBlank { BuildConfig.VERSION_NAME }
    val mandatory = updateInfo?.isMandatory == true

    AlertDialog(
        onDismissRequest = { if (!mandatory) onLater() },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = !mandatory,
            dismissOnClickOutside = !mandatory
        ),
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = stringResource(R.string.prompt_update_icon_cd),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(
                    if (hasNewer) R.string.prompt_update_title else R.string.prompt_up_to_date_title
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (hasNewer) {
                        stringResource(R.string.prompt_update_body, versionLabel)
                    } else {
                        stringResource(R.string.prompt_up_to_date_body, versionLabel)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasNewer && updateInfo.releaseNotes.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = updateInfo.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = if (hasNewer) onUpdateNow else onLater,
                modifier = Modifier.testTag("prompt_update_confirm_button")
            ) {
                Text(
                    stringResource(
                        if (hasNewer) R.string.prompt_update_cta else R.string.prompt_ok_cta
                    )
                )
            }
        },
        dismissButton = {
            if (hasNewer && !mandatory) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onSkipVersion,
                        modifier = Modifier.testTag("prompt_update_skip_button")
                    ) {
                        Text(stringResource(R.string.prompt_skip_version))
                    }
                    TextButton(
                        onClick = onLater,
                        modifier = Modifier.testTag("prompt_update_later_button")
                    ) {
                        Text(stringResource(R.string.prompt_later))
                    }
                }
            }
        }
    )
}

@Composable
private fun RateAppSuggestionDialog(
    onRateOnStore: () -> Unit,
    onLater: () -> Unit,
    onNeverAsk: () -> Unit
) {
    var selectedStars by remember { mutableIntStateOf(5) }

    AlertDialog(
        onDismissRequest = onLater,
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(StatusWarnAmber.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = stringResource(R.string.prompt_rate_icon_cd),
                    tint = StatusWarnAmber,
                    modifier = Modifier.size(30.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.prompt_rate_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.prompt_rate_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // Interactive non-gating star preview (Google Play compliant: any rating can open Play Store)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (star in 1..5) {
                        val filled = star <= selectedStars
                        val scale by animateFloatAsState(
                            targetValue = if (filled) 1.08f else 0.96f,
                            animationSpec = spring(stiffness = Spring.StiffnessMedium),
                            label = "rateStarScale_$star"
                        )
                        val tint by animateColorAsState(
                            targetValue = if (filled) StatusWarnAmber else MaterialTheme.colorScheme.outline,
                            label = "rateStarTint_$star"
                        )
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable { selectedStars = star }
                                .testTag("rate_star_$star"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = stringResource(R.string.prompt_rate_star_cd, star),
                                tint = tint,
                                modifier = Modifier
                                    .size(30.dp)
                                    .scale(scale)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRateOnStore,
                modifier = Modifier.testTag("prompt_rate_confirm_button")
            ) {
                Text(stringResource(R.string.prompt_rate_cta))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onNeverAsk,
                    modifier = Modifier.testTag("prompt_rate_never_button")
                ) {
                    Text(stringResource(R.string.prompt_dont_ask_again))
                }
                TextButton(
                    onClick = onLater,
                    modifier = Modifier.testTag("prompt_rate_later_button")
                ) {
                    Text(stringResource(R.string.prompt_later))
                }
            }
        }
    )
}

@Composable
private fun ShareAppSuggestionDialog(
    onShareNow: () -> Unit,
    onLater: () -> Unit,
    onNeverAsk: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.prompt_share_icon_cd),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.prompt_share_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.prompt_share_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.prompt_share_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onShareNow,
                modifier = Modifier.testTag("prompt_share_confirm_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.prompt_share_cta))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onNeverAsk,
                    modifier = Modifier.testTag("prompt_share_never_button")
                ) {
                    Text(stringResource(R.string.prompt_dont_ask_again))
                }
                TextButton(
                    onClick = onLater,
                    modifier = Modifier.testTag("prompt_share_later_button")
                ) {
                    Text(stringResource(R.string.prompt_later))
                }
            }
        }
    )
}
