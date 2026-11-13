package com.swastik.mbfitness.domain

import kotlin.random.Random

/**
 * What happens when an SMS fails (SPEC 8.5): three tries in all, 5 and then 30 minutes apart. The
 * spec also lists a 2-hour wait, which "max 3 attempts" never reaches (docs/DECISIONS.md).
 */
object RetryPolicy {
    private const val MINUTE = 60_000L
    private val DELAYS = listOf(5 * MINUTE, 30 * MINUTE)

    /** How long to wait after this many failed tries, or null to give up. */
    fun delayAfter(attempts: Int): Long? = DELAYS.getOrNull(attempts - 1)
}

/** Sending rules that aren't about limits. */
object SendingRules {
    /** Rows stuck in SENDING longer than this are looked at again on restart (SPEC 8.5). */
    const val STUCK_AFTER_MILLIS = 10L * 60 * 1000

    /** 3 to 5 seconds, so the carrier sees ordinary traffic (SPEC 8.4). */
    fun spacingMillis(random: Random = Random): Long =
        random.nextLong(Pacing.MIN_SPACING_MILLIS, Pacing.MAX_SPACING_MILLIS + 1)

    /**
     * "[TEST > Rahul] Hi Rahul, ...". SPEC 8.11 used an arrow, but that forces Unicode, which
     * would make every test SMS cost more parts than the real one (docs/DECISIONS.md).
     */
    fun testBody(memberName: String?, body: String): String {
        val who = memberName?.let(TemplateEngine::firstName)?.takeIf { it.isNotEmpty() } ?: "Test"
        return "[TEST > $who] $body"
    }
}

enum class RunHealth { OK, LATE, VERY_LATE }

/** When the daily run should happen, and whether it did (SPEC 9). */
object ReminderSchedule {
    private const val HOUR_MILLIS = 60L * 60 * 1000
    private const val LATE_AFTER_MILLIS = 26 * HOUR_MILLIS
    private const val VERY_LATE_AFTER_MILLIS = 50 * HOUR_MILLIS
    const val PROBLEM_AFTER_DAYS = 2L
    private const val EARLIEST_HOUR = Pacing.PLANNED_FROM_HOUR
    private const val LATEST_HOUR = 20

    /**
     * Reminders only go between 8 AM and 9 PM, so a run after that could never send anything:
     * each day's reminders would wait for a morning when they were already out of date. A
     * mistaken 9:00 PM instead of 9:00 AM is easy to pick, so the picker refuses it.
     */
    fun isAllowedTime(hour: Int, minute: Int): Boolean =
        minute in 0..59 && (hour in EARLIEST_HOUR until LATEST_HOUR || (hour == LATEST_HOUR && minute == 0))

    const val ALLOWED_TIME_TEXT = "Choose a time from 8:00 AM to 8:00 PM. Reminders only go out between 8 AM and 9 PM."

    /** The next reminder time after [nowMillis]. */
    fun nextRunAt(nowMillis: Long, hour: Int, minute: Int): Long {
        val today = Dates.dayOf(nowMillis)
        val todayAt = Dates.millisAt(today, hour, minute)
        return if (todayAt > nowMillis) todayAt else Dates.millisAt(today + 1, hour, minute)
    }

    /** Due if today's run hasn't happened and it's past the reminder time. */
    fun isRunDue(nowMillis: Long, lastRunDay: Long?, hour: Int, minute: Int): Boolean {
        val today = Dates.dayOf(nowMillis)
        return lastRunDay != today && nowMillis >= Dates.millisAt(today, hour, minute)
    }

    /**
     * Home shows amber after 26 hours and red after 50 (SPEC 9). Before the first run, the clock
     * starts when setup finished ([since]), so reminders that never ran once still show up.
     */
    fun health(nowMillis: Long, lastRunAt: Long?, since: Long? = null): RunHealth {
        val age = nowMillis - (lastRunAt ?: since ?: return RunHealth.OK)
        return when {
            age > VERY_LATE_AFTER_MILLIS -> RunHealth.VERY_LATE
            age > LATE_AFTER_MILLIS -> RunHealth.LATE
            else -> RunHealth.OK
        }
    }

