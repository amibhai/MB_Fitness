package com.swastik.mbfitness.ui.screens.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.MessageRepository
import com.swastik.mbfitness.data.MessageWithMember
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MessageLabels
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.TemplateRules
import com.swastik.mbfitness.domain.TodayProvider
import com.swastik.mbfitness.work.DailyRunner
import com.swastik.mbfitness.work.OutboxKicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MessagesTab(val label: String, val statuses: List<MessageStatus>) {
    QUEUED("Queued", listOf(MessageStatus.QUEUED, MessageStatus.SENDING)),
    SENT("Sent", listOf(MessageStatus.SENT, MessageStatus.DELIVERED)),
    FAILED("Failed", listOf(MessageStatus.FAILED)),
}

data class MessageItem(
    val id: Long,
    val who: String,
    val kind: String,
    val body: String,
    val status: String,
    val isFailed: Boolean,
    val canCancel: Boolean,
)

sealed interface MessageEntry {
    data class Single(val item: MessageItem) : MessageEntry

    /** Everyone in one broadcast, folded into one card. */
    data class Group(val broadcastId: Long, val items: List<MessageItem>, val expanded: Boolean) : MessageEntry
}

/** "Run reminders now" asks first (SPEC 8.3). */
sealed interface RunPrompt {
    data class QuietDay(val reason: String) : RunPrompt

    data class Preview(
        val ignoreQuietDay: Boolean,
        val count: Int,
        val lines: List<String>,
        val heldNote: String?,
    ) : RunPrompt
}

data class MessagesUiState(
    val loaded: Boolean = false,
    val tab: MessagesTab = MessagesTab.QUEUED,
    val counts: Map<MessagesTab, Int> = emptyMap(),
    val entries: List<MessageEntry> = emptyList(),
    val prompt: RunPrompt? = null,
    val testMode: Boolean = false,
)

private data class Choices(val expanded: Set<Long>, val prompt: RunPrompt?, val testMode: Boolean)

