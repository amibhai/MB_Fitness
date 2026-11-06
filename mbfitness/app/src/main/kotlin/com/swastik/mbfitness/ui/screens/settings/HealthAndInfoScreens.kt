package com.swastik.mbfitness.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.App
import com.swastik.mbfitness.BuildConfig
import com.swastik.mbfitness.R
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.data.ErrorLogEntity
import com.swastik.mbfitness.domain.BackupRules
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.HealthCheck
import com.swastik.mbfitness.domain.HealthFacts
import com.swastik.mbfitness.domain.HealthFix
import com.swastik.mbfitness.domain.HealthRow
import com.swastik.mbfitness.domain.HealthState
import com.swastik.mbfitness.domain.OemGuide
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.ReminderSchedule
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.EmptyState
import com.swastik.mbfitness.ui.components.GymLogo
import com.swastik.mbfitness.ui.components.LinkButton
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.components.rememberSmsPermissionRequest
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import com.swastik.mbfitness.work.DeviceIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HealthUiState(val loaded: Boolean = false, val rows: List<HealthRow> = emptyList(), val settings: AppSettings = AppSettings())

class HealthViewModel(private val app: App) : ViewModel() {
    val uiState = MutableStateFlow(HealthUiState())

    /** Android's settings change while the app is away, so this looks again each time. */
    fun refresh() {
        viewModelScope.launch {
            val facts = withContext(Dispatchers.IO) { collect() }
            uiState.value = HealthUiState(true, HealthCheck.rows(facts), app.settings.current())
        }
    }

    fun runReminders() {
        viewModelScope.launch {
            app.repairReminders()
            refresh()
        }
    }

    fun turnOffTestMode() {
        viewModelScope.launch {
            app.settings.update { it.copy(testMode = false) }
            refresh()
        }
    }

    private suspend fun collect(): HealthFacts {
        val checks = app.deviceChecks
        val current = app.settings.current()
        val now = app.clock.nowMillis()
        val today = app.clock.today()
        val folderOk = current.backupFolderUri?.let { uri -> runCatching { app.createBackupFolder(uri).isUsable() }.getOrDefault(false) } ?: false
        val db = app.database
        return HealthFacts(
            nowMillis = now,
            lastRunAt = current.lastDailyRunAt,
            nextRunAt = ReminderSchedule.nextRunAt(now, current.reminderHour, current.reminderMinute),
            smsAllowed = app.canSendSms(),
            installedOutsideStore = checks.installedOutsideStore(),
            exactAlarmsAllowed = checks.exactAlarmsAllowed(),
            batteryExempt = checks.batteryExempt(),
            oemGuide = OemGuide.cardFor(android.os.Build.MANUFACTURER),
            notificationsAllowed = checks.notificationsAllowed(),
            hibernationExempt = checks.hibernationExempt(),
            hasSim = checks.hasSim(),
            chosenSimMissing = checks.chosenSimMissing(current.simSubscriptionId),
            sentTodayParts = app.messages.sendsForPacing().filter { Dates.dayOf(it.atMillis) == today }.sumOf { it.parts },
            dailyCapParts = current.dailyCapParts,
            queued = app.messages.observeQueuedCount().first(),
            failed = app.messages.observeFailedCount().first(),
            today = today,
            lastBackupDay = current.lastBackupDay,
            folderChosen = current.backupFolderUri != null,
            folderWritable = folderOk,
            databaseOk = db.passesQuickCheck(),
            databaseBytes = checks.databaseBytes(),
            lastExitReason = checks.lastExitReason(),
            testMode = current.testMode,
            testModeForced = app.settings.isTestMode(AppSettings(testMode = false)),
            aadhaarSaved = db.members().countWithAadhaar(),
            pinSet = current.pinHash != null,
            backupPasswordSet = current.backupPasswordSet,
            errorCount = db.errorLog().count(),
        )
    }
}