    /** "Reminders last ran: today 9:00 AM" */
    fun lastRanText(nowMillis: Long, lastRunAt: Long?): String {
        if (lastRunAt == null) return "Reminders haven't run yet"
        val day = Dates.dayOf(lastRunAt)
        val today = Dates.dayOf(nowMillis)
        val whenText = when (day) {
            today -> "today ${Dates.formatTime(lastRunAt)}"
            today - 1 -> "yesterday ${Dates.formatTime(lastRunAt)}"
            else -> "${Dates.formatShort(day)} ${Dates.formatTime(lastRunAt)}"
        }
        return "Reminders last ran: $whenText"
    }

    /**
     * The "reminders didn't run for 2 days" problem notice (SPEC 8.10). Before the first run, it
     * counts from when setup finished ([setupDoneAt]).
     */
    fun hasStopped(nowMillis: Long, lastRunDay: Long?, setupDoneAt: Long? = null): Boolean {
        val since = lastRunDay ?: setupDoneAt?.let(Dates::dayOf) ?: return false
        return Dates.dayOf(nowMillis) - since >= PROBLEM_AFTER_DAYS
    }
}

data class SummaryFacts(
    val due: Int,
    val overdue: Int,
    val balancePending: Long,
    val onTheirWay: Int,
    val failed: Int,
    val quietReason: String? = null,
    val willGoNext: Int? = null,
    val nextIsTomorrow: Boolean = true,
    val birthdays: List<String> = emptyList(),
    val held: Int = 0,
)

data class SummaryText(val title: String, val detail: String, val opensMessages: Boolean)

/** The morning notification (SPEC 8.10). Emoji are allowed here, and only here. */
object MorningSummary {
    fun build(facts: SummaryFacts): SummaryText {
        val title = "Today: ${facts.due} due · ${facts.overdue} overdue · ${Money.format(facts.balancePending)} balance pending"
        val first = when {
            facts.failed > 0 -> "⚠ ${facts.failed} failed. Tap to retry."
            facts.quietReason != null -> {
                val next = facts.willGoNext ?: 0
                val whenText = if (facts.nextIsTomorrow) "tomorrow" else "next"
                "Quiet day (${facts.quietReason}) · $next will go $whenText"
            }
            facts.held > 0 -> "⚠ ${facts.held} waiting. Add your phone number in Settings."
            facts.onTheirWay > 0 -> "${facts.onTheirWay} ${if (facts.onTheirWay == 1) "reminder" else "reminders"} on their way"
            else -> "No reminders today"
        }
        val lines = buildList {
            add(first)
            if (facts.birthdays.isNotEmpty()) add("🎂 Birthday: ${facts.birthdays.joinToString(", ")}")
        }
        return SummaryText(title, lines.joinToString(" · "), opensMessages = facts.failed > 0)
    }

    /** Whether there's anything to say. Nothing at all only notifies if All clear is on. */
    fun isAllClear(facts: SummaryFacts): Boolean =
        facts.due == 0 && facts.overdue == 0 && facts.balancePending == 0L && facts.onTheirWay == 0 &&
            facts.failed == 0 && facts.quietReason == null && facts.birthdays.isEmpty() && facts.held == 0
}

enum class BroadcastAudience { ALL_ACTIVE, OVERDUE, DUE_SOON, BALANCE_PENDING, FROZEN, HAND_PICKED }

data class AudienceMember(
    val id: Long,
    val name: String,
    val phone: String,
    val state: MemberState,
    val expiry: Long,
    val balance: Long,
    val smsConsent: Boolean,
    val remindersEnabled: Boolean = true,
)

/** The gym's name goes in every message, so the receiver knows who it's from (SPEC 8.9). */
object SenderLine {
    fun add(text: String, gymName: String): String {
        val trimmed = text.trim()
        return if (gymName.isBlank() || trimmed.contains(gymName, ignoreCase = true)) trimmed else "$trimmed - $gymName"
    }
}

