package com.swastik.mbfitness.ui.screens.member

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.FirstPayment
import com.swastik.mbfitness.data.MemberBrief
import com.swastik.mbfitness.data.MemberDraft
import com.swastik.mbfitness.data.MemberEdit
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.MemberSaveResult
import com.swastik.mbfitness.data.PaymentSaved
import com.swastik.mbfitness.data.PlanEntity
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.Aadhaar
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.ScreenRules
import com.swastik.mbfitness.domain.TodayProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PaidToday { FULL, PART, NOT_YET }

sealed interface MemberFormResult {
    data class Saved(
        val memberId: Long,
        val name: String,
        val payment: PaymentSaved?,
        val aadhaarFailed: Boolean,
        /** A number was saved but no app PIN exists yet (SPEC 7.10). */
        val suggestPin: Boolean,
    ) : MemberFormResult
}

data class MemberFormUiState(
    val loaded: Boolean = false,
    val isEdit: Boolean = false,
    val name: String = "",
    val phone: String = "",
    val plans: List<PlanEntity> = emptyList(),
    val planId: Long? = null,
    val joinDate: Long = 0,
    val paidToday: PaidToday = PaidToday.FULL,
    val partAmount: String = "",
    val mode: PaymentMode = PaymentMode.UPI,
    val dob: Long? = null,
    val isMinor: Boolean = false,
    val guardianName: String = "",
    val guardianPhone: String = "",
    /** Held only while the form is open. Never saved anywhere in plain form (hard rule 11). */
    val aadhaarDigits: String = "",
    val aadhaarConsent: Boolean = false,
    val existingAadhaarLast4: String? = null,
    val changingAadhaar: Boolean = false,
    val smsConsent: Boolean = false,
    val notes: String = "",
    val nameError: String? = null,
    val phoneError: String? = null,
    val phoneOwner: MemberBrief? = null,
    val guardianPhoneError: String? = null,
    val aadhaarError: String? = null,
    val aadhaarConsentError: String? = null,
    val duplicateOwner: MemberBrief? = null,
    val saving: Boolean = false,
    val result: MemberFormResult? = null,
) {
    val showAadhaarField: Boolean get() = existingAadhaarLast4 == null || changingAadhaar
    val showAadhaarConsent: Boolean get() = showAadhaarField && aadhaarDigits.isNotEmpty()
    val selectedPlan: PlanEntity? get() = plans.firstOrNull { it.id == planId }
}

