package com.swastik.mbfitness.domain

import com.swastik.mbfitness.domain.MemberRules.replay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberRulesTest {
    private fun d(year: Int, month: Int, day: Int) = Dates.of(year, month, day)
    private val join = d(2027, 1, 1)
    private var nextId = 1L

    private fun renewal(
        paidOn: Long,
        months: Int = 1,
        rule: RenewalRule = RenewalRule.CONTINUE_FROM_EXPIRY,
        restart: Int = 60,
        charged: Long = 1200,
        paid: Long = 1200,
    ) = LedgerPayment(nextId++, PaymentKind.RENEWAL, charged, paid, paidOn, months, rule, restart)

    private val ruleB = RenewalRule.START_FROM_PAYMENT

    @Test
    fun tabsFollowTheStatusTable() {
        val today = d(2027, 3, 10)
        fun tab(daysLeft: Long) = MemberRules.tab(MemberState.ACTIVE, today + daysLeft, today)
        assertEquals(MemberTab.OVERDUE, tab(-1))
        assertEquals(MemberTab.DUE_SOON, tab(0))
        assertEquals(MemberTab.DUE_SOON, tab(3))
        assertEquals(MemberTab.PAID, tab(4))
        assertEquals(MemberTab.FROZEN_LEFT, MemberRules.tab(MemberState.FROZEN, today - 5, today))
        assertEquals(MemberTab.FROZEN_LEFT, MemberRules.tab(MemberState.LEFT, today + 50, today))
    }

    @Test
    fun balanceIsChargedMinusPaid() {
        val part = renewal(join, paid = 500)
        val rest = LedgerPayment(99, PaymentKind.BALANCE, 0, 700, join + 5)
        assertEquals(700L, MemberRules.balance(listOf(part)))
        assertEquals(0L, MemberRules.balance(listOf(part, rest)))
    }

    @Test
    fun overpaymentShowsAsAnAdvanceNeverANegativeDue() {
        val balance = MemberRules.balance(listOf(renewal(join, paid = 1400)))
        assertEquals(-200L, balance)
        assertEquals(0L, MemberRules.due(balance))
        assertEquals(200L, MemberRules.advance(balance))
        assertEquals(700L, MemberRules.due(700))
        assertEquals(0L, MemberRules.advance(700))
    }

    @Test
    fun defaultAmountIsTheFeePlusAnythingOwed() {
        assertEquals(1900L, MemberRules.defaultReceived(1200, 700))
        assertEquals(1000L, MemberRules.defaultReceived(1200, -200))
        assertEquals(0L, MemberRules.defaultReceived(100, -200))
    }

    @Test
    fun saysWhatAPaymentLeavesBehind() {
        assertEquals(AfterPayment.Remaining(700), MemberRules.afterPayment(0, 1200, 500))
        assertEquals(AfterPayment.FullyPaid, MemberRules.afterPayment(0, 1200, 1200))
        assertEquals(AfterPayment.FullyPaid, MemberRules.afterPayment(700, 1200, 1900))
        assertEquals(AfterPayment.Extra(200), MemberRules.afterPayment(0, 1200, 1400))
        assertEquals(AfterPayment.FullyPaid, MemberRules.afterPayment(700, 0, 700))
    }

    @Test
    fun suggestsMarkingAsLeftAfter60DaysButOnlyForActiveMembers() {
        val expiry = d(2027, 1, 1)
        assertFalse(MemberRules.suggestMarkAsLeft(MemberState.ACTIVE, expiry, expiry + 59))
        assertTrue(MemberRules.suggestMarkAsLeft(MemberState.ACTIVE, expiry, expiry + 60))
        assertFalse(MemberRules.suggestMarkAsLeft(MemberState.LEFT, expiry, expiry + 90))
    }

    @Test
    fun withNoPaymentsExpiryIsTheJoinDate() {
        assertEquals(join, replay(join, emptyList()).expiry)
    }

    @Test
    fun ruleAContinuesFromTheOldExpiry() {
        val first = renewal(join)
        val second = renewal(d(2027, 2, 20))
        val result = replay(join, listOf(first, second))
        assertEquals(d(2027, 3, 1), result.expiry)
        assertEquals(ReplayedPeriod(first.id, join, d(2027, 2, 1)), result.periods[0])
        assertEquals(ReplayedPeriod(second.id, d(2027, 2, 1), d(2027, 3, 1)), result.periods[1])
    }

    @Test
    fun ruleAKeepsDaysWhenPayingEarly() {
        assertEquals(d(2027, 3, 1), replay(join, listOf(renewal(join), renewal(d(2027, 1, 25)))).expiry)
    }

    @Test
    fun ruleBStartsFromThePaymentDate() {
        assertEquals(d(2027, 3, 20), replay(join, listOf(renewal(join), renewal(d(2027, 2, 20), rule = ruleB))).expiry)
        assertEquals(d(2027, 2, 25), replay(join, listOf(renewal(join), renewal(d(2027, 1, 25), rule = ruleB))).expiry)
    }

    @Test
    fun aMemberAwayPastTheLimitRestartsFromThePaymentDate() {
        val expiry = d(2027, 2, 1)
        val atLimit = replay(join, listOf(renewal(join), renewal(expiry + 60)))
        assertEquals(d(2027, 3, 1), atLimit.expiry)
        val pastLimit = replay(join, listOf(renewal(join), renewal(expiry + 61)))
        assertEquals(Dates.addMonths(expiry + 61, 1), pastLimit.expiry)
    }

    @Test
    fun rejoiningStartsTheNextPeriodFromThePaymentDate() {
        val first = renewal(join)
        val back = renewal(d(2027, 2, 12))
        assertEquals(d(2027, 3, 12), replay(join, listOf(first, back), rejoinDates = listOf(d(2027, 2, 10))).expiry)
        // Paid and rejoined on the same day: the rejoin counts first.
        val sameDay = renewal(d(2027, 2, 10))
        assertEquals(d(2027, 3, 10), replay(join, listOf(first, sameDay), rejoinDates = listOf(d(2027, 2, 10))).expiry)
    }

    @Test
    fun rejoiningOnlyAffectsTheFirstPaymentAfterIt() {
        val payments = listOf(renewal(join), renewal(d(2027, 2, 12)), renewal(d(2027, 3, 1)))
        assertEquals(d(2027, 4, 12), replay(join, payments, rejoinDates = listOf(d(2027, 2, 10))).expiry)
    }

    @Test
    fun oldPaymentsKeepTheRuleTheyWereRecordedWith() {
        // Bhaiya switched from B to A in between. The first payment must not change.
        val underB = renewal(d(2027, 1, 20), rule = ruleB)
        val underA = renewal(d(2027, 2, 25))
        val result = replay(join, listOf(underB, underA))
        assertEquals(ReplayedPeriod(underB.id, d(2027, 1, 20), d(2027, 2, 20)), result.periods[0])
        assertEquals(d(2027, 3, 20), result.expiry)
    }

    @Test
    fun oldPaymentsKeepThePlanLengthTheyWereRecordedWith() {
        val result = replay(join, listOf(renewal(join, months = 3), renewal(d(2027, 4, 1), months = 1)))
        assertEquals(d(2027, 5, 1), result.expiry)
    }

    @Test
    fun oldPaymentsKeepTheRestartLimitTheyWereRecordedWith() {
        val expiry = d(2027, 2, 1)
        val strict = replay(join, listOf(renewal(join), renewal(expiry + 20, restart = 10)))
        assertEquals(Dates.addMonths(expiry + 20, 1), strict.expiry)
        val relaxed = replay(join, listOf(renewal(join), renewal(expiry + 20, restart = 30)))
        assertEquals(d(2027, 3, 1), relaxed.expiry)
    }

    @Test
    fun balancePaymentsNeverMoveExpiry() {
        val balance = LedgerPayment(50, PaymentKind.BALANCE, 0, 700, d(2027, 3, 5))
        assertEquals(d(2027, 2, 1), replay(join, listOf(renewal(join, paid = 500), balance)).expiry)
    }

    @Test
    fun openingBalanceSetsExpiryFromTheImportFile() {
        val opening = LedgerPayment(1, PaymentKind.OPENING, 700, 0, join, openingExpiry = d(2026, 12, 20))
        assertEquals(d(2026, 12, 20), replay(join, listOf(opening)).expiry)
        val renewed = renewal(d(2027, 1, 5))
        assertEquals(d(2027, 1, 20), replay(join, listOf(opening, renewed)).expiry)
    }

    @Test
    fun aRenewalWithNoPlanLengthIsRefusedRatherThanGuessed() {
        val broken = LedgerPayment(1, PaymentKind.RENEWAL, 1200, 1200, join)
        assertThrows(IllegalArgumentException::class.java) { replay(join, listOf(broken)) }
    }

    // ---- The payment made at joining (1.0.2) ----

    @Test
    fun aPaymentAtJoiningIsDatedTheJoinDateButNeverInTheFuture() {
        val today = d(2027, 3, 10)
        assertEquals(today - 5, MemberRules.joiningPaymentDate(today - 5, today))
        assertEquals(today, MemberRules.joiningPaymentDate(today, today))
        assertEquals(today, MemberRules.joiningPaymentDate(today + 7, today))
    }

    @Test
    fun aJoiningPaymentFollowsTheJoinDateOnlyIfItWasOnIt() {
        val today = d(2027, 3, 10)
        val old = d(2027, 3, 1)
        assertEquals(old + 1, MemberRules.movedJoiningPaymentDate(paidOn = old, oldJoinDate = old, newJoinDate = old + 1, today = today))
        assertEquals(old - 3, MemberRules.movedJoiningPaymentDate(old, old, old - 3, today))
        assertEquals("never into the future", today, MemberRules.movedJoiningPaymentDate(old, old, today + 3, today))
        assertNull("it had its own date", MemberRules.movedJoiningPaymentDate(old + 2, old, old + 5, today))
        assertNull("the join date didn't change", MemberRules.movedJoiningPaymentDate(old, old, old, today))
        assertNull("already as late as it can be", MemberRules.movedJoiningPaymentDate(today, today, today + 4, today))
    }

    @Test
    fun theFirstExpiryIsWhatSavingWouldGive() {
        val start = d(2027, 1, 10)
        val ruleA = RenewalRule.CONTINUE_FROM_EXPIRY
        assertEquals(d(2027, 2, 10), MemberRules.firstExpiry(start, start, 1, ruleA, 60))
        assertEquals(d(2027, 4, 10), MemberRules.firstExpiry(start, start, 3, ruleA, 60))
        // Paid a few days after joining: rule A counts from joining, rule B from the payment.
        assertEquals(d(2027, 2, 10), MemberRules.firstExpiry(start, start + 5, 1, ruleA, 60))
        assertEquals(d(2027, 2, 15), MemberRules.firstExpiry(start, start + 5, 1, ruleB, 60))
        // Paid long after joining: the month starts from the payment.
        assertEquals(Dates.addMonths(start + 90, 1), MemberRules.firstExpiry(start, start + 90, 1, ruleA, 60))
        // Paid ahead of a join date still to come: rule A still starts at joining.
        assertEquals(d(2027, 2, 10), MemberRules.firstExpiry(start, start - 3, 1, ruleA, 60))
    }

    @Test
    fun movingAJoiningPaymentIsOnlySafeWhenRemindersWouldNotNotice() {
        val expiry = d(2027, 2, 1)
        assertTrue(MemberRules.movingJoiningPaymentIsSafe(expiry, expiry, isLatestPayment = true, balance = 0))
        assertTrue("an advance has nothing to remind about", MemberRules.movingJoiningPaymentIsSafe(expiry, expiry, isLatestPayment = true, balance = -200))
        assertTrue("a later payment times any balance reminder", MemberRules.movingJoiningPaymentIsSafe(expiry, expiry, isLatestPayment = false, balance = 700))
        assertFalse("the expiry would move", MemberRules.movingJoiningPaymentIsSafe(expiry, expiry - 30, isLatestPayment = false, balance = 0))
        assertFalse("a balance reminder is timed from it", MemberRules.movingJoiningPaymentIsSafe(expiry, expiry, isLatestPayment = true, balance = 700))
    }
}
