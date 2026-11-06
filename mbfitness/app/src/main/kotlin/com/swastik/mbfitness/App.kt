package com.swastik.mbfitness

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.swastik.mbfitness.data.AadhaarVault
import com.swastik.mbfitness.data.AppDatabase
import com.swastik.mbfitness.data.AppLock
import com.swastik.mbfitness.data.BackupFolder
import com.swastik.mbfitness.data.BackupManager
import com.swastik.mbfitness.data.CrashReports
import com.swastik.mbfitness.data.ErrorLogger
import com.swastik.mbfitness.data.KeystoreAadhaarVault
import com.swastik.mbfitness.data.LogoStore
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.MessageRepository
import com.swastik.mbfitness.data.SafBackupFolder
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.CrashLoop
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.LogText
import com.swastik.mbfitness.domain.SystemTodayProvider
import com.swastik.mbfitness.domain.TodayProvider
import com.swastik.mbfitness.sms.BlockReason
import com.swastik.mbfitness.sms.SimSmsSender
import com.swastik.mbfitness.sms.SmsSender
import com.swastik.mbfitness.widget.MbWidget
import com.swastik.mbfitness.widget.WidgetRefresher
import com.swastik.mbfitness.work.DailyRunner
import com.swastik.mbfitness.work.DeviceChecks
import com.swastik.mbfitness.work.Notices
import com.swastik.mbfitness.work.OutboxEngine
import com.swastik.mbfitness.work.OutboxKicker
import com.swastik.mbfitness.work.ProblemKind
import com.swastik.mbfitness.work.ReminderScheduling
import com.swastik.mbfitness.work.SystemNotices
import com.swastik.mbfitness.work.WorkManagerKicker
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Lives at files/datastore/settings.preferences_pb, which the backup rules include. A damaged
 * file would stop the app opening at all, so it starts again from the defaults instead and setup
 * runs again. Members and payments are in the database, untouched.
 */
private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { SettingsStore.afterDamage() },
)

/**
 * Manual DI container (SPEC 5.3). Everything added here must stay lazy: this class also runs
 * when a boot receiver starts the process before the first unlock, when the database and
 * settings can't be opened yet. Tests subclass it to swap in fakes.
 */
