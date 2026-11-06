package com.swastik.mbfitness.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.App
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MessageLabels
import com.swastik.mbfitness.domain.PackLanguage
import com.swastik.mbfitness.domain.TemplateEngine
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.ConfirmDialog
import com.swastik.mbfitness.ui.components.LinkButton
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.MbActionChip
import com.swastik.mbfitness.ui.components.MbChip
import com.swastik.mbfitness.ui.components.MbSegmented
import com.swastik.mbfitness.ui.components.MbSwitchRow
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.PrimaryButton
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.components.SmsBubble
import com.swastik.mbfitness.ui.components.inserting
import com.swastik.mbfitness.ui.components.rememberSmsPermissionRequest
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

private fun onlyDigits(text: String): String = text.filter { it.isDigit() }.take(3)

/** SPEC 10.2 screen 13. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplatesScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val viewModel = appViewModel { container -> TemplatesViewModel(container.messages, container.outboxKicker, container.settings) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val colors = MbTheme.colors
    var leaving by remember { mutableStateOf(false) }
    val requestPermission = rememberSmsPermissionRequest()

    val tryToLeave = { if (state.hasUnsavedAnywhere) leaving = true else onBack() }
    BackHandler(state.hasUnsavedAnywhere) { leaving = true }

    // Typing edits this directly. It reloads when another template is picked or the saved text changes.
    var field by remember(state.loaded, state.selected, state.savedBody) {
        mutableStateOf(TextFieldValue(state.draft.body, TextRange(state.draft.body.length)))
    }

    fun setField(value: TextFieldValue) {
        // Same length, so the cursor stays put. Curly quotes silently force Unicode (SPEC 8.7).
        field = value.copy(text = TemplateEngine.straightenQuotes(value.text))
        viewModel.onEdit { it.copy(body = field.text) }
    }

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Templates", onBack = tryToLeave)
        if (!state.loaded) return@Column
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            SectionLabel("Language")
            Row(verticalAlignment = Alignment.CenterVertically) {
                MbSegmented(
                    PackLanguage.entries,
                    state.language,
                    ::languageName,
                    viewModel::onLanguageChosen,
                    Modifier.weight(1f),
                )
                LinkButton("Reset", viewModel::onResetChosen)
            }

            SectionLabel("Message")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MessageKind.TEMPLATED.forEach { kind ->
                    MbChip(MessageLabels.kind(kind), state.selected == kind, { viewModel.onSelect(kind) })
                }
            }
            MbSwitchRow(
                state.draft.enabled,
                { on -> viewModel.onEdit { it.copy(enabled = on) } },
                "Send this message",
                help = state.schedule.takeIf { it.isNotEmpty() },
            )
            ScheduleFields(state, viewModel)

            MbTextField(
                field,
                ::setField,
                "Wording",
                error = state.bodyError,
                help = state.counter,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.placeholders.forEach { name ->
                    MbActionChip("{$name}", { setField(field.inserting("{$name}")) })
                }
            }
            if ("[[" in state.draft.body) {
                Text("Words inside [[ ]] only go when a balance is owed.", style = MbType.Caption, color = colors.textSecondary)
            }
            state.warning?.let { Text(it, style = MbType.Caption, color = colors.dueSoon.content) }
            state.cantGoNote?.let { Text(it, style = MbType.Caption, color = colors.danger) }

            SectionLabel("Preview")
            SmsBubble(state.preview)
            PrimaryButton("Save", { viewModel.save { snackbar.show(it) } }, Modifier.fillMaxWidth(), enabled = state.canSave)
            SecondaryButton(
                "Send test to me",
                {
                    if (!app.canSendSms()) requestPermission()
                    viewModel.sendTest { snackbar.show(it) }
                },
                Modifier.fillMaxWidth(),
            )
        }
    }

    state.prompt?.let { prompt -> PackDialog(prompt, state.language, viewModel) }
    if (leaving) {
        ConfirmDialog(
            title = "Leave without saving?",
            message = "Your changes to the wording will be lost.",
            confirmLabel = "Leave",
            danger = true,
            dismissLabel = "Keep editing",
            onConfirm = {
                leaving = false
                onBack()
            },
            onDismiss = { leaving = false },
        )
    }
}

@Composable
private fun ScheduleFields(state: TemplatesUiState, viewModel: TemplatesViewModel) {
    val label = state.offsetLabel ?: return
    MbTextField(
        state.draft.offsetDays,
        { text -> viewModel.onEdit { it.copy(offsetDays = onlyDigits(text)) } },
        label,
        error = state.scheduleError.takeIf { !state.hasRepeat },
        keyboardType = KeyboardType.Number,
    )
    if (state.hasRepeat) {
        Row(horizontalArrangement = Arrangement.spacedBy(ListGap)) {
            MbTextField(
                state.draft.repeatEvery,
                { text -> viewModel.onEdit { it.copy(repeatEvery = onlyDigits(text)) } },
                "Repeat every (days)",
                Modifier.weight(1f),
                keyboardType = KeyboardType.Number,
            )
            MbTextField(
                state.draft.maxRepeats,
                { text -> viewModel.onEdit { it.copy(maxRepeats = onlyDigits(text)) } },
                "At most (times)",
                Modifier.weight(1f),
                keyboardType = KeyboardType.Number,
            )
        }
        state.scheduleError?.let { Text(it, style = MbType.Caption, color = MbTheme.colors.danger) }
    }
}

@Composable
private fun PackDialog(prompt: PackPrompt, language: PackLanguage, viewModel: TemplatesViewModel) {
    val snackbar = LocalSnackbar.current
    when (prompt) {
        is PackPrompt.Switch -> ConfirmDialog(
            title = "Switch to ${languageName(prompt.language)}?",
            message = "All 7 messages change to the ${languageName(prompt.language)} wording. Anything you've reworded is replaced. What's on or off, and when they send, stay as they are.",
            confirmLabel = "Switch",
            onConfirm = { viewModel.confirmPrompt { snackbar.show(it) } },
            onDismiss = viewModel::dismissPrompt,
        )
        PackPrompt.Reset -> ConfirmDialog(
            title = "Reset to the ${languageName(language)} pack?",
            message = "Wording and timing go back to how they came, and all 7 messages are switched on.",
            confirmLabel = "Reset",
            danger = true,
            onConfirm = { viewModel.confirmPrompt { snackbar.show(it) } },
            onDismiss = viewModel::dismissPrompt,
        )
    }
}
