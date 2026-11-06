package com.swastik.mbfitness.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.swastik.mbfitness.App
import com.swastik.mbfitness.ui.components.ConfirmDialog
import com.swastik.mbfitness.ui.components.DangerButton
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.MbBanner
import com.swastik.mbfitness.ui.components.BannerTone
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.PrimaryButton
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.screens.lock.RecoveryCode
import com.swastik.mbfitness.ui.theme.MbTheme
import kotlinx.coroutines.launch

private enum class SecurityDialog { NEW_CODE, TURN_OFF }

/** Reached only after the PIN has been asked for, so nobody can change it without knowing it. */
@Composable
fun SecurityScreen(onBack: () -> Unit, onChangePin: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var dialog by remember { mutableStateOf<SecurityDialog?>(null) }
    var newCode by remember { mutableStateOf<String?>(null) }
    var aadhaarSaved by remember { mutableIntStateOf(0) }
    val code = newCode

    if (code != null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MbTheme.colors.background),
        ) {
            MbTopBar("Recovery code")
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RecoveryCode(code) { newCode = null }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        MbTopBar("App PIN", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            PrimaryButton("Change PIN", onChangePin, Modifier.fillMaxWidth())
            SecondaryButton("Get a new recovery code", { dialog = SecurityDialog.NEW_CODE }, Modifier.fillMaxWidth())
            DangerButton(
                "Turn off PIN",
                {
                    scope.launch {
                        aadhaarSaved = app.database.members().countWithAadhaar()
                        dialog = SecurityDialog.TURN_OFF
                    }
                },
                Modifier.fillMaxWidth(),
            )
            if (aadhaarSaved > 0 && dialog == null) {
                MbBanner("Aadhaar numbers are saved here. They can't be shown without a PIN.", BannerTone.INFO)
            }
            Text(
                "If you forget your PIN, the recovery code is the only way back in.",
                style = com.swastik.mbfitness.ui.theme.MbType.Caption,
                color = MbTheme.colors.textSecondary,
            )
        }
    }

    when (dialog) {
        null -> Unit
        SecurityDialog.NEW_CODE -> ConfirmDialog(
            title = "Get a new recovery code?",
            message = "The code you wrote down before will stop working.",
            confirmLabel = "Get a new one",
            onConfirm = {
                dialog = null
                scope.launch { newCode = app.appLock.replaceRecoveryCode() }
            },
            onDismiss = { dialog = null },
        )
        SecurityDialog.TURN_OFF -> ConfirmDialog(
            title = "Turn off the PIN?",
            message = buildString {
                append("Anyone holding this phone can open the app and see fees and member details.")
                if (aadhaarSaved > 0) append(" Aadhaar numbers stay encrypted, but can't be shown until a PIN is set again.")
            },
            confirmLabel = "Turn off",
            danger = true,
            onConfirm = {
                dialog = null
                scope.launch {
                    app.appLock.removePin()
                    snackbar.show("PIN turned off")
                    onBack()
                }
            },
            onDismiss = { dialog = null },
        )
    }
}

/** Shown instead of the app when the data file is damaged (SPEC 12). */
@Composable
fun DamagedDatabaseScreen() {
    val app = LocalContext.current.applicationContext as App
    var carryOn by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackupScreen(
            onBack = { carryOn = true },
            onRestored = app::clearDatabaseDamage,
            damaged = true,
        )
    }
    if (carryOn) {
        ConfirmDialog(
            title = "Carry on without restoring?",
            message = "Some or all of your data may be missing or wrong. Restoring your latest backup is safer.",
            confirmLabel = "Carry on",
            danger = true,
            dismissLabel = "Go back",
            onConfirm = {
                carryOn = false
                app.clearDatabaseDamage()
            },
            onDismiss = { carryOn = false },
        )
    }
}