class MessagesViewModel(
    private val messages: MessageRepository,
    private val members: MemberRepository,
    private val runner: DailyRunner,
    private val kicker: OutboxKicker,
    private val settings: SettingsStore,
    private val clock: TodayProvider,
) : ViewModel() {
    private val tab = MutableStateFlow(MessagesTab.QUEUED)
    private val expanded = MutableStateFlow(emptySet<Long>())
    private val prompt = MutableStateFlow<RunPrompt?>(null)
    private val tick = MutableStateFlow(0)

    private val counts = combine(messages.observeQueuedCount(), messages.observeSentCount(), messages.observeFailedCount()) { queued, sent, failed ->
        mapOf(MessagesTab.QUEUED to queued, MessagesTab.SENT to sent, MessagesTab.FAILED to failed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rows = tab.flatMapLatest { current -> messages.observeWithStatus(current.statuses).map { current to it } }

    private val choices = combine(expanded, prompt, settings.settings) { open, asking, current ->
        Choices(open, asking, settings.isTestMode(current))
    }

    val uiState: StateFlow<MessagesUiState> =
        combine(rows, counts, choices, tick) { (current, list), counted, chosen, _ ->
            MessagesUiState(
                loaded = true,
                tab = current,
                counts = counted,
                entries = toEntries(if (current == MessagesTab.QUEUED) list.sortedForSending() else list, chosen.expanded),
                prompt = chosen.prompt,
                testMode = chosen.testMode,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), MessagesUiState())

    init {
        // A failure is what needs looking at, so that's what opens first.
        viewModelScope.launch {
            if (messages.observeFailedCount().first() > 0) tab.value = MessagesTab.FAILED
        }
    }

    fun onTabSelected(selected: MessagesTab) = tab.update { selected }

    /** "Goes at 10:05 AM" goes stale while the app is away. */
    fun onResume() = tick.update { it + 1 }

    fun onToggleGroup(broadcastId: Long) = expanded.update { if (broadcastId in it) it - broadcastId else it + broadcastId }

    fun retry(id: Long, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val text = when (messages.retry(id)) {
                MessageRepository.RetryResult.SENDING_AGAIN -> "Trying again"
                MessageRepository.RetryResult.REWORDED -> "Trying again, with today's wording"
                MessageRepository.RetryResult.NOT_DUE -> "That reminder is out of date, and nothing is due today"
            }
            kicker.kick()
            onResult(text)
        }
    }

    fun cancel(id: Long) {
        viewModelScope.launch { messages.cancel(id) }
    }

    fun retryGroup(broadcastId: Long, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val count = messages.retryBroadcast(broadcastId)
            kicker.kick()
            onResult(if (count == 1) "Trying 1 again" else "Trying $count again")
        }
    }

    fun cancelGroup(broadcastId: Long, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val count = messages.cancelBroadcast(broadcastId)
            onResult(if (count == 1) "Cancelled 1 message" else "Cancelled $count messages")
        }
    }

    /** A quiet day asks first, then everything shows what would go before anything does. */
    fun onRunReminders(onNothing: (String) -> Unit) {
        viewModelScope.launch {
            val quiet = messages.quietCalendar(settings.current())
            val today = clock.today()
            if (quiet.isQuiet(today)) {
                prompt.value = RunPrompt.QuietDay(quiet.reason(today).orEmpty())
            } else {
                showPreview(ignoreQuietDay = false, onNothing)
            }
        }
    }

    fun onRunOnQuietDay(onNothing: (String) -> Unit) {
        viewModelScope.launch { showPreview(ignoreQuietDay = true, onNothing) }
    }

    private suspend fun showPreview(ignoreQuietDay: Boolean, onNothing: (String) -> Unit) {
        val plan = messages.previewPlan(ignoreQuietDay)
        val heldNote = TemplateRules.describeMissing(plan.held.flatMap { it.missing }.toSet())
        if (plan.messages.isEmpty()) {
            prompt.value = null
            onNothing(heldNote ?: "No reminders are due right now")
            return
        }
        val names = members.observeMembers().first().associate { it.id to it.name }
        prompt.value = RunPrompt.Preview(
            ignoreQuietDay,
            plan.messages.size,
            plan.messages.take(PREVIEW_LINES).map { "${names[it.memberId].orEmpty()} · ${MessageLabels.kind(it.kind)}" },
            heldNote,
        )
    }

    fun onConfirmRun(onDone: (String) -> Unit) {
        val preview = prompt.value as? RunPrompt.Preview ?: return
        prompt.value = null
        viewModelScope.launch {
            val plan = runner.run(preview.ignoreQuietDay).plan
            onDone(
                when {
                    plan == null -> "Couldn't run reminders. Try again in a little while."
                    plan.messages.size == 1 -> "1 reminder is on its way"
                    else -> "${plan.messages.size} reminders are on their way"
                },
            )
        }
    }

    fun dismissPrompt() {
        prompt.value = null
    }

    private fun List<MessageWithMember>.sortedForSending() = sortedWith(
        compareBy(
            { it.message.status != MessageStatus.SENDING },
            { it.message.priority },
            { it.message.createdAt },
            { it.message.id },
        ),
    )

    private fun toEntries(rows: List<MessageWithMember>, open: Set<Long>): List<MessageEntry> {
        val now = clock.nowMillis()
        val groups = rows.filter { it.message.broadcastId != null }.groupBy { it.message.broadcastId }
        val shown = mutableSetOf<Long>()
        return rows.mapNotNull { row ->
            val broadcastId = row.message.broadcastId
            when {
                broadcastId == null -> MessageEntry.Single(row.toItem(now))
                shown.add(broadcastId) -> MessageEntry.Group(broadcastId, groups.getValue(broadcastId).map { it.toItem(now) }, broadcastId in open)
                else -> null
            }
        }
    }

    private fun MessageWithMember.toItem(now: Long): MessageItem {
        val row = message
        return MessageItem(
            id = row.id,
            who = memberName ?: if (row.kind == MessageKind.TEST) "You" else PhoneNumbers.display(row.phone),
            kind = MessageLabels.kind(row.kind),
            body = row.body,
            status = MessageLabels.status(row.status, row.notBefore, row.sentAt, row.deliveredAt, row.lastError, now),
            isFailed = row.status == MessageStatus.FAILED,
            canCancel = row.status == MessageStatus.QUEUED || row.status == MessageStatus.FAILED,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val PREVIEW_LINES = 8
    }
}
