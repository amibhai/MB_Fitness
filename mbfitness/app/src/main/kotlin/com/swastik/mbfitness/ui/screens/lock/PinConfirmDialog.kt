package com.swastik.mbfitness.ui.screens.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.R
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.LinkButton
import com.swastik.mbfitness.ui.components.MbIconButton
import com.swastik.mbfitness.ui.components.PinDots
import com.swastik.mbfitness.ui.components.PinPad
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

/**
 * Asks for the PIN or fingerprint before something sensitive, like showing an Aadhaar number
 * (SPEC 7.10). It shares the lock screen's logic, so wrong guesses count the same way.
 */
@Composable
fun PinConfirmDialog(title: String, onConfirmed: () -> Unit, onDismiss: () -> Unit) {
    val viewModel = appViewModel(key = "pin-confirm") { app -> LockViewModel(app.appLock, app.settings, app.clock) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = MbTheme.colors
    val context = LocalContext.current
    val biometricActivity = (context as? FragmentActivity)
        ?.takeIf { state.biometricEnabled && canUseBiometric(context) }

    LaunchedEffect(Unit) { viewModel.reset() }
    LaunchedEffect(state.pinConfirmed) {
        if (state.pinConfirmed) {
            viewModel.reset()
            onConfirmed()
        }
    }
    LaunchedEffect(biometricActivity) {
        biometricActivity?.let { promptBiometric(it, title, viewModel::onBiometricSuccess) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(18.dp),
            color = colors.surface,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MbType.Title, color = colors.textPrimary, textAlign = TextAlign.Center)
                val status = if (state.waitSeconds > 0) "Too many tries. Wait ${state.waitSeconds} seconds." else state.message
                Text(
                    status ?: "Enter your PIN",
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MbType.Caption,
                    color = if (status != null) colors.danger else colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                PinDots(state.entered.length, maxOf(state.pinLength, state.entered.length))
                PinPad(
                    onDigit = viewModel::onDigit,
                    onBackspace = viewModel::onBackspace,
                    enabled = !state.busy && state.waitSeconds == 0L,
                    cornerKey = biometricActivity?.let { activity ->
                        {
                            MbIconButton(
                                R.drawable.ic_fingerprint,
                                "Use fingerprint or face",
                                { promptBiometric(activity, title, viewModel::onBiometricSuccess) },
                            )
                        }
                    },
                )
                LinkButton("Cancel", onDismiss, color = colors.textSecondary)
            }
        }
    }
}
