package com.example.ui.components

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AvatarTone
import com.example.data.ChildProfile
import com.example.ui.theme.AvatarAmber
import com.example.ui.theme.AvatarCoral
import com.example.ui.theme.AvatarIndigo
import com.example.ui.theme.AvatarSage
import com.example.ui.theme.AvatarTeal
import kotlin.math.roundToInt

private val TIME_FORMAT_REGEX = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

fun avatarColorFor(tone: AvatarTone): Color = when (tone) {
    AvatarTone.TEAL -> AvatarTeal
    AvatarTone.AMBER -> AvatarAmber
    AvatarTone.CORAL -> AvatarCoral
    AvatarTone.INDIGO -> AvatarIndigo
    AvatarTone.SAGE -> AvatarSage
}

@Composable
fun AddChildDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        age: Long,
        deviceModel: String,
        tone: AvatarTone,
        dailyLimitMin: Long,
        bedtimeStart: String,
        bedtimeEnd: String
    ) -> Unit
) {
    val defaultPhoneModel = stringResource(R.string.default_phone_model)
    var name by remember { mutableStateOf("") }
    var ageFloat by remember { mutableFloatStateOf(11f) }
    var deviceModel by remember { mutableStateOf(Build.MODEL.ifBlank { defaultPhoneModel }) }
    var selectedTone by remember { mutableStateOf(AvatarTone.TEAL) }
    var dailyLimitFloat by remember { mutableFloatStateOf(120f) }
    var bedtimeStart by remember { mutableStateOf("21:30") }
    var bedtimeEnd by remember { mutableStateOf("06:30") }

    val startValid = TIME_FORMAT_REGEX.matches(bedtimeStart.trim())
    val endValid = TIME_FORMAT_REGEX.matches(bedtimeEnd.trim())
    val canSubmit = name.trim().isNotEmpty() && startValid && endValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.add_child_profile),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.dialog_child_name_label)) },
                    placeholder = { Text(stringResource(R.string.dialog_child_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_child_name_input")
                )

                OutlinedTextField(
                    value = deviceModel,
                    onValueChange = { deviceModel = it.take(60) },
                    label = { Text(stringResource(R.string.dialog_device_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_child_device_input")
                )

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.dialog_age_label), style = MaterialTheme.typography.labelLarge)
                        Text(
                            stringResource(R.string.dialog_age_years, ageFloat.roundToInt()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = ageFloat,
                        onValueChange = { ageFloat = it },
                        valueRange = 4f..17f,
                        steps = 12
                    )
                }

                Column {
                    val mins = (dailyLimitFloat / 15f).roundToInt() * 15
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.dialog_daily_limit_label), style = MaterialTheme.typography.labelLarge)
                        Text(
                            stringResource(R.string.hours_minutes_short, mins / 60, mins % 60),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = dailyLimitFloat,
                        onValueChange = { dailyLimitFloat = it },
                        valueRange = 30f..360f,
                        steps = 21
                    )
                }

                Text(stringResource(R.string.dialog_avatar_color_label), style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AvatarTone.entries.forEach { tone ->
                        val color = avatarColorFor(tone)
                        val selected = selectedTone == tone
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable { selectedTone = tone }
                                .testTag("avatar_tone_${tone.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (selected) 3.dp else 0.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = bedtimeStart,
                        onValueChange = { bedtimeStart = it.take(5) },
                        label = { Text(stringResource(R.string.dialog_bedtime_label)) },
                        placeholder = { Text("21:30") },
                        isError = !startValid,
                        supportingText = if (!startValid) {
                            { Text(stringResource(R.string.dialog_time_format_error)) }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = bedtimeEnd,
                        onValueChange = { bedtimeEnd = it.take(5) },
                        label = { Text(stringResource(R.string.dialog_wakeup_label)) },
                        placeholder = { Text("06:30") },
                        isError = !endValid,
                        supportingText = if (!endValid) {
                            { Text(stringResource(R.string.dialog_time_format_error)) }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val limit = ((dailyLimitFloat / 15f).roundToInt() * 15).toLong().coerceIn(15L, 720L)
                    onConfirm(
                        name.trim(),
                        ageFloat.roundToInt().toLong(),
                        deviceModel.trim(),
                        selectedTone,
                        limit,
                        bedtimeStart.trim(),
                        bedtimeEnd.trim()
                    )
                },
                enabled = canSubmit,
                modifier = Modifier.testTag("confirm_add_child_button")
            ) {
                Text(stringResource(R.string.dialog_save_profile))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun ConfirmDeleteChildDialog(
    childName: String,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = stringResource(R.string.delete_profile_cd),
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.dialog_delete_child_title, childName), style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text = stringResource(R.string.dialog_delete_child_body, childName),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.testTag("confirm_delete_child_button")
            ) {
                Text(stringResource(R.string.dialog_confirm_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_keep_profile))
            }
        }
    )
}

@Composable
fun ProminentDisclosureDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.privacy_disclosure_cd),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.privacy_guarantee_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                modifier = Modifier.testTag("accept_disclosure_button")
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_not_now))
            }
        }
    )
}

