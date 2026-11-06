package com.swastik.mbfitness.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.App
import com.swastik.mbfitness.R
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.data.LogoStore
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.RenewalRule
import com.swastik.mbfitness.domain.SettingsRules
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.ChoiceCard
import com.swastik.mbfitness.ui.components.ConfirmDialog
import com.swastik.mbfitness.ui.components.GymLogo
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbSwitchRow
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.components.launchOr
import com.swastik.mbfitness.ui.screens.lock.PinConfirmDialog
import com.swastik.mbfitness.ui.screens.lock.canUseBiometric
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import com.swastik.mbfitness.work.SimChoice
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val testModeForced: Boolean = false,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val logos: LogoStore,
    private val rearm: suspend () -> Unit,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = store.settings
        .map { SettingsUiState(true, it, store.isTestMode(AppSettings(testMode = false))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { store.update(transform) }
    }

    /** The alarm has to move with it, or today's reminders would still go at the old time. */
    fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            store.update { it.copy(reminderHour = hour, reminderMinute = minute) }
            rearm()
        }
    }

    fun pickLogo(picture: android.net.Uri, onFailed: () -> Unit) {
        viewModelScope.launch { if (!logos.save(picture)) onFailed() }
    }

    fun removeLogo() {
        viewModelScope.launch { logos.remove() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** A row that opens something, with what it's set to underneath. */
@Composable
fun SettingRow(title: String, value: String? = null, onClick: () -> Unit) {
    val colors = MbTheme.colors
    MbCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MbType.BodyLarge, color = colors.textPrimary)
                value?.let { Text(it, style = MbType.Caption, color = colors.textSecondary) }
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.textSecondary)
        }
    }
}

/** One text field in a dialog, checked before it saves. */
private data class TextEdit(
    val title: String,
    val value: String,
    val keyboard: KeyboardType = KeyboardType.Text,
    val capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
    val help: String? = null,
    val problem: (String) -> String? = { null },
    val save: (String) -> Unit,
)

private enum class SettingDialog { TIME, RENEWAL, SIM, TEST_MODE_OFF, CONFIRM_PIN }

