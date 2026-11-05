package com.swastik.mbfitness.ui.screens.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.AppLock
import com.swastik.mbfitness.data.PinCheck
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.PinRules
import com.swastik.mbfitness.domain.TodayProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LockStage { PIN, RECOVERY, NEW_PIN, CONFIRM_PIN }

data class LockUiState(
    val stage: LockStage = LockStage.PIN,
    val entered: String = "",
    val pinLength: Int = PinRules.MIN_DIGITS,
    val message: String? = null,
    val waitSeconds: Long = 0,
    val busy: Boolean = false,
    val biometricEnabled: Boolean = false,
    val recoveryAvailable: Boolean = false,
    /** True once the right PIN went in, for screens that ask for it again. */
    val pinConfirmed: Boolean = false,
)

/** The lock screen's logic: PIN entry, the wrong-guess wait, and "Forgot PIN". */
class LockViewModel(
    private val appLock: AppLock,
    private val settings: SettingsStore,
    private val clock: TodayProvider,
) : ViewModel() {
    private val state = MutableStateFlow(LockUiState())
    val uiState: StateFlow<LockUiState> = state.asStateFlow()

    private var countdown: Job? = null
    private var newPin = ""

    init {
        viewModelScope.launch {
            val current = settings.current()
            state.update {
                it.copy(
                    pinLength = current.pinLength ?: PinRules.MIN_DIGITS,
                    biometricEnabled = current.biometric,
                    recoveryAvailable = current.recoveryCodeHash != null,
                )
            }
            current.pinLockedUntil?.let(::startCountdown)
        }
    }

    fun onDigit(digit: Char) {
        val current = state.value
        if (current.busy || current.waitSeconds > 0 || current.entered.length >= current.expectedLength()) return
        val entered = current.entered + digit
        state.update { it.copy(entered = entered, message = null) }
        when (current.stage) {
            LockStage.PIN -> if (entered.length == current.pinLength) checkPin(entered)
            LockStage.CONFIRM_PIN -> if (entered.length == newPin.length) confirmNewPin(entered)
            LockStage.NEW_PIN, LockStage.RECOVERY -> Unit
        }
    }

    fun onBackspace() {
        state.update { it.copy(entered = it.entered.dropLast(1), message = null) }
    }

    /** The new PIN can be 4 to 6 digits, so choosing one needs an explicit Next. */
    fun onNewPinEntered() {
        val pin = state.value.entered
        if (!PinRules.isValidPin(pin)) {
            state.update { it.copy(message = "A PIN must be ${PinRules.MIN_DIGITS} to ${PinRules.MAX_DIGITS} digits") }
            return
        }
        newPin = pin
        state.update { it.copy(stage = LockStage.CONFIRM_PIN, entered = "", message = "Enter it once more") }
    }

    fun onForgotPin() {
        state.update { it.copy(stage = LockStage.RECOVERY, entered = "", message = null) }
    }

    fun onBackToPin() {
        state.update { it.copy(stage = LockStage.PIN, entered = "", message = null) }
    }

    fun onRecoveryCode(code: String) {
        viewModelScope.launch {
            state.update { it.copy(busy = true, message = null) }
            val correct = appLock.checkRecoveryCode(code)
            state.update {
                if (correct) {
                    it.copy(busy = false, stage = LockStage.NEW_PIN, entered = "", message = "Choose a new PIN")
                } else {
                    it.copy(busy = false, message = "That code doesn't match. Check it and try again.")
                }
            }
        }
    }

    fun onBiometricSuccess() {
        appLock.markUnlocked()
        state.update { it.copy(pinConfirmed = true) }
    }

    /** Ready to ask again, when the same screen needs the PIN a second time. */
    fun reset() {
        state.update { it.copy(stage = LockStage.PIN, entered = "", message = null, pinConfirmed = false) }
    }

    private fun checkPin(pin: String) {
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            when (val result = appLock.checkPin(pin)) {
                PinCheck.Correct, PinCheck.NoPinSet -> {
                    appLock.markUnlocked()
                    state.update { it.copy(busy = false, entered = "", pinConfirmed = true) }
                }
                is PinCheck.Wrong -> {
                    state.update { it.copy(busy = false, entered = "", message = "Wrong PIN. Try again.") }
                    result.lockedUntil?.let(::startCountdown)
                }
                is PinCheck.LockedOut -> {
                    state.update { it.copy(busy = false, entered = "") }
                    startCountdown(result.until)
                }
            }
        }
    }

    private fun confirmNewPin(pin: String) {
        if (pin != newPin) {
            state.update { it.copy(stage = LockStage.NEW_PIN, entered = "", message = "Those didn't match. Choose a new PIN.") }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            appLock.setPin(pin, newRecoveryCode = false)
            appLock.markUnlocked()
            state.update { it.copy(busy = false, entered = "", pinLength = pin.length) }
        }
    }

    private fun startCountdown(until: Long) {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (true) {
                val left = (until - clock.nowMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
                state.update { it.copy(waitSeconds = left.coerceAtLeast(0)) }
                if (left <= 0) break
                delay(MILLIS_PER_SECOND)
            }
        }
    }

    private fun LockUiState.expectedLength(): Int = when (stage) {
        LockStage.PIN -> pinLength
        LockStage.CONFIRM_PIN -> newPin.length
        LockStage.NEW_PIN, LockStage.RECOVERY -> PinRules.MAX_DIGITS
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
