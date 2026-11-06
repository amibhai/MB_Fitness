package com.swastik.mbfitness.ui.screens.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.BackupFolder
import com.swastik.mbfitness.data.BackupManager
import com.swastik.mbfitness.data.BackupOutcome
import com.swastik.mbfitness.data.BackupRestoreResult
import com.swastik.mbfitness.data.CopyOutcome
import com.swastik.mbfitness.data.MAX_PICKED_FILE_BYTES
import com.swastik.mbfitness.data.readUpTo
import com.swastik.mbfitness.data.RestoreOpen
import com.swastik.mbfitness.data.RestorePlan
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.BackupRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface RestoreStage {
    data class AskPassword(val hint: String?, val wrong: Boolean = false) : RestoreStage
    data class Confirm(val summary: String, val aadhaarNote: String?, val plan: RestorePlan) : RestoreStage
    data class Refused(val reason: String) : RestoreStage
}

data class BackupUiState(
    val loaded: Boolean = false,
    val lastBackupDay: Long? = null,
    val folderChosen: Boolean = false,
    val folderUsable: Boolean = false,
    val latestName: String? = null,
    val passwordSet: Boolean = false,
    val hint: String? = null,
    val hasSafetyCopy: Boolean = false,
    /** The day of the app's own newest copy, offered when it's newer than anything in the folder. */
    val ownCopyDay: Long? = null,
    val lastCopySentDay: Long? = null,
    val busy: Boolean = false,
    val restore: RestoreStage? = null,
)

private data class FolderFacts(
    val usable: Boolean = false,
    val latest: String? = null,
    val hasSafetyCopy: Boolean = false,
    val ownCopyDay: Long? = null,
)