/** The form for adding or editing a member (SPEC 10.2 screen 7, 7.10). */
class MemberFormViewModel(
    private val memberId: Long?,
    private val members: MemberRepository,
    private val settings: SettingsStore,
    private val clock: TodayProvider,
) : ViewModel() {
    private val state = MutableStateFlow(MemberFormUiState(joinDate = clock.today(), isEdit = memberId != null))
    val uiState: StateFlow<MemberFormUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val plans = members.observeActivePlans().first()
            val member = memberId?.let { members.member(it) }
            state.update {
                val base = it.copy(loaded = true, plans = plans, planId = plans.firstOrNull()?.id)
                if (member == null) {
                    base
                } else {
                    base.copy(
                        name = member.name,
                        phone = PhoneNumbers.display(member.phone).replace(" ", ""),
                        planId = member.planId,
                        joinDate = member.joinDate,
                        dob = member.dob,
                        isMinor = member.isMinor,
                        guardianName = member.guardianName.orEmpty(),
                        guardianPhone = member.guardianPhone?.let(PhoneNumbers::display)?.replace(" ", "").orEmpty(),
                        existingAadhaarLast4 = member.aadhaarLast4,
                        notes = member.notes,
                        plans = if (plans.any { plan -> plan.id == member.planId }) {
                            plans
                        } else {
                            // A retired plan stays selectable for the member already on it.
                            listOfNotNull(members.plan(member.planId)) + plans
                        },
                    )
                }
            }
        }
    }

    fun onNameChange(text: String) = state.update { it.copy(name = text, nameError = null) }

    fun onPhoneChange(text: String) = state.update { it.copy(phone = text, phoneError = null, phoneOwner = null) }

    fun onPlanSelected(id: Long) = state.update { it.copy(planId = id) }

    fun onJoinDateChange(day: Long) = state.update { it.copy(joinDate = day) }

    fun onPaidTodayChange(paid: PaidToday) = state.update { it.copy(paidToday = paid) }

    fun onPartAmountChange(text: String) = state.update { it.copy(partAmount = ScreenRules.amountInput(text)) }

    fun onModeSelected(mode: PaymentMode) = state.update { it.copy(mode = mode) }

    fun onDobChange(day: Long?) = state.update { it.copy(dob = day) }

    fun onMinorChange(minor: Boolean) = state.update { it.copy(isMinor = minor) }

    fun onGuardianNameChange(text: String) = state.update { it.copy(guardianName = text) }

    fun onGuardianPhoneChange(text: String) = state.update { it.copy(guardianPhone = text, guardianPhoneError = null) }

    fun onSmsConsentChange(agreed: Boolean) = state.update { it.copy(smsConsent = agreed) }

    fun onNotesChange(text: String) = state.update { it.copy(notes = text) }

    fun onContactPicked(contact: PickedContact) = state.update {
        val normalized = PhoneNumbers.normalize(contact.phone)
        it.copy(
            name = it.name.ifBlank { contact.name },
            phone = normalized?.let(PhoneNumbers::display)?.replace(" ", "") ?: contact.phone,
            nameError = null,
            phoneError = null,
            phoneOwner = null,
        )
    }

    fun onAadhaarChange(text: String) {
        val digits = text.filter(Char::isDigit).take(AADHAAR_DIGITS)
        state.update {
            it.copy(
                aadhaarDigits = digits,
                aadhaarError = if (digits.length == AADHAAR_DIGITS) aadhaarProblem(digits) else null,
                aadhaarConsent = it.aadhaarConsent && digits.isNotEmpty(),
                aadhaarConsentError = null,
            )
        }
    }

    fun onAadhaarConsentChange(agreed: Boolean) = state.update { it.copy(aadhaarConsent = agreed, aadhaarConsentError = null) }

    fun onChangeAadhaar() = state.update { it.copy(changingAadhaar = true) }

    fun onDuplicateDismissed() = state.update { it.copy(duplicateOwner = null) }

    fun save(ignoreDuplicateAadhaar: Boolean = false) {
        if (state.value.saving) return
        state.update { it.copy(saving = true, duplicateOwner = null) }
        viewModelScope.launch {
            val done = trySave(ignoreDuplicateAadhaar)
            state.update { it.copy(saving = false, result = done ?: it.result) }
        }
    }

    private suspend fun trySave(ignoreDuplicateAadhaar: Boolean): MemberFormResult? {
        val form = state.value
        val aadhaar = if (form.showAadhaarField && form.aadhaarDigits.isNotEmpty()) {
            when (val parsed = Aadhaar.parse(form.aadhaarDigits)) {
                is Aadhaar.ParseResult.Valid -> parsed.aadhaar
                else -> {
                    state.update { it.copy(aadhaarError = AADHAAR_INVALID) }
                    return null
                }
            }
        } else {
            null
        }
        if (!validateFields(form, aadhaar)) return null
        if (aadhaar != null && !ignoreDuplicateAadhaar) {
            members.findAadhaarOwner(aadhaar, exceptMemberId = memberId ?: 0)?.let { owner ->
                state.update { it.copy(duplicateOwner = owner) }
                return null
            }
        }
        val pinSet = settings.current().pinHash != null
        return if (memberId == null) add(form, aadhaar, pinSet) else edit(memberId, form, aadhaar, pinSet)
    }

    private fun validateFields(form: MemberFormUiState, aadhaar: Aadhaar?): Boolean {
        val nameError = if (form.name.isBlank()) "Enter the member's name" else null
        val phoneError = if (PhoneNumbers.normalize(form.phone) == null) "Number must be 10 digits" else null
        val guardianError = if (form.isMinor && form.guardianPhone.isNotBlank() && PhoneNumbers.normalize(form.guardianPhone) == null) {
            "Number must be 10 digits"
        } else {
            null
        }
        val consentError = if (aadhaar != null && !form.aadhaarConsent) "Tick this to save the Aadhaar number" else null
        state.update {
            it.copy(nameError = nameError, phoneError = phoneError, guardianPhoneError = guardianError, aadhaarConsentError = consentError)
        }
        return nameError == null && phoneError == null && guardianError == null && consentError == null
    }

    private suspend fun add(form: MemberFormUiState, aadhaar: Aadhaar?, pinSet: Boolean): MemberFormResult? {
        val plan = form.selectedPlan ?: return null
        val firstPayment = when (form.paidToday) {
            PaidToday.FULL -> FirstPayment(plan.price, form.mode)
            PaidToday.PART -> FirstPayment(ScreenRules.parseAmount(form.partAmount) ?: 0, form.mode)
            PaidToday.NOT_YET -> null
        }
        val draft = MemberDraft(
            name = form.name,
            phone = form.phone,
            planId = plan.id,
            joinDate = form.joinDate,
            dob = form.dob,
            notes = form.notes,
            smsConsent = form.smsConsent,
            isMinor = form.isMinor,
            guardianName = form.guardianName,
            guardianPhone = form.guardianPhone,
            aadhaar = aadhaar,
            aadhaarConsent = form.aadhaarConsent,
            firstPayment = firstPayment,
        )
        return when (val result = members.addMember(draft)) {
            is MemberSaveResult.Saved -> MemberFormResult.Saved(
                memberId = result.memberId,
                name = form.name.trim(),
                payment = result.payment,
                aadhaarFailed = !result.aadhaarStored,
                suggestPin = aadhaar != null && result.aadhaarStored && !pinSet,
            )
            else -> {
                showSaveProblem(result)
                null
            }
        }
    }

    private suspend fun edit(id: Long, form: MemberFormUiState, aadhaar: Aadhaar?, pinSet: Boolean): MemberFormResult? {
        val edit = MemberEdit(
            name = form.name,
            phone = form.phone,
            planId = form.planId ?: return null,
            joinDate = form.joinDate,
            dob = form.dob,
            notes = form.notes,
            isMinor = form.isMinor,
            guardianName = form.guardianName,
            guardianPhone = form.guardianPhone,
        )
        val result = members.updateMember(id, edit)
        if (result !is MemberSaveResult.Saved) {
            showSaveProblem(result)
            return null
        }
        val stored = aadhaar?.let { members.setAadhaar(id, it) }
        return MemberFormResult.Saved(
            memberId = id,
            name = form.name.trim(),
            payment = null,
            aadhaarFailed = stored == false,
            suggestPin = stored == true && !pinSet,
        )
    }

    private fun showSaveProblem(result: MemberSaveResult) {
        when (result) {
            MemberSaveResult.NameMissing -> state.update { it.copy(nameError = "Enter the member's name") }
            MemberSaveResult.PhoneInvalid -> state.update { it.copy(phoneError = "Number must be 10 digits") }
            is MemberSaveResult.PhoneTaken -> state.update {
                it.copy(phoneError = "This number belongs to ${result.existing.name}", phoneOwner = result.existing)
            }
            MemberSaveResult.AadhaarConsentMissing -> state.update { it.copy(aadhaarConsentError = "Tick this to save the Aadhaar number") }
            is MemberSaveResult.Saved -> Unit
        }
    }

    private fun aadhaarProblem(digits: String): String? =
        if (Aadhaar.parse(digits) is Aadhaar.ParseResult.Valid) null else AADHAAR_INVALID

    private companion object {
        const val AADHAAR_DIGITS = 12
        const val AADHAAR_INVALID = "Aadhaar number is not valid. Please check the digits."
    }
}
