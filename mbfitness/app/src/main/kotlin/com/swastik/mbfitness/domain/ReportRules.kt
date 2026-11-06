package com.swastik.mbfitness.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ReportPayment(
    val id: Long,
    val memberId: Long,
    val kind: PaymentKind,
    val charged: Long,
    val paid: Long,
    val paidOn: Long,
)

data class ReportEvent(val memberId: Long, val type: MemberEventType, val date: Long)

/** One month on a chart. */
data class MonthPoint(val firstDay: Long, val label: String, val value: Long)

data class MonthReport(
    val firstDay: Long,
    val title: String,
    val collected: Long,
    /** "↑ 12% vs Oct". Null when last month had nothing to compare with. */
    val change: String?,
    val pending: Long,
    val active: Int,
    val newJoins: Int,
    val left: Int,
    val renewals: Int,
    val averagePerMember: Long,
    val collections: List<MonthPoint>,
    val activeMembers: List<MonthPoint>,
)

/** The Reports screen's numbers (SPEC 4-E). Everything is worked out from payments and events. */
object ReportRules {
    private const val MONTHS_ON_CHART = 6
    private val TITLE = DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH)
    private val SHORT = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

    fun firstOfMonth(day: Long): Long = LocalDate.ofEpochDay(day).withDayOfMonth(1).toEpochDay()

    fun lastOfMonth(day: Long): Long = LocalDate.ofEpochDay(day).plusMonths(1).withDayOfMonth(1).toEpochDay() - 1

    fun addMonths(firstDay: Long, months: Int): Long = LocalDate.ofEpochDay(firstDay).plusMonths(months.toLong()).toEpochDay()

    fun monthTitle(firstDay: Long): String = TITLE.format(LocalDate.ofEpochDay(firstDay))

    private fun monthShort(firstDay: Long): String = SHORT.format(LocalDate.ofEpochDay(firstDay))

    /** How far back the month switcher goes: the first month with anything in it. */
    fun earliestMonth(payments: List<ReportPayment>, events: List<ReportEvent>, today: Long): Long {
        val first = (payments.map { it.paidOn } + events.map { it.date }).minOrNull() ?: today
        return firstOfMonth(minOf(first, today))
    }

    fun build(firstDay: Long, today: Long, payments: List<ReportPayment>, events: List<ReportEvent>): MonthReport {
        val lastDay = lastOfMonth(firstDay)
        val collected = collectedIn(firstDay, payments)
        val previous = addMonths(firstDay, -1)
        val active = activeOn(minOf(lastDay, today), events)
        val joined = events.count { it.type == MemberEventType.JOINED && it.date in firstDay..lastDay }
        val left = events.count { it.type == MemberEventType.LEFT && it.date in firstDay..lastDay }
        val months = (MONTHS_ON_CHART - 1 downTo 0).map { addMonths(firstDay, -it) }
        return MonthReport(
            firstDay = firstDay,
            title = monthTitle(firstDay),
            collected = collected,
            change = change(collected, collectedIn(previous, payments), monthShort(previous)),
            pending = pendingOn(minOf(lastDay, today), payments, events),
            active = active,
            newJoins = joined,
            left = left,
            renewals = renewalsIn(firstDay, lastDay, payments),
            averagePerMember = average(collected, active),
            collections = months.map { MonthPoint(it, monthShort(it), collectedIn(it, payments)) },
            activeMembers = months.map { MonthPoint(it, monthShort(it), activeOn(minOf(lastOfMonth(it), today), events).toLong()) },
        )
    }

    private fun collectedIn(firstDay: Long, payments: List<ReportPayment>): Long {
        val lastDay = lastOfMonth(firstDay)
        return payments.filter { it.paidOn in firstDay..lastDay }.sumOf { it.paid }
    }

    /** Rounded to the nearest whole percent. */
    private fun change(current: Long, previous: Long, previousLabel: String): String? {
        if (previous == 0L) return null
        if (current == previous) return "Same as $previousLabel"
        val difference = kotlin.math.abs(current - previous)
        val percent = (difference * PERCENT + previous / 2) / previous
        return if (current > previous) "↑ $percent% vs $previousLabel" else "↓ $percent% vs $previousLabel"
    }

    private fun average(collected: Long, active: Int): Long = if (active == 0) 0 else (collected + active / 2) / active

    /**
     * A member's first renewal is them joining, which is counted separately. An opening balance
     * means they were already a member when the app arrived, so their next one is a renewal.
     */
    private fun renewalsIn(firstDay: Long, lastDay: Long, payments: List<ReportPayment>): Int =
        payments.groupBy { it.memberId }.values.sumOf { rows ->
            val ordered = rows.sortedWith(compareBy({ it.paidOn }, { it.id }))
            ordered.withIndex().count { (index, payment) ->
                payment.kind == PaymentKind.RENEWAL && payment.paidOn in firstDay..lastDay && index > 0 &&
                    ordered.take(index).any { it.kind == PaymentKind.RENEWAL || it.kind == PaymentKind.OPENING }
            }
        }

    /** What a member's state was on [day], by playing their events forward. */
    private fun stateOn(day: Long, memberEvents: List<ReportEvent>): MemberState? {
        var state: MemberState? = null
        var beforeDeleted: MemberState? = null
        memberEvents.filter { it.date <= day }.forEach { event ->
            when (event.type) {
                MemberEventType.JOINED, MemberEventType.IMPORTED, MemberEventType.REJOINED, MemberEventType.UNFROZEN ->
                    state = MemberState.ACTIVE
                MemberEventType.FROZEN -> state = MemberState.FROZEN
                MemberEventType.LEFT -> state = MemberState.LEFT
                MemberEventType.DELETED -> {
                    beforeDeleted = state
                    state = null
                }
                MemberEventType.RESTORED -> state = beforeDeleted
            }
        }
        return state
    }

    private fun activeOn(day: Long, events: List<ReportEvent>): Int =
        events.groupBy { it.memberId }.values.count { stateOn(day, it) == MemberState.ACTIVE }

    /** What was owed by members who hadn't left by [day]. An advance doesn't cancel someone else's debt. */
    private fun pendingOn(day: Long, payments: List<ReportPayment>, events: List<ReportEvent>): Long {
        val states = events.groupBy { it.memberId }.mapValues { (_, rows) -> stateOn(day, rows) }
        return payments.filter { it.paidOn <= day }.groupBy { it.memberId }.entries.sumOf { (memberId, rows) ->
            if (MemberRules.countsAsPending(states[memberId])) MemberRules.due(rows.sumOf { it.charged - it.paid }) else 0L
        }
    }

    private const val PERCENT = 100L
}