class BackupViewModel(
    private val backups: BackupManager,
    private val settings: SettingsStore,
    private val resolver: ContentResolver,
    private val folderFor: (String) -> BackupFolder,
    private val afterRestore: suspend () -> Unit,
) : ViewModel() {
    private val facts = MutableStateFlow(FolderFacts(hasSafetyCopy = backups.hasSafetyCopy()))
    private val busy = MutableStateFlow(false)
    private val restore = MutableStateFlow<RestoreStage?>(null)
    private var pendingBytes: ByteArray? = null

    val uiState: StateFlow<BackupUiState> =
        combine(settings.settings, facts, busy, restore) { current, folder, working, stage ->
            BackupUiState(
                loaded = true,
                lastBackupDay = current.lastBackupDay,
                folderChosen = current.backupFolderUri != null,
                folderUsable = folder.usable,
                latestName = folder.latest,
                passwordSet = current.backupPasswordSet,
                hint = current.backupPasswordHint,
                hasSafetyCopy = folder.hasSafetyCopy,
                ownCopyDay = folder.ownCopyDay,
                lastCopySentDay = current.lastCopySentDay,
                busy = working,
                restore = stage,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BackupUiState())

    /** The folder can change behind the app's back, so this looks again each time. */
    fun onResume() = refreshFolder()

    private fun refreshFolder() {
        viewModelScope.launch {
            val uri = settings.current().backupFolderUri
            facts.value = withContext(Dispatchers.IO) {
                val safety = backups.hasSafetyCopy()
                val own = runCatching { backups.ownCopyDay() }.getOrNull()
                if (uri == null) {
                    FolderFacts(hasSafetyCopy = safety, ownCopyDay = own)
                } else {
                    val folder = folderFor(uri)
                    val usable = folder.isUsable()
                    FolderFacts(usable, if (usable) runCatching { backups.latestName(folder) }.getOrNull() else null, safety, own)
                }
            }
        }
    }

    fun onFolderChosen(uri: String) {
        viewModelScope.launch {
            settings.update { it.copy(backupFolderUri = uri) }
            refreshFolder()
        }
    }

    fun backupNow(onDone: (String) -> Unit) {
        working {
            onDone(
                when (val outcome = backups.backupNow()) {
                    is BackupOutcome.Saved -> "Backed up as ${outcome.name}"
                    BackupOutcome.NoFolder -> "Choose a folder first"
                    BackupOutcome.FolderGone -> "That folder can't be reached any more. Choose it again."
                    BackupOutcome.PasswordNeeded -> "Set your backup password again. This phone can't reach the old one."
                    is BackupOutcome.Failed -> "The backup didn't work. Check the folder has space."
                },
            )
            refreshFolder()
        }
    }

    /** Makes the file for "Send a copy". The screen hands it to Android's share sheet. */
    fun sendCopy(onReady: (File) -> Unit, onProblem: (String) -> Unit) {
        working {
            when (val outcome = backups.copyToSend()) {
                is CopyOutcome.Ready -> onReady(outcome.file)
                CopyOutcome.PasswordNeeded -> onProblem("Set your backup password again. This phone can't reach the old one.")
                is CopyOutcome.Failed -> onProblem("The copy couldn't be made. Check the phone has space.")
            }
        }
    }

    fun setPassword(password: String, hint: String, onDone: (String) -> Unit) {
        working {
            val saved = backups.setPassword(password, hint)
            onDone(if (saved) "Password set. It applies from the next backup." else "This phone couldn't store the password. Nothing changed.")
        }
    }

    fun removePassword() {
        viewModelScope.launch { backups.removePassword() }
    }

    /** The newest backup in the folder that opens. A damaged newest file gives way to the one before. */
    fun restoreLatest() {
        working {
            val uri = settings.current().backupFolderUri
            val bytes = uri?.let { folder -> withContext(Dispatchers.IO) { runCatching { backups.newestReadable(folderFor(folder)) }.getOrNull() } }
            if (bytes == null) restore.value = RestoreStage.Refused("No backup in that folder can be read.") else openFile(bytes, null)
        }
    }

    /** The app's own daily copy, which can be newer than any backup in the folder. */
    fun restoreOwnCopy() {
        working {
            val bytes = backups.ownCopy()
            if (bytes == null) restore.value = RestoreStage.Refused("That copy can't be read.") else openFile(bytes, null)
        }
    }

    fun onRestoreFileChosen(uri: Uri) {
        working {
            val bytes = readOrNull { resolver.openInputStream(uri)?.use { it.readUpTo(MAX_PICKED_FILE_BYTES) } }
            if (bytes == null) restore.value = RestoreStage.Refused("That file can't be read.") else openFile(bytes, null)
        }
    }

    /** Puts back the data from before the last restore. */
    fun restoreSafetyCopy() {
        working {
            val bytes = readOrNull { backups.safetyCopy() }
            if (bytes == null) restore.value = RestoreStage.Refused("There's nothing to undo.") else openFile(bytes, null)
        }
    }

    fun onPasswordEntered(password: String) {
        val bytes = pendingBytes ?: return
        working { openFile(bytes, password) }
    }

    private suspend fun openFile(bytes: ByteArray, password: String?) {
        pendingBytes = bytes
        restore.value = when (val opened = backups.open(bytes, password)) {
            is RestoreOpen.NeedsPassword -> RestoreStage.AskPassword(opened.hint)
            RestoreOpen.WrongPassword -> (restore.value as? RestoreStage.AskPassword)?.copy(wrong = true) ?: RestoreStage.AskPassword(null, wrong = true)
            is RestoreOpen.NotABackup -> RestoreStage.Refused(opened.reason)
            RestoreOpen.TooNew -> RestoreStage.Refused("This backup is from a newer version of the app. Update the app first.")
            is RestoreOpen.Ready -> RestoreStage.Confirm(opened.summary, opened.aadhaarNote, opened.plan)
        }
    }

    fun confirmRestore(onDone: (String) -> Unit) {
        val stage = restore.value as? RestoreStage.Confirm ?: return
        restore.value = null
        working {
            pendingBytes = null
            when (val result = backups.restore(stage.plan)) {
                is BackupRestoreResult.Restored -> {
                    afterRestore()
                    onDone(BackupRules.describeLost(result.aadhaarToReenter)?.let { "Restored. $it" } ?: "Restored")
                }
                BackupRestoreResult.SafetyCopyFailed ->
                    restore.value = RestoreStage.Refused("Couldn't save a safety copy first, so nothing was changed. Check the phone has space.")
                is BackupRestoreResult.Failed ->
                    restore.value = RestoreStage.Refused("The restore didn't work, and nothing was changed.")
            }
            refreshFolder()
        }
    }

    fun dismissRestore() {
        pendingBytes = null
        restore.value = null
    }

    fun exportMembers(uri: Uri, onDone: (String) -> Unit) = export(uri, backups::membersCsv, onDone)

    fun exportPayments(uri: Uri, onDone: (String) -> Unit) = export(uri, backups::paymentsCsv, onDone)

    private fun export(uri: Uri, build: suspend () -> String, onDone: (String) -> Unit) {
        working {
            val text = build()
            val written = readOrNull {
                resolver.openOutputStream(uri, "wt")?.use { stream ->
                    // The byte-order mark tells Excel it's UTF-8, so Hindi names come out right.
                    stream.write(BYTE_ORDER_MARK)
                    stream.write(text.toByteArray())
                    Unit
                }
            }
            onDone(if (written != null) "Exported" else "That file couldn't be written")
        }
    }

    private fun working(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.update { true }
            try {
                block()
            } finally {
                busy.update { false }
            }
        }
    }

    private suspend fun <T> readOrNull(block: () -> T?): T? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        val BYTE_ORDER_MARK = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
