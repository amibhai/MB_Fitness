package com.swastik.mbfitness.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.data.MessageRepository
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.PackLanguage
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.RenderResult
import com.swastik.mbfitness.domain.SmsSegments
import com.swastik.mbfitness.domain.TemplateConfig
import com.swastik.mbfitness.domain.TemplateEngine
import com.swastik.mbfitness.domain.TemplateRules
import com.swastik.mbfitness.work.OutboxKicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Unsaved changes to one template. The numbers stay text while they're being typed. */
data class TemplateDraft(
    val body: String,
    val enabled: Boolean,
    val offsetDays: String,
    val repeatEvery: String,
    val maxRepeats: String,
)

sealed interface PackPrompt {
    data class Switch(val language: PackLanguage) : PackPrompt
    data object Reset : PackPrompt
}

data class TemplatesUiState(
    val loaded: Boolean = false,
    val language: PackLanguage = PackLanguage.ENGLISH,
    val selected: MessageKind = MessageKind.BEFORE_DUE,
    /** What's saved for this one. When it changes from outside, the editor reloads. */
    val savedBody: String = "",
    val draft: TemplateDraft = TemplateDraft("", true, "", "", ""),
    val offsetLabel: String? = null,
    val hasRepeat: Boolean = false,
    val schedule: String = "",
    val scheduleError: String? = null,
    val placeholders: List<String> = emptyList(),
    val preview: String = "",
    val counter: String = "",
    val bodyError: String? = null,
    val warning: String? = null,
    val cantGoNote: String? = null,
    val canSave: Boolean = false,
    val hasUnsavedHere: Boolean = false,
    val hasUnsavedAnywhere: Boolean = false,
    val prompt: PackPrompt? = null,
)

private data class Editing(
    val selected: MessageKind,
    val edits: Map<MessageKind, TemplateDraft>,
    val prompt: PackPrompt?,
)