/** Who a broadcast reaches (SPEC 8.8, 8.9). */
object BroadcastRules {
    /**
     * Members without SMS consent never get one, whatever the audience. Members with reminders
     * off do, unless [skipRemindersOff] is set (SPEC 8.8).
     */
    fun recipients(
        audience: BroadcastAudience,
        members: List<AudienceMember>,
        pickedIds: Set<Long>,
        today: Long,
        skipRemindersOff: Boolean = false,
    ): List<AudienceMember> = members.filter { member ->
        member.smsConsent && (member.remindersEnabled || !skipRemindersOff) && when (audience) {
            BroadcastAudience.ALL_ACTIVE -> member.state == MemberState.ACTIVE
            BroadcastAudience.OVERDUE -> MemberRules.tab(member.state, member.expiry, today) == MemberTab.OVERDUE
            BroadcastAudience.DUE_SOON -> MemberRules.tab(member.state, member.expiry, today) == MemberTab.DUE_SOON
            BroadcastAudience.BALANCE_PENDING -> member.state == MemberState.ACTIVE && member.balance > 0
            BroadcastAudience.FROZEN -> member.state == MemberState.FROZEN
            BroadcastAudience.HAND_PICKED -> member.id in pickedIds
        }
    }

    /** What one member would receive. [RenderResult.missing] means it can't go yet. */
    fun render(template: String, memberName: String, gymName: String, trainerPhone: String, language: PackLanguage): RenderResult {
        val values = TemplateValues(fullName = memberName, gymName = gymName, trainerPhone = trainerPhone)
        val result = TemplateEngine.render(TemplateEngine.straightenQuotes(template), values, language)
        return result.copy(text = SenderLine.add(result.text, gymName))
    }

    /** Ex-members can be picked by hand, but it deserves a warning (SPEC 8.9). */
    fun picksLeftMembers(members: List<AudienceMember>, pickedIds: Set<Long>): Boolean =
        members.any { it.id in pickedIds && it.state == MemberState.LEFT }
}

/** The schedule fields in the template editor (SPEC 8.6). */
object TemplateRules {
    private val OFFSET_LIMITS = 1..60
    private val REPEAT_LIMITS = 1..30
    private val MAX_REPEAT_LIMITS = 1..12

    fun scheduleProblem(kind: MessageKind, offsetDays: Int, repeatEveryDays: Int?, maxRepeats: Int?): String? {
        when (kind) {
            MessageKind.BEFORE_DUE -> if (-offsetDays !in OFFSET_LIMITS) return "Must be 1 to 60 days before expiry"
            MessageKind.OVERDUE_1, MessageKind.OVERDUE_2, MessageKind.BALANCE_DUE ->
                if (offsetDays !in OFFSET_LIMITS) return "Must be 1 to 60 days after"
            else -> Unit
        }
        if (repeatEveryDays != null && repeatEveryDays !in REPEAT_LIMITS) return "Repeat must be every 1 to 30 days"
        if (maxRepeats != null && maxRepeats !in MAX_REPEAT_LIMITS) return "Can repeat 1 to 12 times"
        return null
    }

    /**
     * The placeholders each kind can really fill. A reminder with {receipt_no} would have nothing
     * to put there and would wait forever, so the editor refuses it (SPEC 8.6).
     */
    fun placeholdersFor(kind: MessageKind): Set<String> = when (kind) {
        MessageKind.OVERDUE_1, MessageKind.OVERDUE_2 -> TemplateEngine.ALL_PLACEHOLDERS - "receipt_no"
        MessageKind.BEFORE_DUE, MessageKind.ON_DUE, MessageKind.BALANCE_DUE, MessageKind.BIRTHDAY ->
            TemplateEngine.ALL_PLACEHOLDERS - setOf("receipt_no", "days_overdue")
        MessageKind.PAYMENT_THANKS -> TemplateEngine.ALL_PLACEHOLDERS - setOf("plan", "days_overdue")
        MessageKind.WELCOME -> TemplateEngine.BROADCAST_PLACEHOLDERS + "full_name"
        MessageKind.BROADCAST -> TemplateEngine.BROADCAST_PLACEHOLDERS
        MessageKind.MANUAL, MessageKind.TEST -> emptySet()
    }

