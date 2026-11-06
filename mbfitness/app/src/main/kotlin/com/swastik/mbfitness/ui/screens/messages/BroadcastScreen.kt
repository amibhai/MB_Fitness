package com.swastik.mbfitness.ui.screens.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.App
import com.swastik.mbfitness.domain.TemplateEngine
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.BannerTone
import com.swastik.mbfitness.ui.components.ConfirmDialog
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.MbActionChip
import com.swastik.mbfitness.ui.components.MbBanner
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbCheckbox
import com.swastik.mbfitness.ui.components.MbChip
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.PrimaryButton
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.components.SmsBubble
import com.swastik.mbfitness.ui.components.inserting
import com.swastik.mbfitness.ui.components.rememberSmsPermissionRequest
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

private enum class SendStep { LEFT_WARNING, CONFIRM }

/** SPEC 8.8 and 10.2 screen 9. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BroadcastScreen(pickedIds: Set<Long>, onBack: () -> Unit, onSent: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val viewModel = appViewModel(key = "broadcast-${pickedIds.sorted()}") { container ->
        BroadcastViewModel(pickedIds, container.members, container.messages, container.outboxKicker, container.settings, container.clock)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = MbTheme.colors
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var step by remember { mutableStateOf<SendStep?>(null) }
    val requestPermission = rememberSmsPermissionRequest()

    fun setField(value: TextFieldValue) {
        // Same length, so the cursor stays put. Curly quotes silently force Unicode (SPEC 8.7).
        field = value.copy(text = TemplateEngine.straightenQuotes(value.text))
        viewModel.onBodyChange(field.text)
    }

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Broadcast", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            state.quietReason?.let { reason ->
                MbBanner("Today is a quiet day ($reason). Broadcasts still go.", BannerTone.INFO)
            }
            if (state.testMode) {
                MbBanner("Test mode is on. Every SMS goes only to your own number.", BannerTone.INFO)
            }
            SectionLabel("Who gets it")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.audiences.forEach { audience ->
                    MbChip(
                        "${audienceLabel(audience)} ${state.counts[audience] ?: 0}",
                        selected = state.audience == audience,
                        onClick = { viewModel.onAudienceSelected(audience) },
                    )
                }
            }
            MbCheckbox(state.skipRemindersOff, viewModel::onSkipRemindersOffChange, "Skip members with reminders off")
            if (state.withoutConsent > 0) {
                val them = if (state.withoutConsent == 1) "1 person here hasn't" else "${state.withoutConsent} people here haven't"
                Text("$them agreed to SMS, so they won't get this.", style = MbType.Caption, color = colors.textSecondary)
            }

            SectionLabel("Message")
            MbTextField(
                field,
                ::setField,
                "Message",
                error = state.error,
                help = state.counter ?: "Keep it short. Links aren't allowed.",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TemplateEngine.BROADCAST_PLACEHOLDERS.forEach { name ->
                    MbActionChip("{$name}", { setField(field.inserting("{$name}")) })
                }
            }
            state.warning?.let { Text(it, style = MbType.Caption, color = colors.dueSoon.content) }

            state.preview?.let { preview ->
                SectionLabel("Preview for ${state.previewName.orEmpty()}")
                SmsBubble(preview)
            }
            state.plan?.let { plan ->
                MbCard(Modifier.fillMaxWidth()) {
                    Text(plan, style = MbType.Body, color = colors.textPrimary)
                }
            }
            PrimaryButton(
                when (state.recipientCount) {
                    0 -> "Nobody to send to"
                    1 -> "Send to 1 member"
                    else -> "Send to ${state.recipientCount} members"
                },
                { step = if (state.picksLeftMembers) SendStep.LEFT_WARNING else SendStep.CONFIRM },
                Modifier.fillMaxWidth(),
                enabled = state.canSend,
            )

            if (state.drafts.isNotEmpty()) {
                SectionLabel("Recent")
                state.drafts.forEach { draft ->
                    MbCard(Modifier.fillMaxWidth(), onClick = { setField(TextFieldValue(draft, TextRange(draft.length))) }) {
                        Text(draft, style = MbType.Body, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    when (step) {
        null -> Unit
        SendStep.LEFT_WARNING -> ConfirmDialog(
            title = "Send to ex-members?",
            message = "Messages to ex-members can be reported as spam and get your number blocked. Send anyway?",
            confirmLabel = "Send anyway",
            danger = true,
            onConfirm = { step = SendStep.CONFIRM },
            onDismiss = { step = null },
        )
        SendStep.CONFIRM -> ConfirmDialog(
            title = if (state.recipientCount == 1) "Send to 1 member?" else "Send to ${state.recipientCount} members?",
            message = state.plan.orEmpty(),
            confirmLabel = "Send",
            onConfirm = {
                step = null
                if (!app.canSendSms()) requestPermission()
                viewModel.send(onSent)
            },
            onDismiss = { step = null },
        )
    }
}
