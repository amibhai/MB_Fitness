package com.swastik.mbfitness.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.os.UserManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.swastik.mbfitness.App
import com.swastik.mbfitness.MainActivity
import com.swastik.mbfitness.R
import com.swastik.mbfitness.data.ErrorLogger
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.MessageRepository
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.MemberRules
import com.swastik.mbfitness.domain.MemberState
import com.swastik.mbfitness.domain.MemberTab
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MorningSummary
import com.swastik.mbfitness.domain.PlanResult
import com.swastik.mbfitness.domain.ReminderSchedule
import com.swastik.mbfitness.domain.SummaryFacts
import com.swastik.mbfitness.domain.SummaryText
import com.swastik.mbfitness.domain.TodayProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Starts the outbox. An interface so tests can run without WorkManager. */
interface OutboxKicker {
    /** Soon, as expedited work. */
    fun kick()

    /** At this time, replacing any earlier wake-up. */
    fun wakeAt(millis: Long)
}

class WorkManagerKicker(private val context: Context, private val clock: TodayProvider) : OutboxKicker {
    override fun kick() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RUN_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Separate from [kick], so a wake-up for tomorrow can't make a message written now wait
     * behind it. The engine's lock keeps the two from ever sending at once.
     */
    override fun wakeAt(millis: Long) {
        val delay = (millis - clock.nowMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<OutboxWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WAKE_LATER, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val RUN_NOW = "outbox-now"
        const val WAKE_LATER = "outbox-later"
    }
}

/** Notices. An interface so tests can see what would have been posted. */
interface Notices {
    fun morningSummary(text: SummaryText)
    fun problem(kind: ProblemKind)
}

enum class ProblemKind(val id: Int, val title: String, val detail: String) {
    SMS_PERMISSION(101, "SMS permission is off", "Reminders can't go until it's back on. Tap to fix."),
    NO_TRAINER_NUMBER(102, "Add your phone number", "Test mode needs it before any SMS can go."),
    REMINDERS_STOPPED(103, "Reminders haven't run for 2 days", "Tap to check what's wrong."),
    BACKUP_OVERDUE(104, "Backup didn't work", "This week's backup couldn't be made. Tap to fix."),
    SIM_MISSING(105, "Your chosen SIM isn't there", "Reminders are using the phone's default SIM. Tap to check."),
}

data class DailyRunResult(val plan: PlanResult?, val summary: SummaryText?)

/**
 * The daily job (SPEC 9). Safe to call from the alarm, the watchdog or an app open: it runs once
 * a day. Each step is wrapped, so one failure can't stop the others.
 */
class DailyRunner(
    private val members: MemberRepository,
    private val messages: MessageRepository,
    private val settings: SettingsStore,
    private val clock: TodayProvider,
    private val kicker: OutboxKicker,
    private val notices: Notices,
    private val errors: ErrorLogger,
    /** False means a backup was due and couldn't be made. */
    private val weeklyBackup: suspend () -> Boolean = { true },
    private val chosenSimMissing: suspend () -> Boolean = { false },
) {
    suspend fun runIfDue(): Boolean {
        val current = settings.current()
        if (!ReminderSchedule.isRunDue(clock.nowMillis(), current.lastDailyRunDay, current.reminderHour, current.reminderMinute)) {
            return false
        }
        run()
        return true
    }

    /**
     * Running it twice in a day queues nothing new, because of the dedupe keys. A run that couldn't
     * plan doesn't count as a run: the watchdog tries again, Home goes amber and the "reminders
     * have stopped" notice can fire (SPEC 9: reminders must never silently stop).
     */
    suspend fun run(ignoreQuietDay: Boolean = false): DailyRunResult {
        step("housekeeping") { members.housekeeping() }
        val plan = step("planning") { messages.planAndQueue(ignoreQuietDay) }
        step("outbox") { kicker.kick() }
        step("backup") { if (!weeklyBackup()) notices.problem(ProblemKind.BACKUP_OVERDUE) }
        step("sim") { if (chosenSimMissing()) notices.problem(ProblemKind.SIM_MISSING) }
        // The widget refresh joins here in Phase 7.
        val summary = plan?.let { step("summary") { postSummary(it, ignoreQuietDay) } }
        if (plan != null) {
            step("finish") {
                val now = clock.nowMillis()
                settings.update { it.copy(lastDailyRunDay = clock.today(), lastDailyRunAt = now) }
            }
        }
        return DailyRunResult(plan, summary)
    }

    private suspend fun <T> step(name: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        report("$name failed: ${error.javaClass.simpleName}: ${error.message}")
        null
    }

    /** If the database is what broke, this can't be written either. That can't stop the run. */
    private suspend fun report(message: String) {
        try {
            errors.log("DailyRun", message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Nowhere left to record it.
        }
    }

    private suspend fun postSummary(plan: PlanResult, ignoreQuietDay: Boolean): SummaryText {
        val current = settings.current()
        val today = clock.today()
        val rows = members.observeMembers().first()
        val tabs = rows.groupingBy { MemberRules.tab(it.status, it.expiryDate, today) }.eachCount()
        val quiet = messages.quietCalendar(current)
        val isQuiet = !ignoreQuietDay && quiet.isQuiet(today)
        val next = if (isQuiet) messages.previewNextNormalDay() else null
        val planned = plan.messages
        val birthdays = planned.filter { it.kind == MessageKind.BIRTHDAY }.map { message ->
            rows.firstOrNull { it.id == message.memberId }?.name?.substringBefore(' ').orEmpty()
        }
        val facts = SummaryFacts(
            due = tabs[MemberTab.DUE_SOON] ?: 0,
            overdue = tabs[MemberTab.OVERDUE] ?: 0,
            balancePending = rows.filter { it.status != MemberState.LEFT }.sumOf { MemberRules.due(it.balance) },
            onTheirWay = planned.count { it.kind != MessageKind.BIRTHDAY },
            failed = messages.failedSince(Dates.millisAt(today - 1, 0)),
            quietReason = if (isQuiet) quiet.reason(today) else null,
            willGoNext = next?.second?.messages?.size,
            nextIsTomorrow = next?.first == today + 1,
            birthdays = birthdays,
            held = plan.held.size,
        )
        val text = MorningSummary.build(facts)
        val worthSaying = current.allClearNotice || !MorningSummary.isAllClear(facts)
        if (current.morningSummary && worthSaying) notices.morningSummary(text)
        return text
    }
}

/** Posts notifications. Quietly does nothing if they aren't allowed (SPEC 8.10). */
class SystemNotices(private val context: Context) : Notices {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannels() {
        manager.createNotificationChannel(NotificationChannel(SUMMARY, "Daily summary", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(PROBLEMS, "Problems", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(SENDING, "Sending reminders", NotificationManager.IMPORTANCE_LOW))
    }

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(openMessages: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_MESSAGES, openMessages)
        return PendingIntent.getActivity(context, if (openMessages) 1 else 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @SuppressLint("MissingPermission") // allowed() checks it.
    private fun post(id: Int, notification: Notification) {
        if (allowed()) manager.notify(id, notification)
    }

    override fun morningSummary(text: SummaryText) {
        ensureChannels()
        post(
            SUMMARY_ID,
            NotificationCompat.Builder(context, SUMMARY)
                .setSmallIcon(R.drawable.ic_sms)
                .setContentTitle(text.title)
                .setContentText(text.detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.detail))
                .setContentIntent(openApp(text.opensMessages))
                .setAutoCancel(true)
                .build(),
        )
    }

    override fun problem(kind: ProblemKind) {
        ensureChannels()
        post(
            kind.id,
            NotificationCompat.Builder(context, PROBLEMS)
                .setSmallIcon(R.drawable.ic_warning)
                .setContentTitle(kind.title)
                .setContentText(kind.detail)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp(openMessages = false))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** The quiet notification Android 8 to 11 require for expedited work (SPEC 11.2). */
    fun sendingForegroundInfo(): ForegroundInfo {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, SENDING)
            .setSmallIcon(R.drawable.ic_sms)
            .setContentTitle("Sending reminders…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        return ForegroundInfo(SENDING_ID, notification)
    }

    private companion object {
        const val SUMMARY = "daily_summary"
        const val PROBLEMS = "problems"
        const val SENDING = "sending"
        const val SUMMARY_ID = 100
        const val SENDING_ID = 200
    }
}

/** Keeps reminders scheduled, however the phone behaves (SPEC 9). */
object ReminderScheduling {
    private const val WATCHDOG = "watchdog"
    private const val DAILY_RUN = "daily-run"
    private const val WATCHDOG_HOURS = 6L
    private const val WINDOW_MILLIS = 30L * 60 * 1000

    /** An exact alarm if allowed, otherwise a 30-minute window (SPEC 9). */
    @SuppressLint("ScheduleExactAlarm") // canScheduleExactAlarms() checks it.
    fun armAlarm(context: Context, nowMillis: Long, hour: Int, minute: Int) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = ReminderSchedule.nextRunAt(nowMillis, hour, minute)
        val intent = Intent(context, DailyAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exactAllowed) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MILLIS, pending)
        }
    }

    fun ensureWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(WATCHDOG_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WATCHDOG, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun startDailyRun(context: Context) {
        val request = OneTimeWorkRequestBuilder<DailyRunWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DAILY_RUN, ExistingWorkPolicy.KEEP, request)
    }

    /** Re-arms everything from the current settings. Safe to call any time. */
    suspend fun ensureAll(app: App) {
        val current = app.settings.current()
        armAlarm(app, app.clock.nowMillis(), current.reminderHour, current.reminderMinute)
        ensureWatchdog(app)
    }
}

class DailyRunWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as App).dailyRunner.runIfDue()
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        (applicationContext as App).notifier.sendingForegroundInfo()
}

