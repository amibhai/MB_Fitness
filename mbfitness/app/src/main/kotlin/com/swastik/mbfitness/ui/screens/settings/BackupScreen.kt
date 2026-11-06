package com.swastik.mbfitness.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.App
import com.swastik.mbfitness.data.SafBackupFolder
import com.swastik.mbfitness.domain.BackupRules
import com.swastik.mbfitness.domain.CsvExport
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.BannerTone
import com.swastik.mbfitness.ui.components.ConfirmDialog
import com.swastik.mbfitness.ui.components.DangerButton
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.MbBanner
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.PrimaryButton
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

private enum class PasswordDialog { SET, REMOVE }

/** SPEC 12: backup now, restore, CSV export, the folder and the password. */
@Composable
fun BackupScreen(onBack: () -> Unit, onRestored: () -> Unit = {}, damaged: Boolean = false) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val viewModel = appViewModel { container ->
        BackupViewModel(container.backups, container.settings, context.contentResolver, container::createBackupFolder, container::afterRestore)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val colors = MbTheme.colors
    var passwordDialog by remember { mutableStateOf<PasswordDialog?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            SafBackupFolder.keepAccess(context, uri)
            viewModel.onFolderChosen(uri.toString())
        }
    }
    val pickRestoreFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onRestoreFileChosen(uri)
    }
    val saveMembers = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV)) { uri ->
        if (uri != null) viewModel.exportMembers(uri) { snackbar.show(it) }
    }
    val savePayments = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV)) { uri ->
        if (uri != null) viewModel.exportPayments(uri) { snackbar.show(it) }
    }
    val today = app.clock.today()

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Backup and restore", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            if (damaged) {
                MbBanner("The app's data file is damaged. Restore your latest backup.", BannerTone.DANGER)
            }
            MbCard(Modifier.fillMaxWidth()) {
                Text(BackupRules.lastBackupText(today, state.lastBackupDay), style = MbType.BodyLarge, color = colors.textPrimary)
                Text(
                    "Backups run every Sunday and the newest 8 are kept.",
                    Modifier.padding(top = 4.dp),
                    style = MbType.Caption,
                    color = colors.textSecondary,
                )
                if (!state.folderChosen) {
                    Text("Choose a folder to turn them on.", style = MbType.Caption, color = colors.danger)
                } else if (!state.folderUsable) {
                    Text("That folder can't be reached any more. Choose it again.", style = MbType.Caption, color = colors.danger)
                }
            }
            PrimaryButton(
                if (state.busy) "Working" else "Back up now",
                { viewModel.backupNow { snackbar.show(it) } },
                Modifier.fillMaxWidth(),
                enabled = state.folderChosen && !state.busy,
            )
            SecondaryButton(
                if (state.folderChosen) "Change folder" else "Choose folder",
                { pickFolder.launch(null) },
                Modifier.fillMaxWidth(),
            )

            SectionLabel("Restore")
            if (state.latestName != null) {
                SecondaryButton("Restore latest backup", viewModel::restoreLatest, Modifier.fillMaxWidth(), enabled = !state.busy)
            }
            SecondaryButton("Pick a backup file", { pickRestoreFile.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !state.busy)
            if (state.hasSafetyCopy) {
                SecondaryButton("Undo the last restore", viewModel::restoreSafetyCopy, Modifier.fillMaxWidth(), enabled = !state.busy)
            }

            SectionLabel("Backup password")
            MbCard(Modifier.fillMaxWidth()) {
                Text(
                    if (state.passwordSet) {
                        "On. Backups can only be opened with your password, and they include full Aadhaar numbers."
                    } else {
                        "Off. Anyone with a backup file can read it, but on a new phone only the last 4 digits of Aadhaar numbers come back."
                    },
                    style = MbType.Body,
                    color = colors.textPrimary,
                )
                state.hint?.let { Text("Hint: $it", Modifier.padding(top = 4.dp), style = MbType.Caption, color = colors.textSecondary) }
            }
            SecondaryButton(
                if (state.passwordSet) "Change password" else "Set a password",
                { passwordDialog = PasswordDialog.SET },
                Modifier.fillMaxWidth(),
            )
            if (state.passwordSet) {
                DangerButton("Remove password", { passwordDialog = PasswordDialog.REMOVE }, Modifier.fillMaxWidth())
            }

            SectionLabel("CSV")
            SecondaryButton("Export members", { saveMembers.launch(CsvExport.fileName("members", today)) }, Modifier.fillMaxWidth())
            SecondaryButton("Export payments", { savePayments.launch(CsvExport.fileName("payments", today)) }, Modifier.fillMaxWidth())
            Text("CSV files never include Aadhaar numbers.", style = MbType.Caption, color = colors.textSecondary)
        }
    }

    when (passwordDialog) {
        null -> Unit
        PasswordDialog.SET -> SetPasswordDialog(
            state.passwordSet,
            onSave = { password, hint ->
                passwordDialog = null
                viewModel.setPassword(password, hint) { snackbar.show(it) }
            },
            onDismiss = { passwordDialog = null },
        )
        PasswordDialog.REMOVE -> ConfirmDialog(
            title = "Remove the password?",
            message = "New backups can be read by anyone who has the file, and won't include full Aadhaar numbers. Old backups still need the old password.",
            confirmLabel = "Remove",
            danger = true,
            onConfirm = {
                passwordDialog = null
                viewModel.removePassword()
            },
            onDismiss = { passwordDialog = null },
        )
    }
    RestoreDialogs(state.restore, viewModel, onRestored)
}

