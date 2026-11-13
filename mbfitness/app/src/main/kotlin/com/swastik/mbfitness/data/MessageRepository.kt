package com.swastik.mbfitness.data

import androidx.room.withTransaction
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.domain.PackLanguage
import com.swastik.mbfitness.domain.Pacing
import com.swastik.mbfitness.domain.PartPayment
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.PlanResult
import com.swastik.mbfitness.domain.PlannedMessage
import com.swastik.mbfitness.domain.PlannerHistory
import com.swastik.mbfitness.domain.PlannerInput
import com.swastik.mbfitness.domain.PlannerMember
import com.swastik.mbfitness.domain.PlannerSettings
import com.swastik.mbfitness.domain.QuietCalendar
import com.swastik.mbfitness.domain.QuietDate
import com.swastik.mbfitness.domain.ReminderPlanner
import com.swastik.mbfitness.domain.RetryPolicy
import com.swastik.mbfitness.domain.SendingRules
import com.swastik.mbfitness.domain.SmsSegments
import com.swastik.mbfitness.domain.TemplateConfig
import com.swastik.mbfitness.domain.TemplatePacks
import com.swastik.mbfitness.domain.TodayProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The outbox: every SMS is a row here (SPEC 8.1). This class only records what should happen.
 * Nothing here talks to the radio; only OutboxWorker does (hard rule 3).
 */
class MessageRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val clock: TodayProvider,
) {
    private val messages get() = db.messages()

    fun observeWithStatus(statuses: List<MessageStatus>): Flow<List<MessageWithMember>> =
        messages.observeWithStatus(statuses)

    fun observeQueuedCount(): Flow<Int> = messages.observeCountWithStatus(listOf(MessageStatus.QUEUED, MessageStatus.SENDING))

    fun observeFailedCount(): Flow<Int> = messages.observeCountWithStatus(listOf(MessageStatus.FAILED))

    fun observeSentCount(): Flow<Int> = messages.observeCountWithStatus(listOf(MessageStatus.SENT, MessageStatus.DELIVERED))

    // ---- What the planner needs ----

    /** Every template, with the pack's preset for a kind added after this database was made. */
    suspend fun templates(): List<TemplateConfig> = templatesIn(settings.current().packLanguage)

    private suspend fun templatesIn(language: PackLanguage): List<TemplateConfig> =
        TemplatePacks.withMissing(db.templates().all().map { it.toConfig() }, language)

    private suspend fun plannerInput(today: Long, current: AppSettings, ignoreQuietDay: Boolean): PlannerInput {
        val since = Dates.millisAt(today - HISTORY_DAYS, 0)
        val members = db.members().plannerRows().map { row ->
            PlannerMember(
                id = row.id,
                fullName = row.name,
                phone = row.phone,
                state = row.status,
                expiry = row.expiryDate,
                balance = row.balance,
                renewalFee = row.planPrice,
                planName = row.planName,
                dob = row.dob,
                smsConsent = row.smsConsent,
                remindersEnabled = row.remindersEnabled,
                lastPartPayment = if (row.lastPaymentId != null && row.lastPaymentOn != null) {
                    PartPayment(row.lastPaymentId, row.lastPaymentOn)
                } else {
                    null
                },
            )
        }
        val lastReminders = messages.lastPlannedPerMember(plannedKinds, since)
        return PlannerInput(
            today = today,
            members = members,
            templates = templatesIn(current.packLanguage),
            history = PlannerHistory(
                usedKeys = messages.usedKeys(since).toSet(),
                lastReminderDay = lastReminders.associate { it.memberId to Dates.dayOf(it.createdAt) },
            ),
            quiet = quietCalendar(current),
            settings = PlannerSettings(
                gymName = current.gymName,
                upiId = current.upiId,
                trainerPhone = PhoneNumbers.display(current.trainerPhone),
                language = current.packLanguage,
            ),
            ignoreQuietDay = ignoreQuietDay,
        )
    }

    suspend fun quietCalendar(current: AppSettings): QuietCalendar =
        QuietCalendar(current.quietWeekdays, db.quietDates().all().map { QuietDate(it.date, it.label) })

    /** What a run would send, for the "Run reminders now" preview (SPEC 8.3). Changes nothing. */
    suspend fun previewPlan(ignoreQuietDay: Boolean = false): PlanResult {
        val current = settings.current()
        return ReminderPlanner.plan(plannerInput(clock.today(), current, ignoreQuietDay))
    }

    /** What the next normal day would send, for "6 will go tomorrow". */
    suspend fun previewNextNormalDay(): Pair<Long, PlanResult>? {
        val current = settings.current()
        return ReminderPlanner.planForNextNormalDay(plannerInput(clock.today(), current, ignoreQuietDay = false))
    }

    /**
     * Plans today and queues it, in one transaction. Reminders left over from an earlier day
     * are replaced, because their wording has gone stale ("due today" is false tomorrow). The
     * replacements keep their original place in the queue (SPEC 8.4).
     */
    suspend fun planAndQueue(ignoreQuietDay: Boolean = false): PlanResult {
        val current = settings.current()
        val today = clock.today()
        val now = clock.nowMillis()
        return db.withTransaction {
            val startOfToday = Dates.millisAt(today, 0)
            val leftovers = messages.queuedPlannedBefore(plannedKinds, startOfToday)
            val originalTimes = leftovers.groupBy { it.memberId }.mapValues { (_, rows) -> rows.minOf { it.createdAt } }
            leftovers.forEach { messages.cancel(it.id, "Replaced by today's plan") }

            val plan = ReminderPlanner.plan(plannerInput(today, current, ignoreQuietDay))
            plan.messages.forEach { planned -> messages.insert(planned.toEntity(originalTimes[planned.memberId] ?: now, now)) }
            plan
        }
    }

    private fun PlannedMessage.toEntity(createdAt: Long, now: Long) = MessageEntity(
        memberId = memberId,
        phone = phone,
        kind = kind,
        body = body,
        parts = SmsSegments.count(body).parts,
        priority = kind.priority,
        forExpiry = forExpiry,
        dedupeKey = dedupeKey,
        createdAt = createdAt,
        notBefore = now,
    )

    // ---- Other ways into the outbox ----

    suspend fun queueManual(memberId: Long, phone: String, body: String): Long =
        queue(memberId, phone, MessageKind.MANUAL, body)

    /** Goes to the owner's own number. The outbox's test mode isn't involved. */
    suspend fun queueTest(body: String): Long? {
        val phone = settings.current().trainerPhone.takeIf { it.isNotBlank() } ?: return null
        return queue(null, phone, MessageKind.TEST, body)
    }

    private suspend fun queue(memberId: Long?, phone: String, kind: MessageKind, body: String): Long {
        val now = clock.nowMillis()
        return messages.insert(
            MessageEntity(
                memberId = memberId,
                phone = phone,
                kind = kind,
                body = body,
                parts = SmsSegments.count(body).parts,
                priority = kind.priority,
                createdAt = now,
                notBefore = now,
            ),
        )
    }

    data class BroadcastRecipient(val memberId: Long, val phone: String, val body: String)

    /** One row per person, so pacing, retries and logging work like any other SMS. */
    suspend fun queueBroadcast(template: String, audience: String, recipients: List<BroadcastRecipient>): Long {
        val now = clock.nowMillis()
        return db.withTransaction {
            val id = db.broadcasts().insert(BroadcastEntity(body = template, audience = audience, createdAt = now, recipientCount = recipients.size))
            recipients.forEach { recipient ->
                messages.insert(
                    MessageEntity(
                        memberId = recipient.memberId,
                        phone = recipient.phone,
                        kind = MessageKind.BROADCAST,
                        body = recipient.body,
                        parts = SmsSegments.count(recipient.body).parts,
                        priority = MessageKind.BROADCAST.priority,
                        broadcastId = id,
                        createdAt = now,
                        notBefore = now,
                    ),
                )
            }
            id
        }
    }

    fun observeRecentBroadcasts(limit: Int): Flow<List<BroadcastEntity>> = db.broadcasts().observeRecent(limit)

    // ---- Used by the outbox ----

    suspend fun queued(): List<MessageEntity> = messages.withStatusOnly(MessageStatus.QUEUED)

    /**
     * Every try that counts against the limits: today's and the last 30 minutes'. A clock moved
     * back can leave some in the future, and those mustn't freeze sending until it catches up.
     */
    suspend fun sendsForPacing(): List<Pacing.Sent> {
        val now = clock.nowMillis()
        val since = minOf(Dates.millisAt(clock.today(), 0), now - Pacing.WINDOW_MILLIS)
        return db.sendAttempts().since(since).filter { it.at <= now }.map { Pacing.Sent(it.at, it.parts) }
    }

    /** Marked before the radio call, so a crash can tell "never sent" from "unknown" (SPEC 8.5). */
    suspend fun markSending(id: Long, attempt: Int) {
        val now = clock.nowMillis()
        db.withTransaction {
            val message = messages.get(id) ?: return@withTransaction
            messages.markSendingAt(id, attempt, now)
            db.sendAttempts().insert(SendAttemptEntity(messageId = id, attempt = attempt, at = now, parts = message.parts))
        }
    }

    /** The radio took it. Its real part count replaces the estimate. */
    suspend fun markSubmitted(id: Long, parts: Int) {
        val now = clock.nowMillis()
        db.withTransaction {
            val message = messages.get(id) ?: return@withTransaction
            messages.markSubmitted(id, parts, now)
            db.sendAttempts().setParts(id, message.attempts, parts)
        }
    }

    /** The radio refused at once, for example no signal: back in the queue with a wait. */
    suspend fun markSendProblem(id: Long, reason: String): Long? = db.withTransaction {
        val message = messages.get(id) ?: return@withTransaction null
        scheduleRetryOrFail(message, reason)
    }

    /** Not this message's fault (no permission, no number set). It keeps its place and its tries. */
    suspend fun putBack(id: Long, reason: String) {
        db.withTransaction {
            val message = messages.get(id) ?: return@withTransaction
            db.sendAttempts().remove(id, message.attempts)
            messages.putBack(id, reason)
        }
    }

    /**
     * One part's result. Only when every part has reported does the message settle: SENT if all
     * were fine, otherwise a retry or FAILED. A result from an older attempt is ignored, but a
     * late one still counts: a row given up on can turn out to have gone after all.
     */
    suspend fun onPartResult(id: Long, attempt: Int, partCount: Int, ok: Boolean, reason: String?): Long? = db.withTransaction {
        val message = messages.get(id) ?: return@withTransaction null
        val awaited = message.status == MessageStatus.SENDING || message.gaveUpWaiting
        if (message.attempts != attempt || !awaited) return@withTransaction null
        val updated = message.copy(
            resultParts = message.resultParts + 1,
            failedParts = message.failedParts + if (ok) 0 else 1,
            lastError = if (ok) message.lastError else reason,
        )
        if (updated.resultParts < partCount) {
            messages.update(updated)
            return@withTransaction null
        }
        if (updated.failedParts == 0) {
            val now = clock.nowMillis()
            // A delivery report can overtake the sent result.
            val delivered = updated.deliveredParts >= partCount
            messages.update(
                updated.copy(
                    status = if (delivered) MessageStatus.DELIVERED else MessageStatus.SENT,
                    sentAt = now,
                    deliveredAt = if (delivered) now else updated.deliveredAt,
                    lastError = null,
                ),
            )
            null
        } else {
            scheduleRetryOrFail(updated, updated.lastError ?: "Could not send")
        }
    }

    /** Not every carrier sends these, so a missing report is never treated as a failure. */
    suspend fun onDelivered(id: Long, attempt: Int, partCount: Int) {
        val now = clock.nowMillis()
        db.withTransaction {
            val message = messages.get(id) ?: return@withTransaction
            if (message.attempts != attempt) return@withTransaction
            val delivered = message.deliveredParts + 1
            val done = delivered >= partCount && (message.status == MessageStatus.SENT || message.gaveUpWaiting)
            messages.update(
                message.copy(
                    deliveredParts = delivered,
                    status = if (done) MessageStatus.DELIVERED else message.status,
                    sentAt = if (done) message.sentAt ?: now else message.sentAt,
                    deliveredAt = if (done) now else message.deliveredAt,
                    lastError = if (done) null else message.lastError,
                ),
            )
        }
    }

    private val MessageEntity.gaveUpWaiting: Boolean
        get() = status == MessageStatus.FAILED && lastError == NO_RESULT

    /** Returns when to try again, or null if it's used up its tries (SPEC 8.5). */
    private suspend fun scheduleRetryOrFail(message: MessageEntity, reason: String): Long? {
        val delay = RetryPolicy.delayAfter(message.attempts)
        return if (delay == null) {
            messages.update(message.copy(status = MessageStatus.FAILED, lastError = reason))
            null
        } else {
            val at = clock.nowMillis() + delay
            messages.update(
                message.copy(status = MessageStatus.QUEUED, notBefore = at, submittedAt = null, resultParts = 0, failedParts = 0, deliveredParts = 0, lastError = reason),
            )
            at
        }
    }

    /**
     * After a restart: rows stuck in SENDING. If the radio never took one, it's safe to queue
     * again. If it did and no result came, it may have gone, so a person decides: sending it
     * again automatically could double-send (SPEC 8.5, docs/DECISIONS.md).
     */
    suspend fun recoverStuck() {
        val cutoff = clock.nowMillis() - SendingRules.STUCK_AFTER_MILLIS
        db.withTransaction {
            messages.withStatusOnly(MessageStatus.SENDING).filter { (it.submittedAt ?: it.attemptStartedAt) < cutoff }
                .forEach { message ->
                    if (message.submittedAt == null) {
                        messages.update(
                            message.copy(status = MessageStatus.QUEUED, attempts = (message.attempts - 1).coerceAtLeast(0), resultParts = 0, failedParts = 0, deliveredParts = 0),
                        )
                    } else {
                        messages.update(message.copy(status = MessageStatus.FAILED, lastError = NO_RESULT))
                    }
                }
            // Nothing waits more than a few minutes on purpose, so a far-off time means the clock
            // jumped ahead and came back. A reminder is written again truthfully, the rest just go.
            val now = clock.nowMillis()
            messages.withStatusOnly(MessageStatus.QUEUED).filter { it.notBefore > now + FAR_OFF_MILLIS }.forEach { message ->
                if (message.kind.isPlanned) {
                    messages.cancel(message.id, "Replaced by today's plan")
                } else {
                    messages.update(message.copy(notBefore = now))
                }
            }
        }
    }

    // ---- From the Messages screen ----

    enum class RetryResult { SENDING_AGAIN, REWORDED, NOT_DUE }

    /**
     * Tries again. A reminder from an earlier day is worded for that day ("due today"), so it's
     * planned afresh and may no longer be needed: every SMS stays truthful (SPEC 8.2).
     */
    suspend fun retry(id: Long): RetryResult {
        val now = clock.nowMillis()
        val today = clock.today()
        return db.withTransaction {
            val message = messages.get(id)?.takeIf { it.status == MessageStatus.FAILED }
                ?: return@withTransaction RetryResult.NOT_DUE
            if (message.kind.isPlanned && Dates.dayOf(message.createdAt) < today) {
                messages.cancel(id, "Replaced by today's plan")
                val plan = planAndQueue()
                return@withTransaction if (plan.messages.any { it.memberId == message.memberId }) RetryResult.REWORDED else RetryResult.NOT_DUE
            }
            messages.update(
                message.copy(status = MessageStatus.QUEUED, attempts = 0, notBefore = now, submittedAt = null, resultParts = 0, failedParts = 0, deliveredParts = 0, lastError = null),
            )
            RetryResult.SENDING_AGAIN
        }
    }

    /** That message stays cancelled. Later reminders in the schedule still go. */
    suspend fun cancel(id: Long) = messages.cancelForGood(id, "Cancelled by you")

    /** Stops whatever hasn't gone yet. Returns how many that was. */
    suspend fun cancelBroadcast(broadcastId: Long): Int = messages.cancelQueuedBroadcast(broadcastId, "Cancelled by you")

    suspend fun retryBroadcast(broadcastId: Long): Int {
        val failed = messages.failedInBroadcast(broadcastId)
        failed.forEach { retry(it) }
        return failed.size
    }

    // ---- Templates (SPEC 8.6) ----

    /** A kind added in an update shows with the pack's wording until it's saved (TemplatePacks.withMissing). */
    fun observeTemplates(): Flow<List<TemplateConfig>> =
        combine(db.templates().observeAll(), settings.settings.map { it.packLanguage }.distinctUntilChanged()) { rows, language ->
            TemplatePacks.withMissing(rows.map { it.toConfig() }, language)
        }

    suspend fun saveTemplate(config: TemplateConfig) = db.templates().upsert(config.toEntity())

    /** Changes the language and wording. What's on or off, and when things send, stays. */
    suspend fun switchPack(language: PackLanguage) {
        val preset = TemplatePacks.defaults(language).associateBy { it.kind }
        db.withTransaction {
            db.templates().all().forEach { row ->
                preset[row.type]?.let { db.templates().upsert(row.copy(body = it.body)) }
            }
        }
        settings.update { it.copy(packLanguage = language) }
    }

    /** Back to the pack exactly as it came: wording, schedule, and everything on. */
    suspend fun resetPack(language: PackLanguage) {
        db.withTransaction { presetTemplates(language).forEach { db.templates().upsert(it) } }
        settings.update { it.copy(packLanguage = language) }
    }

    // ---- Quiet days (SPEC 8.3) ----

    fun observeQuietDatesFrom(day: Long): Flow<List<QuietDateEntity>> = db.quietDates().observeFrom(day)

    /** A date can only be in the list once, so adding it again just renames it. */
    suspend fun addQuietDate(date: Long, label: String) {
        db.withTransaction {
            val existing = db.quietDates().onDate(date)
            db.quietDates().upsert(existing?.copy(label = label.trim()) ?: QuietDateEntity(date = date, label = label.trim()))
        }
    }

    suspend fun removeQuietDate(id: Long) = db.quietDates().delete(id)

    suspend fun failedSince(since: Long): Int = messages.countFailedSince(since)

    companion object {
        private const val HISTORY_DAYS = 400L
        private const val FAR_OFF_MILLIS = 24L * 60 * 60 * 1000
        const val NO_RESULT = "The phone never confirmed this one. It may have gone. Retry only if needed."
        private val plannedKinds = MessageKind.entries.filter { it.isPlanned }
    }
}

fun TemplateEntity.toConfig() = TemplateConfig(type, body, enabled, offsetDays, repeatEveryDays, maxRepeats)

/** A kind's saved template, or the pack's preset when this database has none yet. */
suspend fun TemplateDao.getOrPreset(kind: MessageKind, language: PackLanguage): TemplateConfig? =
    get(kind)?.toConfig() ?: TemplatePacks.preset(kind, language)

fun TemplateConfig.toEntity() = TemplateEntity(kind, body, enabled, offsetDays, repeatEveryDays, maxRepeats)

/** Replaces the wording and schedule with a preset (SPEC 8.6). */
fun presetTemplates(language: PackLanguage): List<TemplateEntity> =
    TemplatePacks.defaults(language).map { it.toEntity() }
