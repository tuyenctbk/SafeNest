package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AiCoachPreferences
import com.example.data.AiEngineMode
import com.example.data.AlertSeverity
import com.example.data.ByokGeminiModel
import com.example.engine.AiCoachMessage
import com.example.engine.AiRecommendedAction
import com.example.engine.AiRiskCheckResult
import com.example.engine.AiWeeklyBrief
import com.example.engine.ByokTestResult
import com.example.ui.components.AiByokSettingsDialog
import com.example.ui.components.AiEngineStatusBanner
import com.example.ui.components.AiOriginBadge
import com.example.ui.components.AiRecommendedActionsRow
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusSafeGreen
import com.example.ui.theme.StatusWarnAmber

private enum class AiCoachSection {
    COACH_CHAT,
    RISK_CHECKER,
    WEEKLY_BRIEF
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiCoachScreen(
    activeChildName: String,
    prefs: AiCoachPreferences,
    messages: List<AiCoachMessage>,
    riskResult: AiRiskCheckResult?,
    weeklyBrief: AiWeeklyBrief?,
    isThinking: Boolean,
    isTestingKey: Boolean,
    keyTestResult: ByokTestResult?,
    onAskCoach: (String) -> Unit,
    onClearChat: () -> Unit,
    onRunRiskCheck: (String, Uri?) -> Unit,
    onRefreshWeeklyBrief: (Boolean) -> Unit,
    onExecuteAction: (AiRecommendedAction) -> Unit,
    onSaveByokSettings: (AiEngineMode, String, ByokGeminiModel, Int, Boolean) -> Unit,
    onEnableEcoLite: () -> Unit,
    onClaimGraceCredits: () -> Unit,
    onSwitchToLocalFree: () -> Unit,
    onClearByokKey: () -> Unit,
    onTestByokKey: (String, ByokGeminiModel) -> Unit,
    onClearKeyTestResult: () -> Unit
) {
    var selectedSection by rememberSaveable { mutableStateOf(AiCoachSection.COACH_CHAT) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var chatInput by rememberSaveable { mutableStateOf("") }
    var riskInput by rememberSaveable { mutableStateOf("") }
    var pickedScreenshotUri by remember { mutableStateOf<Uri?>(null) }
    val chatListState = rememberLazyListState()

    LaunchedEffect(messages.size, isThinking) {
        if (messages.isNotEmpty()) {
            val targetIdx = messages.size + (if (isThinking) 1 else 0)
            chatListState.animateScrollToItem(targetIdx)
        }
    }

    // Zero-permission Android Photo Picker (Google Play compliant — no storage permission needed)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pickedScreenshotUri = uri
        }
    }

    LaunchedEffect(selectedSection, weeklyBrief) {
        if (selectedSection == AiCoachSection.WEEKLY_BRIEF && weeklyBrief == null) {
            onRefreshWeeklyBrief(false)
        }
    }