/** The safety net: catches a run the alarm missed, and says if reminders have stopped (SPEC 9). */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val lastRunBefore = app.settings.current().lastDailyRunDay
        ReminderScheduling.ensureAll(app)
        app.dailyRunner.runIfDue()
        if (ReminderSchedule.hasStopped(app.clock.nowMillis(), lastRunBefore)) {
            app.notifier.problem(ProblemKind.REMINDERS_STOPPED)
        }
        app.outboxKicker.kick()
        return Result.success()
    }
}

/** The alarm went off: start today's run, and set tomorrow's alarm (SPEC 9). */
class DailyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        ReminderScheduling.startDailyRun(app)
        val pending = goAsync()
        app.backgroundScope.launch {
            try {
                ReminderScheduling.ensureAll(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Restores the alarm after a reboot, an app update or a clock change (SPEC 9). Before the first
 * unlock the database and settings can't be opened, so it does nothing then: BOOT_COMPLETED
 * follows after unlock (docs/DECISIONS.md).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS || !UserManagerCompat.isUserUnlocked(context)) return
        val app = context.applicationContext as App
        val pending = goAsync()
        app.backgroundScope.launch {
            try {
                ReminderScheduling.ensureAll(app)
                app.outboxKicker.kick()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            // AlarmManager's name for this needs API 31. The text itself works on any version.
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