open class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FailureGuard.install(this)
    }

    open val clock: TodayProvider = SystemTodayProvider
    val backgroundScope: CoroutineScope by lazy {
        // Background work must never take the app down: anything that escapes goes to the log.
        val onFailure = CoroutineExceptionHandler { _, error -> recordFailure("Background", error) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + onFailure)
    }

    val database: AppDatabase by lazy { createDatabase() }
    val settings: SettingsStore by lazy { createSettings() }
    val aadhaarVault: AadhaarVault by lazy { createVault() }
    val errors: ErrorLogger by lazy { ErrorLogger(database, clock) }
    private val crashReports by lazy { CrashReports(filesDir) }
    val outboxKicker: OutboxKicker by lazy { createKicker() }
    val members: MemberRepository by lazy {
        MemberRepository(
            database,
            settings,
            aadhaarVault,
            clock,
            onMessagesQueued = { outboxKicker.wakeAt(clock.nowMillis() + THANKS_WAKE_MILLIS) },
        )
    }
    val messages: MessageRepository by lazy { MessageRepository(database, settings, clock) }
    val appLock: AppLock by lazy { AppLock(settings, clock) }
    val logos: LogoStore by lazy { LogoStore(this, settings) }
    open val deviceChecks: DeviceChecks by lazy { DeviceChecks(this) }
    val smsSender: SmsSender by lazy { createSender() }
    val notifier: SystemNotices by lazy { SystemNotices(this) }
    open val notices: Notices get() = notifier

    val outbox: OutboxEngine by lazy {
        OutboxEngine(
            messages,
            settings,
            smsSender,
            clock,
            memberName = { id -> members.member(id)?.name },
            onBlocked = { reason ->
                when (reason) {
                    BlockReason.NO_PERMISSION -> notices.problem(ProblemKind.SMS_PERMISSION)
                    BlockReason.NO_TRAINER_NUMBER -> notices.problem(ProblemKind.NO_TRAINER_NUMBER)
                }
            },
        )
    }
    val backups: BackupManager by lazy {
        BackupManager(
            database,
            settings,
            aadhaarVault,
            createBackupKeyVault(),
            clock,
            errors,
            BuildConfig.VERSION_NAME,
            backupFilesDir(),
            databaseDamaged = { databaseDamaged.value || damageMarker.exists() },
            folderFor = ::createBackupFolder,
        )
    }
    val dailyRunner: DailyRunner by lazy {
        DailyRunner(
            members,
            messages,
            settings,
            clock,
            outboxKicker,
            notices,
            errors,
            weeklyBackup = backups::runScheduledBackups,
            simProblem = ::simProblem,
            refreshWidget = ::refreshWidget,
        )
    }

    /** The chosen SIM has gone, or none is chosen on a phone that asks every time. */
    private suspend fun simProblem(): ProblemKind? {
        val chosen = settings.current().simSubscriptionId
        return when {
            deviceChecks.chosenSimMissing(chosen) -> ProblemKind.SIM_MISSING
            chosen == null && deviceChecks.phoneAsksWhichSim() -> ProblemKind.SIM_NOT_CHOSEN
            else -> null
        }
    }

    protected open fun createDatabase(): AppDatabase = AppDatabase.create(this, ::markDatabaseDamaged)
    protected open fun createSettings(): SettingsStore = SettingsStore(settingsDataStore, forceTestMode = BuildConfig.DEBUG)
    protected open fun createVault(): AadhaarVault = KeystoreAadhaarVault()
    protected open fun createSender(): SmsSender = SimSmsSender(this)
    protected open fun createKicker(): OutboxKicker = WorkManagerKicker(this, clock)
    protected open fun createBackupKeyVault(): AadhaarVault = KeystoreAadhaarVault(BACKUP_KEY_ALIAS)
    open fun createBackupFolder(uri: String): BackupFolder = DebugHooks.testFolder(this, uri) ?: SafBackupFolder(this, uri.toUri())
    protected open fun backupFilesDir(): File = filesDir

    /** Layer 3 of "reminders must never silently stop" (SPEC 9), on every app open. */
    protected open fun onAppOpened() {
        backgroundScope.launch {
            attempt("Opening: alarms") { armReminders() }
            attempt("Opening: setup date") { noteSetupDone() }
            attempt("Opening: daily run") { dailyRunner.runIfDue() }
            attempt("Opening: outbox") { outboxKicker.kick() }
        }
    }

    /**
     * Runs one step of a background job on its own, so one failing can't stop the ones after it.
     * The failure goes to the error log.
     */
    suspend fun attempt(where: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure(where, error)
        }
    }

    /** Setup finished before version 1.0.1 recorded when, so the first open after updating does. */
    private suspend fun noteSetupDone() {
        val current = settings.current()
        if (!current.onboardingDone || current.setupDoneAt != null) return
        val now = clock.nowMillis()
        settings.update { it.copy(setupDoneAt = it.setupDoneAt ?: now) }
    }

    protected open suspend fun armReminders() = ReminderScheduling.ensureAll(this)

    /**
     * Puts a failure in the error log, with where it happened, for Swastik to read later. If the
     * database is what broke, the details wait in the crash file instead.
     */
    fun recordFailure(where: String, error: Throwable) {
        val details = LogText.describe(error)
        val at = clock.nowMillis()
        backgroundScope.launch {
            try {
                errors.log(where, details)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                runCatching { crashReports.write(at, where, details) }
            }
        }
    }

    /** A screen's work failed and the app carries on: say so, and keep the details for Swastik. */
    fun onScreenFailure(error: Throwable) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(this, FailureGuard.messageFor(error), Toast.LENGTH_LONG).show() }
        recordFailure("Screen", error)
    }

    /** Android is about to close the app, so there's no time for the database: the file it is. */
    fun recordCrash(thread: Thread, error: Throwable) {
        val now = clock.nowMillis()
        crashReports.write(now, "Crash (${thread.name})", LogText.describe(error))
        crashReports.noteCrash(now)
    }

    /** True when it's crashed so often that opening normally won't help (RecoveryActivity). */
    fun keepsCrashing(): Boolean = CrashLoop.keepsCrashing(crashReports.crashTimes(), clock.nowMillis())

    fun forgetCrashes() = crashReports.forgetCrashes()

    /** Heads whatever is copied for Swastik, so he can match it to that release's mapping.txt. */
    fun versionLine(): String =
        "MB Fitness ${BuildConfig.VERSION_NAME}, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"

    /** What RecoveryActivity copies for Swastik: which phone and version, and the last crash. */
    fun crashDetails(): String {
        val report = crashReports.peek()
        return versionLine() + "\n" + (report?.let { "${it.where}: ${it.details}" } ?: "No details were saved.")
    }

    /** A crash last time left its details in a file. They belong in the error log. Internal for tests. */
    internal fun collectCrashReport() {
        backgroundScope.launch {
            val report = crashReports.take() ?: return@launch
            errors.log(report.where, "${report.details}\n(at ${Dates.formatFull(Dates.dayOf(report.at))} ${Dates.formatTime(report.at)})")
        }
    }

    /** True when the data file is damaged, so the app can offer a restore (SPEC 12). */
    val databaseDamaged = MutableStateFlow(false)

    private val damageMarker get() = File(filesDir, "database_damaged")

    private fun markDatabaseDamaged() {
        runCatching { damageMarker.createNewFile() }
        databaseDamaged.value = true
    }

    /**
     * Android deletes a corrupt database as soon as it notices, and starts an empty one, so the
     * marker is what remembers it. Otherwise this would look like an empty new install.
     */
    protected open fun checkDatabase() {
        backgroundScope.launch {
            if (damageMarker.exists() || !database.passesQuickCheck()) databaseDamaged.value = true
        }
    }

    /** Someone restored a backup, or chose to carry on. */
    fun clearDatabaseDamage() {
        damageMarker.delete()
        databaseDamaged.value = false
    }

    protected open suspend fun refreshWidget() = MbWidget.refresh(this)

    /** For as long as the app is open, the widget follows every change to members and payments. */
    private fun watchForWidget() {
        backgroundScope.launch { WidgetRefresher(members.observeMembers(), ::refreshWidget).watch() }
    }

    suspend fun rearmReminders() = armReminders()

    suspend fun afterRestore() {
        clearDatabaseDamage()
        armReminders()
    }

    /** The "Fix" button on Home: sets everything up again and does today's run. */
    suspend fun repairReminders() {
        attempt("Fix: alarms") { armReminders() }
        dailyRunner.run()
    }

    open fun canSendSms(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private var watchingForeground = false

    /**
     * Called from MainActivity rather than onCreate: this process can start before the first
     * unlock, when ProcessLifecycleOwner isn't available.
     */
    fun watchForeground() {
        if (watchingForeground) return
        watchingForeground = true
        checkDatabase()
        collectCrashReport()
        watchForWidget()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    appLock.onForegrounded()
                    onAppOpened()
                }

                override fun onStop(owner: LifecycleOwner) = appLock.onBackgrounded()
            },
        )
    }

    private companion object {
        /** Just past the thank-you's wait, so it goes as soon as Undo can no longer cancel it. */
        const val THANKS_WAKE_MILLIS = 11_000L
        const val BACKUP_KEY_ALIAS = "mbf_backup_key"
    }
}
