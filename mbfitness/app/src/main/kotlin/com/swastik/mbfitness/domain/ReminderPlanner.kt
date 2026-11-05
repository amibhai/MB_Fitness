package com.swastik.mbfitness.domain

/** The latest payment that left money owing. BALANCE_DUE counts from here. */
data class PartPayment(val paymentId: Long, val paidOn: Long)

data class PlannerMember(
    val id: Long,
    val fullName: String,
    val phone: String,
    val state: MemberState,
    val expiry: Long,
    val balance: Long,
    val renewalFee: Long,
    val planName: String,
    val dob: Long? = null,
    val smsConsent: Boolean = true,
    val remindersEnabled: Boolean = true,
    val lastPartPayment: PartPayment? = null,
)

data class PlannerSettings(
    val gymName: String,
    val upiId: String,
    val trainerPhone: String,
    val language: PackLanguage,
)

/** [usedKeys] holds the dedupe key of every message that wasn't cancelled. */
data class PlannerHistory(
    val usedKeys: Set<String> = emptySet(),
    val lastReminderDay: Map<Long, Long> = emptyMap(),
)

data class PlannerInput(
    val today: Long,
    val members: List<PlannerMember>,
    val templates: List<TemplateConfig>,
    val history: PlannerHistory,
    val quiet: QuietCalendar,
    val settings: PlannerSettings,
    val ignoreQuietDay: Boolean = false,
)

data class PlannedMessage(
    val memberId: Long,
    val phone: String,
    val kind: MessageKind,
    val body: String,
    val forExpiry: Long?,
    val dedupeKey: String,
)

/** A reminder that can't go yet because a setting it needs is blank. Sends once it's set. */
data class HeldReminder(val memberId: Long, val kind: MessageKind, val missing: Set<String>)

data class PlanResult(val messages: List<PlannedMessage>, val held: List<HeldReminder>)

object DedupeKeys {
    fun expiry(memberId: Long, kind: MessageKind, expiry: Long, n: Int? = null): String =
        "m$memberId:$kind:e$expiry" + (n?.let { ":n$it" } ?: "")

    fun balance(memberId: Long, paymentId: Long, n: Int): String =
        "m$memberId:${MessageKind.BALANCE_DUE}:p$paymentId:n$n"

    fun birthday(memberId: Long, year: Int): String = "m$memberId:${MessageKind.BIRTHDAY}:y$year"

    fun thanks(memberId: Long, paymentId: Long): String =
        "m$memberId:${MessageKind.PAYMENT_THANKS}:p$paymentId"
}

/**
 * Who gets what today (SPEC 8.2). A pure function: the same input always gives the same
 * output, and running it twice on one day plans nothing new because of the dedupe keys.
 */
object ReminderPlanner {
    /** Older schedule points are dropped, so an import can't text long-gone members. */
    const val MAX_CATCH_UP_AGE_DAYS = 7L

    /** A balance reminder keeps this far from any other reminder to the same member. */
    const val BALANCE_MIN_GAP_DAYS = 3L

    private data class Point(val kind: MessageKind, val day: Long, val key: String)

    private data class Candidate(val kind: MessageKind, val key: String, val forExpiry: Long?)

    fun plan(input: PlannerInput): PlanResult {
        val templates = input.templates.filter { it.enabled }.associateBy { it.kind }
        val quietToday = !input.ignoreQuietDay && input.quiet.isQuiet(input.today)
        val messages = mutableListOf<PlannedMessage>()
        val held = mutableListOf<HeldReminder>()

        for (member in input.members.sortedBy { it.id }) {
            val eligible = member.state == MemberState.ACTIVE && member.smsConsent && member.remindersEnabled
            if (!eligible || input.history.lastReminderDay[member.id] == input.today) continue

            // In priority order. Only birthdays are allowed on a quiet day (SPEC 8.3).
            val candidates = buildList {
                if (!quietToday) {
                    expiryCandidate(member, input, templates)?.let { add(it) }
                    balanceCandidate(member, input, templates)?.let { add(it) }
                }
                birthdayCandidate(member, input, templates)?.let { add(it) }
            }

            for (candidate in candidates) {
                val template = templates.getValue(candidate.kind)
                val rendered = TemplateEngine.render(template.body, valuesFor(member, input), input.settings.language)
                if (rendered.missing.isEmpty()) {
                    messages += PlannedMessage(
                        member.id, member.phone, candidate.kind, rendered.text, candidate.forExpiry, candidate.key,
                    )
                    break
                }
                held += HeldReminder(member.id, candidate.kind, rendered.missing)
            }
        }
        return PlanResult(messages, held)
    }