    val starterQuestions = listOf(
        stringResource(R.string.ai_coach_starter_gaming),
        stringResource(R.string.ai_coach_starter_bedtime),
        stringResource(R.string.ai_coach_starter_bully),
        stringResource(R.string.ai_coach_starter_agreement)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiEngineStatusBanner(
            prefs = prefs,
            onOpenSettings = {
                onClearKeyTestResult()
                showSettingsDialog = true
            },
            onEnableEcoLite = onEnableEcoLite,
            onClaimGraceCredits = onClaimGraceCredits,
            onSwitchToLocalFree = onSwitchToLocalFree
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            val sections = listOf(
                AiCoachSection.COACH_CHAT to R.string.ai_coach_tab_chat,
                AiCoachSection.RISK_CHECKER to R.string.ai_coach_tab_risk,
                AiCoachSection.WEEKLY_BRIEF to R.string.ai_coach_tab_brief
            )
            sections.forEachIndexed { index, (section, labelRes) ->
                SegmentedButton(
                    selected = selectedSection == section,
                    onClick = { selectedSection = section },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = sections.size),
                    modifier = Modifier.testTag("ai_section_${section.name.lowercase()}")
                ) {
                    Text(
                        text = stringResource(labelRes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        AnimatedContent(
            targetState = selectedSection,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            transitionSpec = {
                val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                (fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 12 * direction }) togetherWith
                    (fadeOut(tween(140)) + slideOutHorizontally(tween(140)) { -it / 12 * direction })
            },
            label = "aiCoachSectionTransition"
        ) { section ->
            when (section) {
                AiCoachSection.COACH_CHAT -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LazyColumn(
                            state = chatListState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                    if (messages.isEmpty()) {
                        item {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Forum,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = stringResource(R.string.ai_coach_welcome_title, activeChildName),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.ai_coach_welcome_body),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        starterQuestions.forEachIndexed { idx, prompt ->
                                            AssistChip(
                                                onClick = { onAskCoach(prompt) },
                                                label = {
                                                    Text(
                                                        text = prompt,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                },
                                                modifier = Modifier.testTag("ai_starter_chip_$idx")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = onClearChat,
                                    modifier = Modifier.testTag("ai_clear_chat_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteSweep,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.ai_coach_clear_chat))
                                }
                            }
                        }
                        items(messages, key = { it.id }) { msg ->
                            AiChatBubbleCard(
                                message = msg,
                                onActionClick = onExecuteAction
                            )
                        }
                    }

                    if (isThinking) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = stringResource(R.string.ai_coach_thinking),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Bottom prompt composer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = chatInput,
                        onValueChange = { chatInput = it },
                        placeholder = { Text(stringResource(R.string.ai_coach_input_placeholder)) },
                        maxLines = 3,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_coach_chat_input")
                    )
                    Button(
                        onClick = {
                            val prompt = chatInput.trim()
                            if (prompt.isNotEmpty()) {
                                chatInput = ""
                                onAskCoach(prompt)
                            }
                        },
                        enabled = !isThinking && chatInput.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .size(52.dp)
                            .testTag("ai_coach_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.ai_coach_send_cd)
                        )
                    }
                }
                    }
                }

                AiCoachSection.RISK_CHECKER -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column {
                                        Text(
                                            text = stringResource(R.string.ai_coach_risk_card_title),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = stringResource(R.string.ai_coach_risk_card_subtitle),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = riskInput,
                                    onValueChange = { riskInput = it },
                                    label = { Text(stringResource(R.string.ai_coach_risk_input_label)) },
                                    placeholder = { Text(stringResource(R.string.ai_coach_risk_input_placeholder)) },
                                    minLines = 2,
                                    maxLines = 4,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ai_risk_text_input")
                                )

                                // Zero-permission Photo Picker row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("ai_pick_screenshot_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.AddPhotoAlternate,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(
                                                if (pickedScreenshotUri != null) R.string.ai_coach_change_screenshot
                                                else R.string.ai_coach_attach_screenshot
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (pickedScreenshotUri != null) {
                                        IconButton(
                                            onClick = { pickedScreenshotUri = null },
                                            modifier = Modifier.testTag("ai_remove_screenshot_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = stringResource(R.string.ai_coach_remove_screenshot_cd)
                                            )
                                        }
                                    }
                                }

                                if (pickedScreenshotUri != null) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Image,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.ai_coach_screenshot_attached),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                Button(
                                    onClick = { onRunRiskCheck(riskInput, pickedScreenshotUri) },
                                    enabled = !isThinking && (riskInput.isNotBlank() || pickedScreenshotUri != null),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("ai_run_risk_check_button")
                                ) {
                                    if (isThinking) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.Psychology,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.ai_coach_analyze_risk_button))
                                    }
                                }
                            }
                        }
                    }

                    if (riskResult != null) {
                        item {
                            AiRiskVerdictCard(
                                result = riskResult,
                                onActionClick = onExecuteAction
                            )
                        }
                    }
                }
            }

                AiCoachSection.WEEKLY_BRIEF -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
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
                                            imageVector = Icons.Filled.Summarize,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = stringResource(R.string.ai_coach_weekly_brief_title, activeChildName),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = { onRefreshWeeklyBrief(true) },
                                        enabled = !isThinking,
                                        modifier = Modifier
                                            .height(42.dp)
                                            .testTag("ai_refresh_weekly_brief_button")
                                    ) {
                                        if (isThinking) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Filled.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(stringResource(R.string.ai_coach_refresh_brief))
                                        }
                                    }
                                }

                                if (weeklyBrief != null) {
                                    AiOriginBadge(
                                        origin = weeklyBrief.origin,
                                        fallbackReason = weeklyBrief.fallbackReason
                                    )
                                    Text(
                                        text = weeklyBrief.headline,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        weeklyBrief.bullets.forEach { bullet ->
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.AutoAwesome,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .padding(top = 2.dp)
                                                        .size(15.dp)
                                                )
                                                Text(
                                                    text = bullet,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.ai_coach_conversation_starter_label),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "\"${weeklyBrief.conversationStarter}\"",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }

                                    AiRecommendedActionsRow(
                                        actions = weeklyBrief.recommendedActions,
                                        onActionClick = onExecuteAction
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

    if (showSettingsDialog) {
        AiByokSettingsDialog(
            prefs = prefs,
            isTestingKey = isTestingKey,
            keyTestResult = keyTestResult,
            onDismiss = { showSettingsDialog = false },
            onTestKey = onTestByokKey,
            onClearKey = onClearByokKey,
            onSave = onSaveByokSettings
        )
    }
}

@Composable
private fun AiChatBubbleCard(
    message: AiCoachMessage,
    onActionClick: (AiRecommendedAction) -> Unit
) {
    val containerColor = if (message.isUser) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (message.isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                Text(
                    text = stringResource(
                        if (message.isUser) R.string.ai_coach_sender_parent else R.string.ai_coach_sender_coach
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (!message.isUser) {
                    AiOriginBadge(
                        origin = message.origin,
                        fallbackReason = message.fallbackReason
                    )
                }
            }
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!message.isUser && message.recommendedActions.isNotEmpty()) {
                AiRecommendedActionsRow(
                    actions = message.recommendedActions,
                    onActionClick = onActionClick
                )
            }
        }
    }
}

@Composable
private fun AiRiskVerdictCard(
    result: AiRiskCheckResult,
    onActionClick: (AiRecommendedAction) -> Unit
) {
    val sevColor = when (result.severity) {
        AlertSeverity.HIGH -> StatusDangerRed
        AlertSeverity.MEDIUM -> StatusWarnAmber
        AlertSeverity.LOW -> StatusSafeGreen
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.2.dp, sevColor.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_risk_verdict_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (result.severity == AlertSeverity.LOW) Icons.Filled.Shield else Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = sevColor
                    )
                    Text(
                        text = stringResource(result.severity.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = sevColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                AiOriginBadge(
                    origin = result.origin,
                    fallbackReason = result.fallbackReason
                )
            }

            Text(
                text = result.headlineRes?.let { stringResource(it) } ?: result.headline,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = result.explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ai_coach_parent_tip_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = result.parentTip,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            AiRecommendedActionsRow(
                actions = result.recommendedActions,
                onActionClick = onActionClick
            )
        }
    }
}
