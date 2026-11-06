package com.swastik.mbfitness.ui.screens.settings

import android.content.ContentResolver
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.ImportResult
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.ImportPreview
import com.swastik.mbfitness.domain.ImportRow
import com.swastik.mbfitness.domain.MemberImport
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.TodayProvider
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.BannerTone
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.MbBanner
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbCheckbox
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.PrimaryButton
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class ImportUiState(
    val preview: ImportPreview? = null,
    val smsConsent: Boolean = false,
    val aadhaarConsent: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
    val doneMessage: String? = null,
)

/** SPEC 7.9 and 7.10. Nothing is saved until the preview is confirmed. */
class ImportViewModel(
    private val members: MemberRepository,
    private val clock: TodayProvider,
    private val resolver: ContentResolver,
) : ViewModel() {
    val uiState = MutableStateFlow(ImportUiState())
    private var text: String? = null

    fun onFileChosen(uri: Uri) {
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                try {
                    resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }
            if (read == null) {
                uiState.update { it.copy(error = "That file can't be read.") }
            } else {
                text = read
                uiState.update { ImportUiState() }
                refreshPreview()
            }
        }
    }

    fun onSmsConsentChange(agreed: Boolean) = uiState.update { it.copy(smsConsent = agreed) }

    /** Without this tick the Aadhaar column is ignored completely (SPEC 7.9). */
    fun onAadhaarConsentChange(agreed: Boolean) {
        uiState.update { it.copy(aadhaarConsent = agreed) }
        viewModelScope.launch { refreshPreview() }
    }

    private suspend fun refreshPreview() {
        val file = text ?: return
        val useAadhaar = uiState.value.aadhaarConsent
        val planMonths = members.activePlanMonths().toSet()
        val existing = members.existingPhones()
        val preview = withContext(Dispatchers.Default) {
            MemberImport.preview(file, existing, useAadhaar, clock.today(), planMonths)
        }
        uiState.update { it.copy(preview = preview, error = null) }
    }

    fun import() {
        val state = uiState.value
        val rows = state.preview?.rows.orEmpty()
        if (rows.isEmpty() || state.working) return
        uiState.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            try {
                val result = members.importMembers(rows, state.smsConsent)
                text = null
                uiState.value = ImportUiState(doneMessage = doneText(result, state.aadhaarConsent))
            } catch (_: SQLiteConstraintException) {
                // Someone was added between the preview and now. Nothing was saved.
                uiState.update { it.copy(working = false, error = "One of these numbers was just added. Nothing was imported. Check again.") }
                refreshPreview()
            }
        }
    }

    private fun doneText(result: ImportResult, usedAadhaar: Boolean): String = buildString {
        append(if (result.imported == 1) "1 member added." else "${result.imported} members added.")
        if (usedAadhaar) {
            append(if (result.aadhaarStored == 1) " 1 Aadhaar number saved." else " ${result.aadhaarStored} Aadhaar numbers saved.")
            if (result.aadhaarFailed > 0) append(" ${result.aadhaarFailed} couldn't be secured on this phone, so they weren't saved.")
            if (result.aadhaarDuplicates.isNotEmpty()) {
                append(" Check ${result.aadhaarDuplicates.joinToString(", ")}: the same number is on another member.")
            }
        }
    }

    fun sampleFile(uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) {
                try {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(MemberImport.SAMPLE.toByteArray()) } != null
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
            onDone(written)
        }
    }
}