@Composable
private fun RestoreDialogs(restore: RestoreStage?, viewModel: BackupViewModel, onRestored: () -> Unit) {
    val snackbar = LocalSnackbar.current
    when (restore) {
        null -> Unit
        is RestoreStage.AskPassword -> AskPasswordDialog(restore, viewModel)
        is RestoreStage.Confirm -> ConfirmDialog(
            title = "Replace everything?",
            message = buildString {
                append("This backup has ${restore.summary}.")
                restore.aadhaarNote?.let { append("\n\n").append(it) }
                append("\n\nEverything on this phone now gets replaced. A safety copy is saved first, so you can undo this.")
            },
            confirmLabel = "Restore",
            danger = true,
            onConfirm = {
                viewModel.confirmRestore { text ->
                    snackbar.show(text)
                    onRestored()
                }
            },
            onDismiss = viewModel::dismissRestore,
        )
        is RestoreStage.Refused -> ConfirmDialog(
            title = "Can't restore this",
            message = restore.reason,
            confirmLabel = "OK",
            onConfirm = viewModel::dismissRestore,
            onDismiss = viewModel::dismissRestore,
        )
    }
}

@Composable
private fun AskPasswordDialog(stage: RestoreStage.AskPassword, viewModel: BackupViewModel) {
    val colors = MbTheme.colors
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = viewModel::dismissRestore,
        containerColor = colors.surface,
        title = { Text("Backup password", style = MbType.Title, color = colors.textPrimary) },
        text = {
            MbTextField(
                password,
                { password = it },
                "Password",
                error = "That isn't the password".takeIf { stage.wrong },
                help = stage.hint?.let { "Hint: $it" },
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = {
            TextButton({ viewModel.onPasswordEntered(password) }, Modifier.heightIn(min = 48.dp), enabled = password.isNotEmpty()) {
                Text("Open", style = MbType.Label, color = if (colors.isDark) colors.greeting else colors.button)
            }
        },
        dismissButton = {
            TextButton(viewModel::dismissRestore, Modifier.heightIn(min = 48.dp)) { Text("Cancel", style = MbType.Label, color = colors.textSecondary) }
        },
    )
}

@Composable
private fun SetPasswordDialog(changing: Boolean, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    val colors = MbTheme.colors
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val problem = BackupRules.passwordProblem(password, again)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(if (changing) "Change password" else "Set a password", style = MbType.Title, color = colors.textPrimary) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ListGap),
            ) {
                Text(
                    "If you forget it, backups made with it can never be opened. Nobody can get it back for you.",
                    style = MbType.Body,
                    color = colors.danger,
                )
                MbTextField(password, { password = it }, "Password", keyboardType = KeyboardType.Password, visualTransformation = PasswordVisualTransformation())
                MbTextField(
                    again,
                    { again = it },
                    "Password again",
                    error = problem.takeIf { tried },
                    keyboardType = KeyboardType.Password,
                    visualTransformation = PasswordVisualTransformation(),
                )
                MbTextField(hint, { hint = it }, "Hint (optional)", help = "Shown when a backup asks for the password. Don't write the password here.")
            }
        },
        confirmButton = {
            TextButton(
                {
                    tried = true
                    if (problem == null) onSave(password, hint)
                },
                Modifier.heightIn(min = 48.dp),
            ) { Text("Save", style = MbType.Label, color = if (colors.isDark) colors.greeting else colors.button) }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("Cancel", style = MbType.Label, color = colors.textSecondary) }
        },
    )
}

private const val CSV = "text/csv"