    /** What the next normal day would send, for "6 will go tomorrow" (SPEC 8.3). */
    fun planForNextNormalDay(input: PlannerInput): Pair<Long, PlanResult>? {
        val day = input.quiet.nextNormalDay(input.today) ?: return null
        return day to plan(input.copy(today = day))
    }

    private fun valuesFor(member: PlannerMember, input: PlannerInput) = TemplateValues(
        fullName = member.fullName,
        amount = maxOf(member.renewalFee + member.balance, 0),
        balance = member.balance,
        plan = member.planName,
        dueDate = Dates.formatShort(member.expiry),
        daysOverdue = maxOf(input.today - member.expiry, 0),
        gymName = input.settings.gymName,
        upiId = input.settings.upiId,
        trainerPhone = input.settings.trainerPhone,
    )

    private fun expiryPoints(member: PlannerMember, templates: Map<MessageKind, TemplateConfig>): List<Point> =
        buildList {
            for (kind in listOf(MessageKind.BEFORE_DUE, MessageKind.ON_DUE, MessageKind.OVERDUE_1)) {
                val template = templates[kind] ?: continue
                val day = if (kind == MessageKind.ON_DUE) member.expiry else member.expiry + template.offsetDays
                add(Point(kind, day, DedupeKeys.expiry(member.id, kind, member.expiry)))
            }
            templates[MessageKind.OVERDUE_2]?.let { template ->
                repeat(template.maxRepeats ?: 1) { index ->
                    val day = member.expiry + template.offsetDays + index * (template.repeatEveryDays ?: 0)
                    add(Point(MessageKind.OVERDUE_2, day, DedupeKeys.expiry(member.id, MessageKind.OVERDUE_2, member.expiry, index + 1)))
                }
            }
        }.sortedBy { it.day }

    /**
     * The latest point due that hasn't gone. Earlier ones are skipped for good, so a phone
     * that was off sends one truthful message, not a burst of old ones (SPEC 8.2).
     */
    private fun expiryCandidate(
        member: PlannerMember,
        input: PlannerInput,
        templates: Map<MessageKind, TemplateConfig>,
    ): Candidate? {
        val today = input.today
        val latest = expiryPoints(member, templates).lastOrNull { it.day <= today } ?: return null
        val used = input.history.usedKeys
        if (today - latest.day > MAX_CATCH_UP_AGE_DAYS || latest.key in used) return null

        val truthfulKind = truthfulKind(latest.kind, today, member.expiry)
        if (truthfulKind == latest.kind) return Candidate(latest.kind, latest.key, member.expiry)
        if (truthfulKind !in templates) return null
        val key = DedupeKeys.expiry(member.id, truthfulKind, member.expiry)
        return if (key in used) null else Candidate(truthfulKind, key, member.expiry)
    }

    /** "Due today" sent late would be false, so it moves on to wording that is true today. */
    private fun truthfulKind(kind: MessageKind, today: Long, expiry: Long): MessageKind = when {
        kind == MessageKind.BEFORE_DUE && today == expiry -> MessageKind.ON_DUE
        (kind == MessageKind.BEFORE_DUE || kind == MessageKind.ON_DUE) && today > expiry -> MessageKind.OVERDUE_1
        else -> kind
    }

    private fun balanceCandidate(
        member: PlannerMember,
        input: PlannerInput,
        templates: Map<MessageKind, TemplateConfig>,
    ): Candidate? {
        val template = templates[MessageKind.BALANCE_DUE] ?: return null
        val part = member.lastPartPayment ?: return null
        val today = input.today
        if (member.balance <= 0 || today > member.expiry) return null
        val lastReminder = input.history.lastReminderDay[member.id]
        if (lastReminder != null && today - lastReminder < BALANCE_MIN_GAP_DAYS) return null

        val every = template.repeatEveryDays ?: 0
        val latest = (0 until (template.maxRepeats ?: 1))
            .map { index -> index to part.paidOn + template.offsetDays + index * every }
            .lastOrNull { (_, day) -> day <= today } ?: return null
        val (index, day) = latest
        val key = DedupeKeys.balance(member.id, part.paymentId, index + 1)
        if (today - day > MAX_CATCH_UP_AGE_DAYS || key in input.history.usedKeys) return null
        return Candidate(MessageKind.BALANCE_DUE, key, member.expiry)
    }

    private fun birthdayCandidate(
        member: PlannerMember,
        input: PlannerInput,
        templates: Map<MessageKind, TemplateConfig>,
    ): Candidate? {
        if (MessageKind.BIRTHDAY !in templates) return null
        val dob = member.dob ?: return null
        if (!Dates.isBirthday(dob, input.today)) return null
        val key = DedupeKeys.birthday(member.id, Dates.year(input.today))
        return if (key in input.history.usedKeys) null else Candidate(MessageKind.BIRTHDAY, key, null)
    }
}
