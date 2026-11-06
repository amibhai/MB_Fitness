package com.swastik.mbfitness.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.swastik.mbfitness.App
import com.swastik.mbfitness.data.MessageEntity
import com.swastik.mbfitness.data.MessageRepository
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.Pacing
import com.swastik.mbfitness.domain.SendingRules
import com.swastik.mbfitness.domain.TodayProvider
import com.swastik.mbfitness.sms.BlockReason
import com.swastik.mbfitness.sms.SendOutcome
import com.swastik.mbfitness.sms.SmsRequest
import com.swastik.mbfitness.sms.SmsSender
import com.swastik.mbfitness.sms.TestModeSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one pipeline every SMS goes through (SPEC 8.1): takes what Pacing allows, one at a time,
 * then says when to come back. Separate from the worker so tests can run it with a fake sender.
 * The lock means two runs can never send at once.
 */
class OutboxEngine(
    private val messages: MessageRepository,
    private val settings: SettingsStore,
    private val sender: SmsSender,
    private val clock: TodayProvider,
    private val memberName: suspend (Long) -> String?,
    private val onBlocked: suspend (BlockReason) -> Unit = {},
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()

    /** Returns when to run again, or null if the queue is empty. */
    suspend fun run(budgetMillis: Long = DEFAULT_BUDGET_MILLIS): Long? = lock.withLock {
        messages.recoverStuck()
        val deadline = clock.nowMillis() + budgetMillis

        while (clock.nowMillis() < deadline) {
            // Read every time, so turning Test mode on takes effect from the very next message.
            val current = settings.current()
            val limits = Pacing.Limits(current.windowCapParts, current.dailyCapParts, current.reminderHour, current.reminderMinute)
            val active = if (settings.isTestMode(current)) TestModeSender(sender, current.trainerPhone) else sender
            val queue = messages.queued()
            when (val step = Pacing.next(queue.map { it.toQueued() }, clock.nowMillis(), messages.sendsForPacing(), limits)) {
                Pacing.NextStep.Idle -> return@withLock null
                is Pacing.NextStep.WakeAt -> return@withLock step.millis
                is Pacing.NextStep.Send -> {
                    val message = queue.first { it.id == step.message.id }
                    val blocked = sendOne(message, active, current.simSubscriptionId)
                    if (blocked != null) {
                        onBlocked(blocked)
                        return@withLock clock.nowMillis() + BLOCKED_RETRY_MILLIS
                    }
                    pause(SendingRules.spacingMillis())
                }
            }
        }
        clock.nowMillis()
    }

    /** Returns why nothing can go, if that's the case. */
    private suspend fun sendOne(message: MessageEntity, active: SmsSender, simId: Int?): BlockReason? {
        val attempt = message.attempts + 1
        // Recorded first, so a crash can tell "never sent" from "result unknown" (SPEC 8.5).
        messages.markSending(message.id, attempt)
        val request = SmsRequest(message.id, attempt, message.phone, message.body, message.memberId?.let { memberName(it) }, simId)
        return when (val outcome = active.send(request)) {
            is SendOutcome.Accepted -> {
                messages.markSubmitted(message.id, outcome.parts)
                null
            }
            is SendOutcome.Failed -> {
                messages.markSendProblem(message.id, outcome.reason)
                null
            }
            is SendOutcome.Blocked -> {
                messages.putBack(message.id, outcome.reason.message)
                outcome.reason
            }
        }
    }

    private fun MessageEntity.toQueued() = Pacing.Queued(id, parts, priority, createdAt, notBefore, kind.isPlanned)

    companion object {
        /** WorkManager stops a worker at 10 minutes. This leaves room to finish cleanly. */
        private const val DEFAULT_BUDGET_MILLIS = 8L * 60 * 1000
        private const val BLOCKED_RETRY_MILLIS = 60L * 60 * 1000
    }
}

/** Sends in short bursts, then schedules itself for the next allowed time (SPEC 9). */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        return try {
            app.outbox.run()?.let(app.outboxKicker::wakeAt)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // WorkManager tries again soon. The row stays queued, so nothing is lost.
            runCatching { app.errors.log("Outbox", "${error.javaClass.simpleName}: ${error.message}") }
            Result.retry()
        }
    }

    /** Expedited work on Android 8 to 11 runs as a short foreground service (SPEC 11.2). */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        (applicationContext as App).notifier.sendingForegroundInfo()
}