class TemplatesViewModel(
    private val messages: MessageRepository,
    private val kicker: OutboxKicker,
    settings: SettingsStore,
) : ViewModel() {
    private val selected = MutableStateFlow(MessageKind.BEFORE_DUE)
    private val edits = MutableStateFlow(emptyMap<MessageKind, TemplateDraft>())
    private val prompt = MutableStateFlow<PackPrompt?>(null)

    private val editing = combine(selected, edits, prompt) { kind, drafts, asking -> Editing(kind, drafts, asking) }

    val uiState: StateFlow<TemplatesUiState> =
        combine(editing, messages.observeTemplates(), settings.settings) { edit, templates, current ->
            build(edit, templates.associateBy { it.kind }, current)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TemplatesUiState())

    fun onSelect(kind: MessageKind) = selected.update { kind }

    fun onEdit(change: (TemplateDraft) -> TemplateDraft) {
        val state = uiState.value
        if (!state.loaded) return
        edits.update { it + (state.selected to change(state.draft)) }
    }

    fun onLanguageChosen(language: PackLanguage) {
        if (language != uiState.value.language) prompt.value = PackPrompt.Switch(language)
    }

    fun onResetChosen() {
        prompt.value = PackPrompt.Reset
    }

    fun dismissPrompt() {
        prompt.value = null
    }

    fun confirmPrompt(onDone: (String) -> Unit) {
        val asking = prompt.value ?: return
        prompt.value = null
        viewModelScope.launch {
            edits.value = emptyMap()
            when (asking) {
                is PackPrompt.Switch -> {
                    messages.switchPack(asking.language)
                    onDone("Switched to ${languageName(asking.language)}")
                }
                PackPrompt.Reset -> {
                    messages.resetPack(uiState.value.language)
                    onDone("Reset")
                }
            }
        }
    }

    fun save(onDone: (String) -> Unit) {
        val state = uiState.value
        if (!state.canSave) return
        viewModelScope.launch {
            messages.saveTemplate(state.draft.toConfig(state.selected))
            edits.update { it - state.selected }
            onDone("Saved")
        }
    }

    /** Sends the preview to the owner's own number, even before it's saved (SPEC 10.2 screen 13). */
    fun sendTest(onDone: (String) -> Unit) {
        val state = uiState.value
        if (state.cantGoNote != null) {
            onDone(state.cantGoNote)
            return
        }
        viewModelScope.launch {
            val id = messages.queueTest(state.preview)
            if (id == null) {
                onDone("Add your phone number in Settings first")
            } else {
                kicker.kick()
                onDone("Test SMS is on its way to your number")
            }
        }
    }

    private fun build(edit: Editing, templates: Map<MessageKind, TemplateConfig>, current: AppSettings): TemplatesUiState {
        val kind = edit.selected
        val saved = templates[kind] ?: return TemplatesUiState(selected = kind, prompt = edit.prompt)
        val draft = edit.edits[kind] ?: saved.toDraft()
        val config = draft.toConfig(kind)
        val rendered = renderPreview(draft.body, current)
        val parts = SmsSegments.count(rendered.text)
        val problems = TemplateEngine.validate(draft.body, TemplateRules.placeholdersFor(kind), current.packLanguage)
        val scheduleError = TemplateRules.scheduleProblem(kind, config.offsetDays, config.repeatEveryDays, config.maxRepeats)
        val unsavedHere = config != saved
        return TemplatesUiState(
            loaded = true,
            language = current.packLanguage,
            selected = kind,
            savedBody = saved.body,
            draft = draft,
            offsetLabel = offsetLabel(kind),
            hasRepeat = TemplateRules.hasRepeat(kind),
            schedule = if (scheduleError == null) TemplateEngine.describeSchedule(config) else "",
            scheduleError = scheduleError,
            placeholders = TemplateEngine.ALL_PLACEHOLDERS.filter { it in TemplateRules.placeholdersFor(kind) },
            preview = rendered.text,
            counter = SmsSegments.describe(parts),
            bodyError = problems.firstOrNull()?.let(TemplateRules::describe),
            warning = TemplateRules.lengthWarning(parts.parts),
            cantGoNote = TemplateRules.describeMissing(rendered.missing),
            canSave = problems.isEmpty() && scheduleError == null && unsavedHere,
            hasUnsavedHere = unsavedHere,
            hasUnsavedAnywhere = edit.edits.any { (key, value) -> templates[key]?.toDraft() != value },
            prompt = edit.prompt,
        )
    }

    /** Sample member, real gym details: the same blanks that would hold the real SMS back show here. */
    private fun renderPreview(body: String, current: AppSettings): RenderResult = TemplateEngine.render(
        body,
        TemplateEngine.sampleValues().copy(
            gymName = current.gymName,
            upiId = current.upiId,
            trainerPhone = PhoneNumbers.display(current.trainerPhone),
        ),
        current.packLanguage,
    )

    private fun TemplateConfig.toDraft() = TemplateDraft(
        body = body,
        enabled = enabled,
        offsetDays = abs(offsetDays).toString(),
        repeatEvery = repeatEveryDays?.toString().orEmpty(),
        maxRepeats = maxRepeats?.toString().orEmpty(),
    )

    private fun TemplateDraft.toConfig(kind: MessageKind): TemplateConfig {
        val days = offsetDays.toIntOrNull() ?: 0
        return TemplateConfig(
            kind = kind,
            body = body,
            enabled = enabled,
            offsetDays = when {
                !TemplateRules.hasOffset(kind) -> 0
                kind == MessageKind.BEFORE_DUE -> -days
                else -> days
            },
            repeatEveryDays = repeatEvery.toIntOrNull().takeIf { TemplateRules.hasRepeat(kind) },
            maxRepeats = maxRepeats.toIntOrNull().takeIf { TemplateRules.hasRepeat(kind) },
        )
    }

    private fun offsetLabel(kind: MessageKind): String? = when (kind) {
        MessageKind.BEFORE_DUE -> "Days before expiry"
        MessageKind.OVERDUE_1, MessageKind.OVERDUE_2 -> "Days after expiry"
        MessageKind.BALANCE_DUE -> "Days after a part payment"
        else -> null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

fun languageName(language: PackLanguage): String = when (language) {
    PackLanguage.ENGLISH -> "English"
    PackLanguage.HINGLISH -> "Hinglish"
}
