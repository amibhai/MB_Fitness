package com.swastik.mbfitness.ui.screens.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.swastik.mbfitness.R
import com.swastik.mbfitness.ui.components.LinkButton
import com.swastik.mbfitness.ui.components.MbIconButton
import com.swastik.mbfitness.ui.components.PinDots
import com.swastik.mbfitness.ui.components.PinPad
import com.swastik.mbfitness.ui.components.PrimaryButton
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import com.swastik.mbfitness.domain.PinRules

/** Weak biometrics are enough to open the app, and work on every Android version we support. */
private const val ALLOWED_BIOMETRICS = BiometricManager.Authenticators.BIOMETRIC_WEAK

fun canUseBiometric(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(ALLOWED_BIOMETRICS) == BiometricManager.BIOMETRIC_SUCCESS

/** Falling back to the PIN is always possible, so errors and cancels simply do nothing. */
fun promptBiometric(activity: FragmentActivity, title: String, onSuccess: () -> Unit) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setAllowedAuthenticators(ALLOWED_BIOMETRICS)
        .setNegativeButtonText("Use PIN")
        .build()
    prompt.authenticate(info)
}

/** SPEC 10.2 screen 2. */
@Composable
fun LockScreen(state: LockUiState, viewModel: LockViewModel) {
    val colors = MbTheme.colors
    val context = LocalContext.current
    val biometricActivity = (context as? FragmentActivity)
        ?.takeIf { state.biometricEnabled && canUseBiometric(context) }

    LaunchedEffect(biometricActivity) {
        biometricActivity?.let { promptBiometric(it, "Unlock MB Fitness", viewModel::onBiometricSuccess) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.header)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        val title = when (state.stage) {
            LockStage.PIN -> "Enter your PIN"
            LockStage.RECOVERY -> "Enter your recovery code"
            LockStage.NEW_PIN -> "Choose a new PIN"
            LockStage.CONFIRM_PIN -> "Confirm your new PIN"
        }
        Text(title, style = MbType.Title, color = colors.onHeader, textAlign = TextAlign.Center)

        val status = when {
            state.waitSeconds > 0 -> "Too many tries. Wait ${state.waitSeconds} seconds."
            else -> state.message
        }
        Text(
            status.orEmpty(),
            Modifier
                .height(40.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MbType.Caption,
            color = colors.greeting,
            textAlign = TextAlign.Center,
        )

        if (state.stage == LockStage.RECOVERY) {
            RecoveryEntry(state, viewModel)
        } else {
            val total = when (state.stage) {
                LockStage.NEW_PIN -> maxOf(state.entered.length, PinRules.MIN_DIGITS)
                else -> maxOf(state.pinLength, state.entered.length)
            }
            PinDots(state.entered.length, total)
            Spacer(Modifier.height(8.dp))
            PinPad(
                onDigit = viewModel::onDigit,
                onBackspace = viewModel::onBackspace,
                enabled = !state.busy && state.waitSeconds == 0L,
                keyColor = colors.onHeader,
                cornerKey = biometricActivity?.takeIf { state.stage == LockStage.PIN }?.let { activity ->
                    {
                        MbIconButton(
                            R.drawable.ic_fingerprint,
                            "Unlock with fingerprint or face",
                            { promptBiometric(activity, "Unlock MB Fitness", viewModel::onBiometricSuccess) },
                            tint = colors.onHeader,
                        )
                    }
                },
            )
            if (state.stage == LockStage.NEW_PIN) {
                PrimaryButton("Next", viewModel::onNewPinEntered, Modifier.fillMaxWidth())
            }
            if (state.stage == LockStage.PIN && state.recoveryAvailable) {
                LinkButton("Forgot PIN", viewModel::onForgotPin, color = colors.greeting)
            }
        }
    }
}

@Composable
private fun RecoveryEntry(state: LockUiState, viewModel: LockViewModel) {
    val colors = MbTheme.colors
    var code by remember { mutableStateOf("") }
    OutlinedTextField(
        code,
        { code = it },
        Modifier.fillMaxWidth(),
        label = { Text("Recovery code") },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.onHeader,
            unfocusedTextColor = colors.onHeader,
            focusedLabelColor = colors.greeting,
            unfocusedLabelColor = colors.onHeaderMuted,
            focusedBorderColor = colors.greeting,
            unfocusedBorderColor = colors.onHeaderMuted,
            cursorColor = colors.greeting,
        ),
    )
    PrimaryButton("Check code", { viewModel.onRecoveryCode(code) }, Modifier.fillMaxWidth(), enabled = !state.busy && code.isNotBlank())
    LinkButton("Back to PIN", viewModel::onBackToPin, color = colors.greeting)
    // Nobody else can unlock it, so the last way back is said here rather than left to a phone call.
    Text(
        "Lost the code too? Then only a backup can bring your data back: in Android's App info for this app, clear its storage, open the app and restore your latest backup.",
        style = MbType.Caption,
        color = colors.onHeaderMuted,
        textAlign = TextAlign.Center,
    )
}