@Composable
fun ParentPinDialog(
    title: String,
    subtitle: String,
    confirmLabel: String,
    errorMessage: String? = null,
    errorCounter: Int = 0,
    onDismiss: () -> Unit,
    onSubmitPin: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(errorCounter) {
        if (errorCounter > 0) {
            pin = ""
            listOf(-18f, 18f, -12f, 12f, -6f, 0f).forEach { target ->
                shakeOffset.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 45)
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = stringResource(R.string.pin_cd),
                tint = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.offset { IntOffset(shakeOffset.value.roundToInt(), 0) }
            ) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)

                // Visual 4-dot PIN indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { index ->
                        val filled = index < pin.length
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        errorMessage != null && pin.isEmpty() -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                                        filled -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.outlineVariant
                                    }
                                )
                        )
                    }
                }

                OutlinedTextField(
                    value = pin,
                    onValueChange = { input ->
                        pin = input.filter { it.isDigit() }.take(4)
                    },
                    label = { Text(stringResource(R.string.pin_input_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = errorMessage != null && pin.isEmpty(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (pin.length == 4) onSubmitPin(pin)
                        }
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("parent_pin_input")
                )

                AnimatedVisibility(visible = errorMessage != null && pin.isEmpty()) {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmitPin(pin) },
                enabled = pin.length == 4,
                modifier = Modifier.testTag("submit_parent_pin_button")
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun ChildGuardModeScreen(
    activeChild: ChildProfile?,
    vpnRunning: Boolean,
    onRequestParentUnlock: () -> Unit
) {
    // Prevent exiting Child Guard Mode via hardware/gesture Back button
    BackHandler(enabled = true) {
        onRequestParentUnlock()
    }

    val paused = activeChild?.downtimePaused == true
    val childName = activeChild?.name ?: stringResource(R.string.child_default_name)
    val limitMin = activeChild?.dailyLimitMin ?: 120L
    val usedMin = activeChild?.todayScreenTimeMin ?: 0L
    val remaining = (limitMin - usedMin).coerceAtLeast(0L)

    val breathTransition = rememberInfiniteTransition(label = "guardPulse")
    val pulseScale by breathTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shieldBreath"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .scale(pulseScale)
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(
                            if (paused) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = stringResource(R.string.child_guard_active_cd),
                        tint = if (paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Text(
                    text = stringResource(if (paused) R.string.child_guard_paused_title else R.string.child_guard_active_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = if (paused) {
                        stringResource(R.string.child_guard_paused_body, childName)
                    } else {
                        stringResource(R.string.child_guard_active_body, childName)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.child_guard_remaining), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.hours_minutes_short, remaining / 60, remaining % 60),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.child_guard_bedtime), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${activeChild?.bedtimeStart ?: "21:30"} – ${activeChild?.bedtimeEnd ?: "06:30"}",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.child_guard_dns_label), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(if (vpnRunning) R.string.status_active else R.string.child_guard_standby),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (vpnRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedButton(
                    onClick = onRequestParentUnlock,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("unlock_parent_mode_button"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LockOpen,
                        contentDescription = stringResource(R.string.child_guard_unlock_cd),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.child_guard_unlock_btn))
                }
            }
        }
    }
}