/** SPEC 10.2 screen 12. */
@Composable
fun SettingsScreen(
    onSetPin: () -> Unit,
    onSecurity: () -> Unit,
    onPlans: () -> Unit,
    onRecycleBin: () -> Unit,
    onTemplates: () -> Unit,
    onQuietDays: () -> Unit,
    onImport: () -> Unit,
    onBackup: () -> Unit,
    onHealthCheck: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val viewModel = appViewModel { container -> SettingsViewModel(container.settings, container.logos, container::rearmReminders) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val settings = state.settings
    var editing by remember { mutableStateOf<TextEdit?>(null) }
    var dialog by remember { mutableStateOf<SettingDialog?>(null) }
    var sims by remember { mutableStateOf(emptyList<SimChoice>()) }

    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picture ->
        if (picture != null) viewModel.pickLogo(picture) { snackbar.show("Couldn't read that picture") }
    }
    fun openSimPicker() {
        sims = app.deviceChecks.activeSims()
        if (sims.isEmpty()) snackbar.show("No SIM found") else dialog = SettingDialog.SIM
    }
    val askForPhoneState = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openSimPicker() else snackbar.show("Android needs that permission to list the SIMs")
    }

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Settings")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            SectionLabel("Gym")
            MbCard(Modifier.fillMaxWidth(), onClick = { pickLogo.launchOr(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) { snackbar.show("This phone has no photo picker") } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GymLogo(settings.logoFile, settings.gymName)
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text("Logo", style = MbType.BodyLarge, color = MbTheme.colors.textPrimary)
                        Text(if (settings.logoFile == null) "Tap to pick one" else "Tap to change", style = MbType.Caption, color = MbTheme.colors.textSecondary)
                    }
                    if (settings.logoFile != null) {
                        TextButton(viewModel::removeLogo, Modifier.heightIn(min = 48.dp)) { Text("Remove", style = MbType.Label, color = MbTheme.colors.danger) }
                    }
                }
            }
            SettingRow("Greeting", settings.greeting) {
                editing = TextEdit("Greeting", settings.greeting, capitalization = KeyboardCapitalization.Characters, problem = { SettingsRules.requiredProblem(it, "Greeting") }) { text ->
                    viewModel.update { it.copy(greeting = text.trim()) }
                }
            }
            SettingRow("Owner name", settings.ownerName) {
                editing = TextEdit("Owner name", settings.ownerName, problem = { SettingsRules.requiredProblem(it, "Owner name") }) { text ->
                    viewModel.update { it.copy(ownerName = text.trim()) }
                }
            }
            SettingRow("Gym name", settings.gymName) {
                editing = TextEdit("Gym name", settings.gymName, help = "This goes in every SMS.", problem = { SettingsRules.requiredProblem(it, "Gym name") }) { text ->
                    viewModel.update { it.copy(gymName = text.trim()) }
                }
            }
            SettingRow("Your phone number", settings.trainerPhone.ifBlank { null }?.let(PhoneNumbers::display) ?: "Not set") {
                editing = TextEdit(
                    "Your phone number",
                    PhoneNumbers.display(settings.trainerPhone).filter { it.isDigit() },
                    KeyboardType.Phone,
                    help = "Members see this in some reminders. Test SMS come here.",
                    problem = SettingsRules::trainerPhoneProblem,
                ) { text ->
                    viewModel.update { it.copy(trainerPhone = PhoneNumbers.normalize(text).orEmpty()) }
                }
            }
            SettingRow("UPI ID", settings.upiId.ifBlank { "Not set" }) {
                editing = TextEdit("UPI ID", settings.upiId, KeyboardType.Email, KeyboardCapitalization.None, "It goes on the QR code and in reminders.", SettingsRules::upiProblem) { text ->
                    viewModel.update { it.copy(upiId = text.trim()) }
                }
            }
            SettingRow("UPI payee name", settings.upiPayeeName.ifBlank { "Not set" }) {
                editing = TextEdit("UPI payee name", settings.upiPayeeName, help = "The name people see in their UPI app.") { text ->
                    viewModel.update { it.copy(upiPayeeName = text.trim()) }
                }
            }
            SettingRow("Plans and prices", onClick = onPlans)

            SectionLabel("Reminders")
            SettingRow("Templates", onClick = onTemplates)
            SettingRow("Quiet days", onClick = onQuietDays)
            SettingRow("Reminder time", SettingsRules.timeText(settings.reminderHour, settings.reminderMinute)) { dialog = SettingDialog.TIME }
            SettingRow("Daily SMS limit", "${settings.dailyCapParts} a day") {
                editing = TextEdit(
                    "Daily SMS limit",
                    settings.dailyCapParts.toString(),
                    KeyboardType.Number,
                    help = "Prepaid plans usually allow about 100 a day. Stay a little under yours.",
                    problem = SettingsRules::dailyCapProblem,
                ) { text ->
                    viewModel.update { it.copy(dailyCapParts = text.toInt()) }
                }
            }
            SettingRow("SIM", settings.simSubscriptionId?.let { "Chosen" } ?: "The phone's default") {
                if (app.deviceChecks.canListSims()) openSimPicker() else askForPhoneState.launch(android.Manifest.permission.READ_PHONE_STATE)
            }
            SettingRow("Renewal rule", SettingsRules.renewalRuleTitle(settings.renewalRule)) { dialog = SettingDialog.RENEWAL }
            MbCard(Modifier.fillMaxWidth()) {
                MbSwitchRow(settings.morningSummary, { on -> viewModel.update { it.copy(morningSummary = on) } }, "Morning summary", help = "A notification after each day's run")
                if (settings.morningSummary) {
                    MbSwitchRow(settings.allClearNotice, { on -> viewModel.update { it.copy(allClearNotice = on) } }, "Also when all is clear", help = "Even on days with nothing to report")
                }
                MbSwitchRow(
                    settings.testMode || state.testModeForced,
                    { on -> if (!state.testModeForced) { if (on) viewModel.update { it.copy(testMode = true) } else dialog = SettingDialog.TEST_MODE_OFF } },
                    "Test mode",
                    help = if (state.testModeForced) "Always on in this test build" else "Every SMS goes to your number instead of members",
                )
            }

            SectionLabel("Data")
            SettingRow("Import members", onClick = onImport)
            SettingRow("Backup and restore", onClick = onBackup)
            SettingRow("Recycle bin", onClick = onRecycleBin)

            SectionLabel("Security")
            SettingRow("App PIN", if (settings.pinHash != null) "On" else "Off") {
                if (settings.pinHash != null) dialog = SettingDialog.CONFIRM_PIN else onSetPin()
            }
            if (settings.pinHash != null && canUseBiometric(context)) {
                MbCard(Modifier.fillMaxWidth()) {
                    MbSwitchRow(settings.biometric, { on -> viewModel.update { it.copy(biometric = on) } }, "Unlock with fingerprint or face")
                }
            }

            SectionLabel("More")
            SettingRow("Health check", onClick = onHealthCheck)
            SettingRow("Help", onClick = onHelp)
            SettingRow("About", onClick = onAbout)
        }
    }

    editing?.let { edit -> TextEditDialog(edit, onDismiss = { editing = null }) }
    when (dialog) {
        null -> Unit
        SettingDialog.TIME -> ReminderTimeDialog(settings, onSave = { hour, minute ->
            dialog = null
            viewModel.setReminderTime(hour, minute)
        }, onDismiss = { dialog = null })
        SettingDialog.RENEWAL -> RenewalRuleDialog(settings.renewalRule, onPick = { rule ->
            dialog = null
            viewModel.update { it.copy(renewalRule = rule) }
        }, onDismiss = { dialog = null })
        SettingDialog.SIM -> SimDialog(sims, settings.simSubscriptionId, onPick = { id ->
            dialog = null
            viewModel.update { it.copy(simSubscriptionId = id) }
        }, onDismiss = { dialog = null })
        SettingDialog.TEST_MODE_OFF -> ConfirmDialog(
            title = "Turn off Test mode?",
            message = "Members will start getting real SMS.",
            confirmLabel = "Turn off",
            onConfirm = {
                dialog = null
                viewModel.update { it.copy(testMode = false) }
            },
            onDismiss = { dialog = null },
        )
        SettingDialog.CONFIRM_PIN -> PinConfirmDialog(
            title = "Enter your PIN",
            onConfirmed = {
                dialog = null
                onSecurity()
            },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun TextEditDialog(edit: TextEdit, onDismiss: () -> Unit) {
    val colors = MbTheme.colors
    var text by remember { mutableStateOf(edit.value) }
    var tried by remember { mutableStateOf(false) }
    val problem = edit.problem(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(edit.title, style = MbType.Title, color = colors.textPrimary) },
        text = {
            MbTextField(
                text,
                { text = it },
                edit.title,
                error = problem.takeIf { tried },
                help = edit.help,
                keyboardType = edit.keyboard,
                capitalization = edit.capitalization,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    tried = true
                    if (problem == null) {
                        edit.save(text)
                        onDismiss()
                    }
                },
                Modifier.heightIn(min = 48.dp),
            ) { Text("Save", style = MbType.Label, color = if (colors.isDark) colors.greeting else colors.button) }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("Cancel", style = MbType.Label, color = colors.textSecondary) }
        },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeDialog(settings: AppSettings, onSave: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val colors = MbTheme.colors
    val picker = rememberTimePickerState(settings.reminderHour, settings.reminderMinute)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Reminder time", style = MbType.Title, color = colors.textPrimary) },
        text = { TimePicker(picker) },
        confirmButton = {
            TextButton({ onSave(picker.hour, picker.minute) }, Modifier.heightIn(min = 48.dp)) {
                Text("Save", style = MbType.Label, color = if (colors.isDark) colors.greeting else colors.button)
            }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("Cancel", style = MbType.Label, color = colors.textSecondary) }
        },
    )
}

@Composable
private fun RenewalRuleDialog(current: RenewalRule, onPick: (RenewalRule) -> Unit, onDismiss: () -> Unit) {
    val colors = MbTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Renewal rule", style = MbType.Title, color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ListGap)) {
                RenewalRule.entries.forEach { rule ->
                    ChoiceCard(SettingsRules.renewalRuleTitle(rule), SettingsRules.renewalRuleDetail(rule), rule == current, { onPick(rule) })
                }
                Text("Payments already recorded keep the rule they were made with.", style = MbType.Caption, color = colors.textSecondary)
            }
        },
        confirmButton = {
            TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("Close", style = MbType.Label, color = colors.textSecondary) }
        },
    )
}

@Composable
private fun SimDialog(sims: List<SimChoice>, current: Int?, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    val colors = MbTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("SIM for reminders", style = MbType.Title, color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ListGap)) {
                ChoiceCard("The phone's default", null, current == null, { onPick(null) })
                sims.forEach { sim -> ChoiceCard(sim.label, null, sim.id == current, { onPick(sim.id) }) }
            }
        },
        confirmButton = {
            TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("Close", style = MbType.Label, color = colors.textSecondary) }
        },
    )
}
