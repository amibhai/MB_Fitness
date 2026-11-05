package com.swastik.mbfitness.domain

/** The status tabs on Home (SPEC 7.2). Derived, never stored. */
enum class MemberTab { OVERDUE, DUE_SOON, PAID, FROZEN_LEFT }

/** One payment as the money rules see it. Soft-deleted payments are never passed in. */
data class LedgerPayment(
    val id: Long,
    val kind: PaymentKind,
    val charged: Long,
    val paid: Long,
    val paidOn: Long,
    /** Length of the plan when this was recorded, so editing a plan never rewrites history. */
    val planMonths: Int? = null,
    /** Renewal rule when this was recorded, so changing the setting never rewrites history. */
    val rule: RenewalRule? = null,
    /** The restart-after-days setting when this was recorded, for the same reason. */
    val restartAfterDays: Int? = null,
    /** OPENING only: the expiry date given in the import file. */
    val openingExpiry: Long? = null,
)

/** A freeze. [to] is null while the member is still frozen. */
data class FreezeSpan(val from: Long, val to: Long?)

data class ReplayedPeriod(val paymentId: Long, val start: Long, val end: Long)

data class ReplayResult(val expiry: Long, val periods: List<ReplayedPeriod>)

/** What a payment leaves behind, shown before saving (SPEC 7.3). */
sealed interface AfterPayment {
    data class Remaining(val amount: Long) : AfterPayment
    data object FullyPaid : AfterPayment
    data class Extra(val amount: Long) : AfterPayment
}

object MemberRules {
    const val DUE_SOON_DAYS = 3L
    const val SUGGEST_LEFT_AFTER_DAYS = 60L
    const val DEFAULT_RESTART_AFTER_DAYS = 60

    fun tab(state: MemberState, expiry: Long, today: Long): MemberTab = when {
        state != MemberState.ACTIVE -> MemberTab.FROZEN_LEFT
        today > expiry -> MemberTab.OVERDUE
        expiry - today <= DUE_SOON_DAYS -> MemberTab.DUE_SOON
        else -> MemberTab.PAID
    }

    /** Positive means money is owed, negative means an advance (SPEC 7.2). */
    fun balance(payments: List<LedgerPayment>): Long = payments.sumOf { it.charged - it.paid }

    /** The "₹X due" badge amount. An advance is shown separately, never as a negative due. */
    fun due(balance: Long): Long = maxOf(balance, 0)

    fun advance(balance: Long): Long = maxOf(-balance, 0)

    /** Never automatic: this only decides whether to show the suggestion banner (SPEC 4-A). */
    fun suggestMarkAsLeft(state: MemberState, expiry: Long, today: Long): Boolean =
        state == MemberState.ACTIVE && today - expiry >= SUGGEST_LEFT_AFTER_DAYS

    /** Default for "amount received" on a renewal: the fee plus anything owed (SPEC 7.3). */
    fun defaultReceived(planPrice: Long, balance: Long): Long = maxOf(planPrice + balance, 0)

    fun afterPayment(balance: Long, charged: Long, received: Long): AfterPayment {
        val left = balance + charged - received
        return when {
            left > 0 -> AfterPayment.Remaining(left)
            left == 0L -> AfterPayment.FullyPaid
            else -> AfterPayment.Extra(-left)
        }
    }

    private sealed interface Step {
        val date: Long
        val order: Int

        data class Rejoin(override val date: Long) : Step {
            override val order = 0
        }

        data class Pay(val payment: LedgerPayment) : Step {
            override val date = payment.paidOn
            override val order = 1
        }

        data class Freeze(override val date: Long, val days: Long) : Step {
            override val order = 2
        }
    }

    /**
     * The single source of truth for expiry (SPEC 7.4): works it out from scratch, in date
     * order, and never looks at the real date or current settings. Each payment carries the
     * rule, plan length and restart limit from when it was recorded, so the answer can only
     * change when the records change. That is why this takes no "today" (docs/DECISIONS.md).
     *
     * On one day, a rejoin comes before a payment (recording a renewal for someone who left
     * rejoins them) and a payment before a freeze (so a freeze that day protects the new
     * period). Payments on one day go in the order they were recorded.
     */
    fun replay(
        joinDate: Long,
        payments: List<LedgerPayment>,
        freezes: List<FreezeSpan> = emptyList(),
        rejoinDates: List<Long> = emptyList(),
    ): ReplayResult {
        val completedFreezes = freezes.mapNotNull { span ->
            span.to?.let { to -> span.from to maxOf(to, span.from) }
        }
        val steps = buildList {
            rejoinDates.forEach { add(Step.Rejoin(it)) }
            payments.filter { it.kind != PaymentKind.BALANCE }.forEach { add(Step.Pay(it)) }
            completedFreezes.forEach { (from, to) -> add(Step.Freeze(from, to - from)) }
        }.sortedWith(
            compareBy<Step>({ it.date }, { it.order }, { (it as? Step.Pay)?.payment?.id ?: 0L }),
        )

        var expiry = joinDate
        var rejoinedSinceLastPayment = false
        val periods = mutableListOf<ReplayedPeriod>()

        for (step in steps) {
            when (step) {
                is Step.Rejoin -> rejoinedSinceLastPayment = true

                is Step.Freeze -> {
                    // A freeze that starts after the membership ran out has nothing to protect.
                    if (step.date <= expiry) expiry += step.days
                }

                is Step.Pay -> {
                    val payment = step.payment
                    val start = if (payment.kind == PaymentKind.OPENING) {
                        expiry = payment.openingExpiry ?: expiry
                        joinDate
                    } else {
                        val start = renewalStart(payment, expiry, rejoinedSinceLastPayment, completedFreezes)
                        expiry = Dates.addMonths(start, requireNotNull(payment.planMonths) {
                            "A renewal must record its plan length"
                        })
                        start
                    }
                    periods += ReplayedPeriod(payment.id, start, expiry)
                    rejoinedSinceLastPayment = false
                }
            }
        }
        return ReplayResult(expiry, periods)
    }

    private fun renewalStart(
        payment: LedgerPayment,
        expiryBefore: Long,
        rejoined: Boolean,
        freezes: List<Pair<Long, Long>>,
    ): Long {
        val rule = payment.rule ?: RenewalRule.CONTINUE_FROM_EXPIRY
        val restartAfter = (payment.restartAfterDays ?: DEFAULT_RESTART_AFTER_DAYS).toLong()
        val awayTooLong = payment.paidOn - expiryBefore > restartAfter
        if (rule == RenewalRule.CONTINUE_FROM_EXPIRY && !rejoined && !awayTooLong) return expiryBefore

        // A period that starts from the payment date must not start inside a freeze: those
        // days would be lost, so it starts when the freeze ended. A freeze beginning on the
        // payment day itself is left out: it comes after the payment and extends the period.
        val during = freezes.firstOrNull { (from, to) -> payment.paidOn > from && payment.paidOn < to }
        return during?.second ?: payment.paidOn
    }
}