    fun describe(problem: TemplateProblem): String = when (problem) {
        TemplateProblem.Empty -> "Write a message first"
        is TemplateProblem.UnknownPlaceholder -> "{${problem.name}} can't be used here"
        TemplateProblem.ContainsLink -> "Links aren't allowed. They get personal numbers flagged as spam."
        is TemplateProblem.TooManyParts -> "Too long: ${problem.parts} SMS parts. The limit is ${SmsSegments.BLOCK_ABOVE_PARTS}."
    }

    /** "This costs 3 SMS per member", once a message runs long (SPEC 8.7). */
    fun lengthWarning(parts: Int): String? =
        if (parts > SmsSegments.WARN_ABOVE_PARTS) "This costs $parts SMS per member. Shorter is better." else null

    /** What the setting a blank placeholder is waiting for is called. */
    fun describeMissing(missing: Set<String>): String? = when {
        missing.isEmpty() -> null
        "trainer_phone" in missing -> "This can't go until you add your phone number in Settings."
        "upi_id" in missing -> "This can't go until you add your UPI ID in Settings."
        "gym_name" in missing -> "This can't go until you add the gym's name in Settings."
        else -> "This can't go yet: ${missing.joinToString { "{$it}" }} has no value."
    }

    /** Only these can be moved. On-due always means the expiry day. */
    fun hasOffset(kind: MessageKind): Boolean =
        kind == MessageKind.BEFORE_DUE || kind == MessageKind.OVERDUE_1 ||
            kind == MessageKind.OVERDUE_2 || kind == MessageKind.BALANCE_DUE

    fun hasRepeat(kind: MessageKind): Boolean = kind == MessageKind.OVERDUE_2 || kind == MessageKind.BALANCE_DUE
}

/** Words for the Messages screen (SPEC 10.2 screen 11). */
object MessageLabels {
    fun kind(kind: MessageKind): String = when (kind) {
        MessageKind.BEFORE_DUE -> "Before due"
        MessageKind.ON_DUE -> "Due today"
        MessageKind.OVERDUE_1 -> "Overdue"
        MessageKind.OVERDUE_2 -> "Overdue repeat"
        MessageKind.BALANCE_DUE -> "Balance"
        MessageKind.PAYMENT_THANKS -> "Thank you"
        MessageKind.WELCOME -> "Welcome"
        MessageKind.BIRTHDAY -> "Birthday"
        MessageKind.BROADCAST -> "Broadcast"
        MessageKind.MANUAL -> "Your SMS"
        MessageKind.TEST -> "Test"
    }

    /** "9:02 AM", "yesterday 9:02 AM", "tomorrow 9:00 AM" or "12 Dec, 9:02 AM". */
    fun whenText(millis: Long, nowMillis: Long): String {
        val time = Dates.formatTime(millis)
        val day = Dates.dayOf(millis)
        return when (day - Dates.dayOf(nowMillis)) {
            0L -> time
            -1L -> "yesterday $time"
            1L -> "tomorrow $time"
            else -> "${Dates.formatShort(day)}, $time"
        }
    }

    fun status(
        status: MessageStatus,
        notBefore: Long,
        sentAt: Long?,
        deliveredAt: Long?,
        lastError: String?,
        nowMillis: Long,
    ): String = when (status) {
        MessageStatus.QUEUED -> {
            val waiting = notBefore > nowMillis
            when {
                lastError != null && waiting -> "$lastError. Tries again ${whenText(notBefore, nowMillis)}."
                lastError != null -> "Waiting: $lastError"
                waiting -> "Goes ${whenText(notBefore, nowMillis)}"
                else -> "Waiting to send"
            }
        }
        MessageStatus.SENDING -> "Sending"
        MessageStatus.SENT -> sentAt?.let { "Sent ${whenText(it, nowMillis)}" } ?: "Sent"
        MessageStatus.DELIVERED -> deliveredAt?.let { "Delivered ${whenText(it, nowMillis)}" } ?: "Delivered"
        MessageStatus.FAILED -> lastError ?: "Could not send"
        MessageStatus.CANCELLED -> lastError ?: "Cancelled"
    }
}
