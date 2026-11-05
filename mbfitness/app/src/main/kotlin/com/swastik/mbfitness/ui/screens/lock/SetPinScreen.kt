package com.swastik.mbfitness.ui.screens.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.AppLock
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.PinRules
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.PinDots
import com.swastik.mbfitness.ui.components.PinPad
import com.swastik.mbfitness.ui.components.PrimaryButton
import com.swastik.mbfitness.ui.components.SecureWindow
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetPinStage { ENTER, CONFIRM, SHOW_CODE, DONE }

data class SetPinUiState(
    val stage: SetPinStage = SetPinStage.ENTER,
    val entered: String = "",
    val message: String? = null,
    val recoveryCode: String? = null,
    val busy: Boolean = false,
)

/** Choosing a PIN: enter, confirm, then write down the recovery code (SPEC 11.1 step 11). */
class SetPinViewModel(private val appLock: AppLock, private val settings: SettingsStore) : ViewModel() {
    private val state = MutableStateFlow(SetPinUiState())
    val uiState: StateFlow<SetPinUiState> = state.asStateFlow()
    private var firstEntry = ""

    fun onDigit(digit: Char) {
        val current = state.value
        val limit = if (current.stage == SetPinStage.CONFIRM) firstEntry.length else PinRules.MAX_DIGITS
        if (current.busy || current.entered.length >= limit) return
        val entered = current.entered + digit
        state.update { it.copy(entered = entered, message = null) }
        if (current.stage == SetPinStage.CONFIRM && entered.length == firstEntry.length) confirm(entered)
    }

    fun onBackspace() {
        state.update { it.copy(entered = it.entered.dropLast(1), message = null) }
    }

    fun onNext() {
        val pin = state.value.entered
        if (!PinRules.isValidPin(pin)) {
            state.update { it.copy(message = "A PIN must be ${PinRules.MIN_DIGITS} to ${PinRules.MAX_DIGITS} digits") }
            return
        }
        firstEntry = pin
        state.update { it.copy(stage = SetPinStage.CONFIRM, entered = "", message = null) }
    }

    fun onCodeWrittenDown() {
        state.update { it.copy(stage = SetPinStage.DONE, recoveryCode = null) }
    }

    private fun confirm(pin: String) {
        if (pin != firstEntry) {
            firstEntry = ""
            state.update { it.copy(stage = SetPinStage.ENTER, entered = "", message = "Those didn't match. Try again.") }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            val needsCode = settings.current().recoveryCodeHash == null
            val code = appLock.setPin(pin, newRecoveryCode = needsCode)
            appLock.markUnlocked()
            state.update {
                it.copy(
                    busy = false,
                    entered = "",
                    recoveryCode = code,
                    stage = if (code != null) SetPinStage.SHOW_CODE else SetPinStage.DONE,
                )
            }
        }
    }
}

@Composable
fun SetPinScreen(state: SetPinUiState, viewModel: SetPinViewModel, onBack: () -> Unit) {
    val colors = MbTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        MbTopBar("App PIN", onBack = onBack.takeIf { state.stage != SetPinStage.SHOW_CODE })
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.stage) {
                SetPinStage.SHOW_CODE -> RecoveryCode(state.recoveryCode.orEmpty(), viewModel::onCodeWrittenDown)
                else -> PinEntry(state, viewModel)
            }
        }
    }
}

@Composable
private fun PinEntry(state: SetPinUiState, viewModel: SetPinViewModel) {
    val colors = MbTheme.colors
    val confirming = state.stage == SetPinStage.CONFIRM
    Text(
        if (confirming) "Enter it once more" else "Choose a PIN",
        style = MbType.Title,
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
    )
    Text(
        state.message ?: if (confirming) "" else "${PinRules.MIN_DIGITS} to ${PinRules.MAX_DIGITS} digits",
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MbType.Caption,
        color = if (state.message != null) colors.danger else colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    PinDots(state.entered.length, maxOf(state.entered.length, PinRules.MIN_DIGITS))
    Spacer(Modifier.height(4.dp))
    PinPad(viewModel::onDigit, viewModel::onBackspace, enabled = !state.busy)
    if (!confirming) {
        PrimaryButton("Next", viewModel::onNext, Modifier.fillMaxWidth(), enabled = state.entered.isNotEmpty())
    }
}

/** Shown once. Screenshots are blocked while it's on screen. */
@Composable
private fun RecoveryCode(code: String, onDone: () -> Unit) {
    val colors = MbTheme.colors
    SecureWindow()
    Text("Write this down", style = MbType.Title, color = colors.textPrimary, textAlign = TextAlign.Center)
    Text(code, style = MbType.Display, color = colors.textPrimary, textAlign = TextAlign.Center)
    Text(
        "If you forget your PIN, this code is the only way back in. It is shown only this once, so keep it somewhere safe.",
        style = MbType.Body,
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    PrimaryButton("I wrote it down", onDone, Modifier.fillMaxWidth())
}
