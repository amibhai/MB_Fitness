package com.swastik.mbfitness.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.App
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.domain.OnboardingRules
import com.swastik.mbfitness.domain.OnboardingStep
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.SettingsRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GymDetails(
    val ownerName: String = "",
    val gymName: String = "",
    val trainerPhone: String = "",
    val upiId: String = "",
    val payeeName: String = "",
    val tried: Boolean = false,
) {
    val ownerProblem get() = SettingsRules.requiredProblem(ownerName, "Owner name")
    val gymProblem get() = SettingsRules.requiredProblem(gymName, "Gym name")
    val phoneProblem get() = SettingsRules.trainerPhoneProblem(trainerPhone)
    val upiProblem get() = SettingsRules.upiProblem(upiId)
    val isValid get() = listOf(ownerProblem, gymProblem, phoneProblem, upiProblem).all { it == null }
}

data class OnboardingUiState(
    val loaded: Boolean = false,
    val steps: List<OnboardingStep> = emptyList(),
    val index: Int = 0,
    val gym: GymDetails = GymDetails(),
    /** SEND_SMS came back refused at once, so Android's restricted settings need a visit. */
    val smsRestricted: Boolean = false,
    val smsAllowed: Boolean = false,
    val capTip: Boolean = false,
    val testSent: Boolean = false,
    val testError: String? = null,
    val testModeForced: Boolean = false,
    val settings: AppSettings = AppSettings(),
) {
    val step: OnboardingStep get() = steps.getOrElse(index) { OnboardingStep.WELCOME }
}

/** The first-run flow (SPEC 11.1). Nothing is required: every step can be skipped. */
class OnboardingViewModel(private val app: App, simSlots: Int, sdk: Int) : ViewModel() {
    val uiState = MutableStateFlow(OnboardingUiState(steps = OnboardingRules.steps(simSlots, sdk)))

    init {
        viewModelScope.launch {
            val current = app.settings.current()
            uiState.update {
                it.copy(
                    loaded = true,
                    settings = current,
                    smsAllowed = app.canSendSms(),
                    testModeForced = app.settings.isTestMode(AppSettings(testMode = false)),
                    gym = GymDetails(
                        ownerName = current.ownerName,
                        gymName = current.gymName,
                        trainerPhone = PhoneNumbers.display(current.trainerPhone).filter { char -> char.isDigit() },
                        upiId = current.upiId,
                        payeeName = current.upiPayeeName,
                    ),
                )
            }
        }
        viewModelScope.launch {
            app.settings.settings.collect { current -> uiState.update { it.copy(settings = current) } }
        }
    }

    fun next() {
        uiState.update { it.copy(index = (it.index + 1).coerceAtMost(it.steps.lastIndex)) }
    }

    fun back() {
        uiState.update { it.copy(index = (it.index - 1).coerceAtLeast(0)) }
    }

    fun onGymChange(change: (GymDetails) -> GymDetails) = uiState.update { it.copy(gym = change(it.gym)) }

    /** Moves on only with good details. Skip moves on without saving them. */
    fun saveGym() {
        val gym = uiState.value.gym
        if (!gym.isValid) {
            uiState.update { it.copy(gym = gym.copy(tried = true)) }
            return
        }
        viewModelScope.launch {
            app.settings.update {
                it.copy(
                    ownerName = gym.ownerName.trim(),
                    gymName = gym.gymName.trim(),
                    trainerPhone = PhoneNumbers.normalize(gym.trainerPhone).orEmpty(),
                    upiId = gym.upiId.trim(),
                    upiPayeeName = gym.payeeName.trim().ifEmpty { gym.ownerName.trim() },
                )
            }
            next()
        }
    }

    fun onSmsResult(granted: Boolean, restricted: Boolean) {
        uiState.update { it.copy(smsAllowed = granted, smsRestricted = restricted && !granted) }
        if (granted) next()
    }

    fun refreshSms() = uiState.update { it.copy(smsAllowed = app.canSendSms(), smsRestricted = it.smsRestricted && !app.canSendSms()) }

    /** A dual-SIM phone with one SIM in it has nothing to choose. The next step moves up. */
    fun dropSimStep() = uiState.update { it.copy(steps = it.steps - OnboardingStep.SIM) }

    fun chooseSim(id: Int?) {
        viewModelScope.launch {
            app.settings.update { it.copy(simSubscriptionId = id) }
            next()
        }
    }

    fun onPlanAnswer(hasFreeSms: Boolean) {
        viewModelScope.launch {
            app.settings.update { it.copy(dailyCapParts = OnboardingRules.capForPlan(hasFreeSms)) }
            if (hasFreeSms) next() else uiState.update { it.copy(capTip = true) }
        }
    }

    fun onFolderChosen(uri: String) {
        viewModelScope.launch {
            app.settings.update { it.copy(backupFolderUri = uri) }
            next()
        }
    }

    /**
     * One SMS to the owner's own number. SPEC 11.1 turns Test mode on for this, but a test SMS
     * only ever goes to that number anyway, and skipping this step would have left it on, with
     * members silently getting nothing (docs/DECISIONS.md).
     */
    fun sendTest() {
        viewModelScope.launch {
            val current = app.settings.current()
            if (current.trainerPhone.isBlank()) {
                uiState.update { it.copy(testError = "Go back and add your phone number first.") }
                return@launch
            }
            app.messages.queueTest("Test from ${current.gymName}. If you got this, SMS reminders work.")
            app.outboxKicker.kick()
            uiState.update { it.copy(testSent = true, testError = null) }
        }
    }

    fun finish(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val now = app.clock.nowMillis()
            app.settings.update { it.copy(onboardingDone = true, setupDoneAt = it.setupDoneAt ?: now, settingsReset = false) }
            onDone()
        }
    }
}
