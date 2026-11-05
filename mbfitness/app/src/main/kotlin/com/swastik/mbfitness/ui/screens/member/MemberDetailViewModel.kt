package com.swastik.mbfitness.ui.screens.member

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.ExpiryChange
import com.swastik.mbfitness.data.MemberEntity
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.MessageEntity
import com.swastik.mbfitness.data.PaymentEntity
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.MemberRules
import com.swastik.mbfitness.domain.MemberTab
import com.swastik.mbfitness.domain.TodayProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Aadhaar row: nothing, masked, showing for a moment, or unreadable on this phone. */
sealed interface AadhaarRow {
    data object None : AadhaarRow
    data class Masked(val last4: String) : AadhaarRow
    data class Revealed(val grouped: String, val secondsLeft: Int) : AadhaarRow
    data class Unavailable(val last4: String) : AadhaarRow
}

private data class Reveal(val grouped: String? = null, val secondsLeft: Int = 0, val unavailable: Boolean = false)

data class MemberDetailUiState(
    val loaded: Boolean = false,
    val member: MemberEntity? = null,
    val planName: String = "",
    val tab: MemberTab = MemberTab.PAID,
    val due: Long = 0,
    val advance: Long = 0,
    val payments: List<PaymentEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val aadhaar: AadhaarRow = AadhaarRow.None,
    val pinSet: Boolean = false,
)

class MemberDetailViewModel(
    private val memberId: Long,
    private val members: MemberRepository,
    settings: SettingsStore,
    private val clock: TodayProvider,
) : ViewModel() {
    private val reveal = MutableStateFlow(Reveal())
    private var revealJob: Job? = null

    private val records = combine(
        members.observeMember(memberId),
        members.observePayments(memberId),
        members.observeMessages(memberId),
        members.observePlans(),
    ) { member, payments, messages, plans -> Records(member, payments, messages, plans.associate { it.id to it.name }) }

    val uiState: StateFlow<MemberDetailUiState> =
        combine(records, reveal, settings.settings) { record, shown, current ->
            val member = record.member
            val balance = record.payments.sumOf { it.charged - it.paid }
            MemberDetailUiState(
                loaded = true,
                member = member,
                planName = member?.let { record.planNames[it.planId] }.orEmpty(),
                tab = member?.let { MemberRules.tab(it.status, it.expiryDate, clock.today()) } ?: MemberTab.PAID,
                due = MemberRules.due(balance),
                advance = MemberRules.advance(balance),
                payments = record.payments,
                messages = record.messages.take(MESSAGES_SHOWN),
                aadhaar = aadhaarRow(member?.aadhaarLast4, shown),
                pinSet = current.pinHash != null,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), MemberDetailUiState())

    private data class Records(
        val member: MemberEntity?,
        val payments: List<PaymentEntity>,
        val messages: List<MessageEntity>,
        val planNames: Map<Long, String>,
    )

    private fun aadhaarRow(last4: String?, shown: Reveal): AadhaarRow = when {
        last4 == null -> AadhaarRow.None
        shown.grouped != null -> AadhaarRow.Revealed(shown.grouped, shown.secondsLeft)
        shown.unavailable -> AadhaarRow.Unavailable(last4)
        else -> AadhaarRow.Masked(last4)
    }

    fun freeze() = launch { members.freeze(memberId) }

    fun unfreeze(onDone: (ExpiryChange?) -> Unit) {
        viewModelScope.launch { onDone(members.unfreeze(memberId)) }
    }

    fun markLeft(removeAadhaar: Boolean) = launch {
        hideAadhaar()
        members.markLeft(memberId, removeAadhaar)
    }

    fun rejoin() = launch { members.rejoin(memberId) }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            hideAadhaar()
            members.deleteMember(memberId)
            onDone()
        }
    }

    fun setSmsConsent(agreed: Boolean) = launch { members.setSmsConsent(memberId, agreed) }

    fun setRemindersEnabled(enabled: Boolean) = launch { members.setRemindersEnabled(memberId, enabled) }

    fun stopSms() = launch { members.stopSms(memberId) }

    /** Call only after the PIN or fingerprint check. The number stays in memory, never saved. */
    fun revealAadhaar() {
        revealJob?.cancel()
        revealJob = viewModelScope.launch {
            val aadhaar = members.revealAadhaar(memberId)
            if (aadhaar == null) {
                reveal.value = Reveal(unavailable = true)
                return@launch
            }
            for (left in REVEAL_SECONDS downTo 1) {
                reveal.value = Reveal(aadhaar.revealGrouped(), left)
                delay(MILLIS_PER_SECOND)
            }
            reveal.value = Reveal()
        }
    }

    fun hideAadhaar() {
        revealJob?.cancel()
        reveal.update { Reveal(unavailable = it.unavailable) }
    }

    fun removeAadhaar() = launch {
        revealJob?.cancel()
        reveal.value = Reveal()
        members.removeAadhaar(memberId)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    override fun onCleared() {
        revealJob?.cancel()
        reveal.value = Reveal()
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MESSAGES_SHOWN = 20
        const val REVEAL_SECONDS = 30
        const val MILLIS_PER_SECOND = 1000L
    }
}