/** SPEC 7.9. */
@Composable
fun ImportScreen(onBack: () -> Unit, onAddOne: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel = appViewModel { app -> ImportViewModel(app.members, app.clock, context.contentResolver) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = MbTheme.colors
    val snackbar = com.swastik.mbfitness.ui.components.LocalSnackbar.current
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFileChosen(uri)
    }
    val saveSample = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.sampleFile(uri) { saved -> snackbar.show(if (saved) "Sample file saved" else "That file couldn't be written") }
    }
    val preview = state.preview

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Import members", onBack = onBack)
        LazyColumn(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            state.doneMessage?.let { done ->
                item {
                    MbBanner("$done Delete the CSV file from your phone now. It holds their details.", BannerTone.INFO)
                }
            }
            state.error?.let { problem -> item { MbBanner(problem, BannerTone.DANGER) } }
            preview?.fileProblem?.let { problem -> item { MbBanner(problem, BannerTone.DANGER) } }

            if (preview == null) {
                item {
                    Text(
                        "Pick a CSV file with these columns: name, phone, plan_months, expiry_date (DD-MM-YYYY), balance_due, dob, notes and aadhaar. Only the first four are needed.",
                        style = MbType.Body,
                        color = colors.textPrimary,
                    )
                }
            }
            item {
                PrimaryButton(if (preview == null) "Pick a CSV file" else "Pick another file", { pickFile.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
            }
            if (preview == null) {
                item { SecondaryButton("Get a sample file", { saveSample.launch(MemberImport.SAMPLE_FILE_NAME) }, Modifier.fillMaxWidth()) }
                item { SecondaryButton("Add one from contacts", onAddOne, Modifier.fillMaxWidth()) }
            }

            if (preview != null && preview.fileProblem == null) {
                item {
                    MbCheckbox(state.smsConsent, viewModel::onSmsConsentChange, "These members have agreed to SMS reminders")
                }
                if (preview.hasAadhaarColumn) {
                    item {
                        MbCheckbox(state.aadhaarConsent, viewModel::onAadhaarConsentChange, "These members agreed to share Aadhaar for gym records")
                    }
                    item {
                        Text(
                            if (state.aadhaarConsent) "Delete the CSV file after importing. It holds full Aadhaar numbers." else "Without this tick, the Aadhaar column is ignored.",
                            style = MbType.Caption,
                            color = colors.textSecondary,
                        )
                    }
                }
                item {
                    PrimaryButton(
                        when {
                            state.working -> "Importing"
                            preview.rows.size == 1 -> "Import 1 member"
                            else -> "Import ${preview.rows.size} members"
                        },
                        viewModel::import,
                        Modifier.fillMaxWidth(),
                        enabled = preview.rows.isNotEmpty() && !state.working,
                    )
                }
                if (preview.errors.isNotEmpty()) {
                    item { SectionLabel("Won't be imported (${preview.errors.size})", Modifier.padding(top = 8.dp)) }
                    items(preview.errors, key = { "e${it.line}" }) { error ->
                        MbCard(Modifier.fillMaxWidth()) {
                            Text("Line ${error.line}: ${error.name.ifBlank { "No name" }}", style = MbType.BodyLarge, color = colors.danger)
                            error.reasons.forEach { Text(it, style = MbType.Caption, color = colors.textSecondary) }
                        }
                    }
                }
                if (preview.duplicates.isNotEmpty()) {
                    item { SectionLabel("Already here, so skipped (${preview.duplicates.size})", Modifier.padding(top = 8.dp)) }
                    items(preview.duplicates, key = { "d${it.line}" }) { duplicate ->
                        MbCard(Modifier.fillMaxWidth()) {
                            Text(duplicate.name, style = MbType.BodyLarge, color = colors.textPrimary)
                            Text(PhoneNumbers.display(duplicate.phone), style = MbType.Caption, color = colors.textSecondary)
                        }
                    }
                }
                if (preview.rows.isNotEmpty()) {
                    item { SectionLabel("Ready (${preview.rows.size})", Modifier.padding(top = 8.dp)) }
                    items(preview.rows, key = { "r${it.line}" }) { row -> ReadyRow(row) }
                }
            }
        }
    }
}

@Composable
private fun ReadyRow(row: ImportRow) {
    val colors = MbTheme.colors
    MbCard(Modifier.fillMaxWidth()) {
        Text(row.name, style = MbType.BodyLarge, color = colors.textPrimary)
        val balance = if (row.balanceDue > 0) " · ${Money.format(row.balanceDue)} due" else ""
        Text(
            "${PhoneNumbers.display(row.phone)} · valid till ${Dates.formatShort(row.expiry)}$balance",
            style = MbType.Caption,
            color = colors.textSecondary,
        )
        row.aadhaar?.let { Text("Aadhaar $it", style = MbType.Caption, color = colors.textSecondary) }
    }
}
