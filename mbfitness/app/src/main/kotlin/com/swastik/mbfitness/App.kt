package com.swastik.mbfitness

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.swastik.mbfitness.data.AadhaarVault
import com.swastik.mbfitness.data.AppDatabase
import com.swastik.mbfitness.data.AppLock
import com.swastik.mbfitness.data.BackupFolder
import com.swastik.mbfitness.data.BackupManager
import com.swastik.mbfitness.data.ErrorLogger
import com.swastik.mbfitness.data.KeystoreAadhaarVault
import com.swastik.mbfitness.data.LogoStore
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.MessageRepository
import com.swastik.mbfitness.data.SafBackupFolder
import com.swastik.mbfitness.data.SettingsStore
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Lives at files/datastore/settings.preferences_pb, which the backup rules include. */
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Manual DI container (SPEC 5.3). Everything added here must stay lazy: this class also runs
 * when a boot receiver starts the process before the first unlock, when the database and
 * settings can't be opened yet. Tests subclass it to swap in fakes.
 */
open class App : Application() {
    override fun onCreate() {
        super.onCreate()
        StorageFullGuard.install(this)
    }

    open val clock: TodayProvider = SystemTodayProvider
    val backgroundScope: CoroutineScope by lazy {
        // Background work must never take the app down: anything that escapes goes to the log.
        val onFailure = CoroutineExceptionHandler { _, error ->
            backgroundScope.launch { runCatching { errors.log("Background", "${error.javaClass.simpleName}: ${error.message}") } }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + onFailure)
    }

    val database: AppDatabase by lazy { createDatabase() }
    val settings: SettingsStore by lazy { createSettings() }
    val aadhaarVault: AadhaarVault by lazy { createVault() }
    val errors: ErrorLogger by lazy { ErrorLogger(database, clock) }
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
        BackupManager(database, settings, aadhaarVault, createBackupKeyVault(), clock, errors, BuildConfig.VERSION_NAME, backupFilesDir(), ::createBackupFolder)
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
            weeklyBackup = backups::runWeeklyIfDue,
            chosenSimMissing = { deviceChecks.chosenSimMissing(settings.current().simSubscriptionId) },
            refreshWidget = ::refreshWidget,
        )
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
            armReminders()
            dailyRunner.runIfDue()
            outboxKicker.kick()
        }
    }

    protected open suspend fun armReminders() = ReminderScheduling.ensureAll(this)

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
        armReminders()
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