/** What the home-screen widget says (SPEC 4-E). Never an Aadhaar number (hard rule 11). */
data class WidgetSummary(val due: Int, val overdue: Int, val pending: Long?, val overdueNames: List<String>) {
    val headline: String get() = "$due due · $overdue overdue"
}

object WidgetRules {
    private const val NAMES_SHOWN = 3

    data class Member(val name: String, val state: MemberState, val expiry: Long, val balance: Long)

    /**
     * With an app PIN set, the person has asked for fees and names to stay private, so only the
     * counts show. Anyone can see a home screen without getting past the lock (docs/DECISIONS.md).
     */
    fun summarize(members: List<Member>, today: Long, pinSet: Boolean): WidgetSummary {
        val tabs = members.map { it to MemberRules.tab(it.state, it.expiry, today) }
        val overdue = tabs.filter { (_, tab) -> tab == MemberTab.OVERDUE }.map { (member, _) -> member }
        return WidgetSummary(
            due = tabs.count { (_, tab) -> tab == MemberTab.DUE_SOON },
            overdue = overdue.size,
            pending = if (pinSet) null else members.filter { MemberRules.countsAsPending(it.state) }.sumOf { MemberRules.due(it.balance) },
            overdueNames = if (pinSet) emptyList() else overdue.sortedBy { it.expiry }.take(NAMES_SHOWN).map { TemplateEngine.firstName(it.name) },
        )
    }
}
