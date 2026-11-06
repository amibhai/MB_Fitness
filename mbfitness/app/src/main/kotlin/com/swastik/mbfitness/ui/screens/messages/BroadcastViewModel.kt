package com.swastik.mbfitness.ui.screens.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.data.BroadcastEntity
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.MemberRow
import com.swastik.mbfitness.data.MessageRepository
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.AudienceMember
import com.swastik.mbfitness.domain.BroadcastAudience
import com.swastik.mbfitness.domain.BroadcastRules
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.Pacing
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.SmsSegments
import com.swastik.mbfitness.domain.TemplateEngine
import com.swastik.mbfitness.domain.TemplateProblem
import com.swastik.mbfitness.domain.TemplateRules
import com.swastik.mbfitness.domain.TodayProvider
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

fun audienceLabel(audience: BroadcastAudience): String = when (audience) {
    BroadcastAudience.ALL_ACTIVE -> "All active"
    BroadcastAudience.OVERDUE -> "Overdue"
    BroadcastAudience.DUE_SOON -> "Due soon"
    BroadcastAudience.BALANCE_PENDING -> "Balance pending"
    BroadcastAudience.FROZEN -> "Frozen"
    BroadcastAudience.HAND_PICKED -> "Picked"
}

data class BroadcastUiState(
    val loaded: Boolean = false,
    val audiences: List<BroadcastAudience> = emptyList(),
    val audience: BroadcastAudience = BroadcastAudience.ALL_ACTIVE,
    val counts: Map<BroadcastAudience, Int> = emptyMap(),
    val skipRemindersOff: Boolean = false,
    val recipientCount: Int = 0,
    /** In this audience, but they haven't agreed to SMS. */
    val withoutConsent: Int = 0,
    val picksLeftMembers: Boolean = false,
    val previewName: String? = null,
    val preview: String? = null,
    val counter: String? = null,
    val error: String? = null,
    val warning: String? = null,
    val plan: String? = null,
    val quietReason: String? = null,
    val testMode: Boolean = false,
    val drafts: List<String> = emptyList(),
    val canSend: Boolean = false,
)

private data class Inputs(val body: String, val audience: BroadcastAudience, val skipRemindersOff: Boolean)