/** SPEC 13: every row says how it is, and most can be fixed with one tap. */
@Composable
fun HealthCheckScreen(onBack: () -> Unit, onSetPin: () -> Unit, onBackup: () -> Unit, onMessages: () -> Unit, onSettings: () -> Unit, onErrorLog: () -> Unit) {
    val context = LocalContext.current
    val viewModel = appViewModel { app -> HealthViewModel(app) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val colors = MbTheme.colors
    val requestSms = rememberSmsPermissionRequest { viewModel.refresh() }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) DeviceIntents.open(context, DeviceIntents.notifications(context))
        viewModel.refresh()
    }
    val manageHibernation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refresh() }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    fun apply(fix: HealthFix) {
        when (fix) {
            HealthFix.RUN_REMINDERS -> viewModel.runReminders()
            HealthFix.SMS_PERMISSION -> requestSms()
            HealthFix.EXACT_ALARMS -> DeviceIntents.exactAlarms(context)?.let { DeviceIntents.open(context, it) }
            HealthFix.BATTERY -> DeviceIntents.open(context, DeviceIntents.battery(context))
            HealthFix.NOTIFICATIONS ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    DeviceIntents.open(context, DeviceIntents.notifications(context))
                }
            HealthFix.HIBERNATION -> manageHibernation.launch(IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName))
            HealthFix.PICK_SIM -> onSettings()
            HealthFix.OPEN_MESSAGES -> onMessages()
            HealthFix.BACKUP -> onBackup()
            HealthFix.TEST_MODE -> viewModel.turnOffTestMode()
            HealthFix.SET_PIN -> onSetPin()
            HealthFix.ERROR_LOG -> onErrorLog()
        }
    }

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Health check", onBack = onBack)
        LazyColumn(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            items(state.rows, key = { it.title }) { row -> HealthRowCard(row, onFix = { fix -> apply(fix) }) }
            val guide = OemGuide.cardFor(android.os.Build.MANUFACTURER)
            if (state.loaded && guide != null) {
                item(key = "brand") {
                    MbCard(Modifier.fillMaxWidth()) {
                        Text("On ${guide.brand} phones", style = MbType.BodyLarge, color = colors.textPrimary)
                        guide.steps.forEachIndexed { index, step ->
                            Text("${index + 1}. $step", style = MbType.Body, color = colors.textSecondary)
                        }
                        LinkButton("Open settings", {
                            val opened = DeviceIntents.brandBattery(android.os.Build.MANUFACTURER).any { DeviceIntents.open(context, it) }
                            if (!opened) snackbar.show("Open the phone's Settings and follow the steps above")
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthRowCard(row: HealthRow, onFix: (HealthFix) -> Unit) {
    val colors = MbTheme.colors
    val (icon, tint, stateWord) = when (row.state) {
        HealthState.OK -> Triple(R.drawable.ic_check_circle, colors.paid.content, "OK")
        HealthState.WARNING -> Triple(R.drawable.ic_warning, colors.dueSoon.content, "Needs attention")
        HealthState.PROBLEM -> Triple(R.drawable.ic_error, colors.danger, "Problem")
    }
    MbCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(painterResource(icon), contentDescription = stateWord, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MbType.BodyLarge, color = colors.textPrimary)
                Text(row.detail, style = MbType.Caption, color = colors.textSecondary)
                val label = row.fixLabel
                val fix = row.fix
                if (label != null && fix != null) LinkButton(label, { onFix(fix) })
            }
        }
    }
}

class ErrorLogViewModel(app: App) : ViewModel() {
    val entries: StateFlow<List<ErrorLogEntity>> = app.database.errorLog().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** The last 200 entries (SPEC 13). They never hold an Aadhaar number (hard rule 11). */
@Composable
fun ErrorLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel = appViewModel { app -> ErrorLogViewModel(app) }
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val colors = MbTheme.colors
    fun line(entry: ErrorLogEntity) = "${Dates.formatFull(Dates.dayOf(entry.at))} ${Dates.formatTime(entry.at)} ${entry.tag}: ${entry.message}"

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Error log", onBack = onBack)
        LazyColumn(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            if (entries.isEmpty()) {
                item { EmptyState("Nothing logged.") }
            } else {
                item {
                    SecondaryButton(
                        "Copy as text",
                        {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("MB Fitness error log", entries.joinToString("\n", transform = ::line)))
                            snackbar.show("Copied")
                        },
                        Modifier.fillMaxWidth(),
                    )
                }
            }
            items(entries, key = { it.id }) { entry ->
                MbCard(Modifier.fillMaxWidth()) {
                    Text(line(entry), style = MbType.Caption, color = colors.textPrimary)
                }
            }
        }
    }
}

private data class HelpTopic(val title: String, @DrawableRes val icon: Int, val steps: List<String>)

private val HELP = listOf(
    HelpTopic(
        "Record a payment",
        R.drawable.ic_payments,
        listOf("Open the member from Home.", "Tap Record payment, then pick the plan and the amount.", "Save. A part payment leaves a balance you can collect later."),
    ),
    HelpTopic(
        "Add a member",
        R.drawable.ic_person_add,
        listOf("On Home, tap Add member.", "Fill in the name, number and plan, or pick from your contacts.", "Tick SMS only after asking them. Save."),
    ),
    HelpTopic(
        "Set a quiet day",
        R.drawable.ic_event_busy,
        listOf("Go to Settings, then Quiet days.", "Tap the weekdays the gym is closed, or add a holiday by date.", "Reminders wait for the next normal day, with the right wording."),
    ),
    HelpTopic(
        "Send a broadcast",
        R.drawable.ic_campaign,
        listOf("Go to SMS, then Broadcast.", "Pick who gets it and write the message. No links.", "Check the preview and the plan, then send. It goes out slowly on purpose."),
    ),
    HelpTopic(
        "Back up your data",
        R.drawable.ic_backup,
        listOf("Go to Settings, then Backup and restore.", "Choose a folder. Backups then run every Sunday.", "To move to a new phone, install the app and pick Restore."),
    ),
    HelpTopic(
        "Reminders stopped?",
        R.drawable.ic_health_and_safety,
        listOf("Go to Settings, then Health check.", "Tap the fix beside anything that isn't green.", "Your phone may be stopping the app to save battery. The steps for your brand are at the bottom."),
    ),
)

/** SPEC 10.2 screen 18. */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val colors = MbTheme.colors
    Column(Modifier.fillMaxSize()) {
        MbTopBar("Help", onBack = onBack)
        LazyColumn(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            items(HELP, key = { it.title }) { topic ->
                MbCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(topic.icon), contentDescription = null, Modifier.size(32.dp), tint = colors.accent)
                        Spacer(Modifier.width(12.dp))
                        Text(topic.title, style = MbType.BodyLarge, color = colors.textPrimary)
                    }
                    Spacer(Modifier.height(8.dp))
                    topic.steps.forEachIndexed { index, step ->
                        Text("${index + 1}. $step", Modifier.padding(top = 4.dp), style = MbType.Body, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

/** Filled in at handover (docs/PROGRESS.md). A blank one simply doesn't show. */
private const val DEV_CONTACT = ""

/** SPEC 10.2 screen 19, and the gym's contact for data questions (SPEC 12). */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val colors = MbTheme.colors
    Column(Modifier.fillMaxSize()) {
        MbTopBar("About", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(ScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            GymLogo(settings.logoFile, settings.gymName, size = 96.dp)
            Text(settings.gymName, style = MbType.Title, color = colors.textPrimary, textAlign = TextAlign.Center)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MbType.Caption, color = colors.textSecondary)
            Text("Designed & developed by Swastik", style = MbType.Body, color = colors.textPrimary, textAlign = TextAlign.Center)
            if (DEV_CONTACT.isNotBlank()) Text(DEV_CONTACT, style = MbType.Body, color = colors.textSecondary)
            SectionLabel("Your data", Modifier.padding(top = 16.dp))
            MbCard(Modifier.fillMaxWidth()) {
                Text(
                    "Everything stays on this phone, apart from the backups you choose to save. Nothing is sent over the internet.",
                    style = MbType.Body,
                    color = colors.textPrimary,
                )
                if (settings.trainerPhone.isNotBlank()) {
                    Text(
                        "For questions about your data, contact the gym on ${PhoneNumbers.display(settings.trainerPhone)}.",
                        Modifier.padding(top = 8.dp),
                        style = MbType.Body,
                        color = colors.textSecondary,
                    )
                }
            }
            Text("Keep the newest ${BackupRules.KEEP} backups safe.", style = MbType.Caption, color = colors.textSecondary)
        }
    }
}