class BroadcastViewModel(
    private val pickedIds: Set<Long>,
    members: MemberRepository,
    private val messages: MessageRepository,
    private val kicker: OutboxKicker,
    private val settings: SettingsStore,
    private val clock: TodayProvider,
) : ViewModel() {
    private val body = MutableStateFlow("")
    private val audience = MutableStateFlow(if (pickedIds.isEmpty()) BroadcastAudience.ALL_ACTIVE else BroadcastAudience.HAND_PICKED)
    private val skipRemindersOff = MutableStateFlow(false)
    private var outgoing = emptyList<MessageRepository.BroadcastRecipient>()
    private var sent = false

    private val inputs = combine(body, audience, skipRemindersOff) { text, chosen, skip -> Inputs(text, chosen, skip) }

    val uiState: StateFlow<BroadcastUiState> =
        combine(inputs, settings.settings, members.observeMembers(), messages.observeRecentBroadcasts(DRAFTS_KEPT)) { input, current, rows, recent ->
            build(input, current, rows, recent)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BroadcastUiState())

    fun onBodyChange(text: String) = body.update { text }

    fun onAudienceSelected(chosen: BroadcastAudience) = audience.update { chosen }

    fun onSkipRemindersOffChange(skip: Boolean) = skipRemindersOff.update { skip }

    /** One message per person, through the same outbox as everything else (SPEC 8.1). */
    fun send(onDone: (String) -> Unit) {
        if (sent) return
        val recipients = outgoing
        if (recipients.isEmpty()) return
        sent = true
        viewModelScope.launch {
            val plan = uiState.value.plan
            try {
                messages.queueBroadcast(TemplateEngine.straightenQuotes(body.value), audience.value.name, recipients)
            } catch (error: Exception) {
                // Nothing was queued, so sending again has to stay possible.
                sent = false
                throw error
            }
            kicker.kick()
            onDone(plan ?: "Broadcast queued")
        }
    }

    private suspend fun build(input: Inputs, current: AppSettings, rows: List<MemberRow>, recent: List<BroadcastEntity>): BroadcastUiState {
        val today = clock.today()
        val now = clock.nowMillis()
        val everyone = rows.map { AudienceMember(it.id, it.name, it.phone, it.status, it.expiryDate, it.balance, it.smsConsent, it.remindersEnabled) }
        val recipients = BroadcastRules.recipients(input.audience, everyone, pickedIds, today, input.skipRemindersOff)
        val audiences = BroadcastAudience.entries.filter { it != BroadcastAudience.HAND_PICKED || pickedIds.isNotEmpty() }
        val agreedOrNot = BroadcastRules.recipients(input.audience, everyone.map { it.copy(smsConsent = true) }, pickedIds, today, input.skipRemindersOff)
        val trainer = PhoneNumbers.display(current.trainerPhone)
        val rendered = recipients.map { it to BroadcastRules.render(input.body, it.name, current.gymName, trainer, current.packLanguage) }
        val partsEach = rendered.maxOfOrNull { (_, result) -> SmsSegments.count(result.text).parts } ?: 1
        val problems = problems(input.body, partsEach, current)
        val missing = rendered.flatMap { (_, result) -> result.missing }.toSet()
        val hasMessage = input.body.isNotBlank()
        val first = rendered.firstOrNull()
        val limits = Pacing.Limits(current.windowCapParts, current.dailyCapParts, current.reminderHour, current.reminderMinute)
        val plan = if (hasMessage && recipients.isNotEmpty()) {
            val batches = Pacing.planBroadcast(recipients.size, partsEach, now, messages.sendsForPacing(), limits)
            Pacing.describeBroadcast(recipients.size, partsEach, batches, now)
        } else {
            null
        }
        val quiet = messages.quietCalendar(current)
        outgoing = rendered.map { (member, result) -> MessageRepository.BroadcastRecipient(member.id, member.phone, result.text) }
        return BroadcastUiState(
            loaded = true,
            audiences = audiences,
            audience = input.audience,
            counts = audiences.associateWith { BroadcastRules.recipients(it, everyone, pickedIds, today, input.skipRemindersOff).size },
            skipRemindersOff = input.skipRemindersOff,
            recipientCount = recipients.size,
            withoutConsent = agreedOrNot.size - recipients.size,
            picksLeftMembers = input.audience == BroadcastAudience.HAND_PICKED && BroadcastRules.picksLeftMembers(recipients, pickedIds),
            previewName = first?.first?.name,
            preview = first?.second?.text?.takeIf { hasMessage },
            counter = first?.second?.text?.takeIf { hasMessage }?.let { SmsSegments.describe(SmsSegments.count(it)) },
            error = problems.firstOrNull { it != TemplateProblem.Empty }?.let(TemplateRules::describe)
                ?: TemplateRules.describeMissing(missing).takeIf { hasMessage },
            warning = TemplateRules.lengthWarning(partsEach).takeIf { hasMessage },
            plan = plan,
            quietReason = quiet.reason(today).takeIf { quiet.isQuiet(today) },
            testMode = settings.isTestMode(current),
            drafts = recent.map { it.body }.distinct(),
            canSend = hasMessage && recipients.isNotEmpty() && problems.isEmpty() && missing.isEmpty(),
        )
    }

    /** The real longest message decides the length, not the sample values. */
    private fun problems(text: String, partsEach: Int, current: AppSettings): List<TemplateProblem> {
        val found = TemplateEngine.validate(text, TemplateRules.placeholdersFor(MessageKind.BROADCAST), current.packLanguage)
            .filter { it !is TemplateProblem.TooManyParts }
        return if (partsEach > SmsSegments.BLOCK_ABOVE_PARTS) found + TemplateProblem.TooManyParts(partsEach) else found
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val DRAFTS_KEPT = 5
    }
}
