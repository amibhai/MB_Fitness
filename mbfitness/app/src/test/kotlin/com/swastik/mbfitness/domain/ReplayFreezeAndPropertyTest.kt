package com.swastik.mbfitness.domain

import com.swastik.mbfitness.domain.MemberRules.replay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class ReplayFreezeAndPropertyTest {
    private fun d(year: Int, month: Int, day: Int) = Dates.of(year, month, day)
    private val join = d(2027, 1, 1)
    private val ruleA = RenewalRule.CONTINUE_FROM_EXPIRY
    private val ruleB = RenewalRule.START_FROM_PAYMENT

    private fun renewal(id: Long, paidOn: Long, rule: RenewalRule = ruleA, months: Int = 1) =
        LedgerPayment(id, PaymentKind.RENEWAL, 1200, 1200, paidOn, months, rule, 60)

    private val paidJan = renewal(1, join) // Valid till 1 Feb.

    @Test
    fun aFreezeExtendsExpiryByItsLength() {
        val freeze = FreezeSpan(d(2027, 1, 10), d(2027, 1, 20))
        assertEquals(d(2027, 2, 11), replay(join, listOf(paidJan), listOf(freeze)).expiry)
    }

    @Test
    fun anOpenFreezeAddsNothingUntilItEnds() {
        val open = FreezeSpan(d(2027, 1, 10), null)
        assertEquals(d(2027, 2, 1), replay(join, listOf(paidJan), listOf(open)).expiry)
    }

    @Test
    fun aFreezeRunningPastExpiryKeepsAllItsDays() {
        // 4 days were left when it began, so 4 days must be left when it ends on 11 Feb.
        val freeze = FreezeSpan(d(2027, 1, 28), d(2027, 2, 11))
        assertEquals(d(2027, 2, 15), replay(join, listOf(paidJan), listOf(freeze)).expiry)
    }

    @Test
    fun aFreezeStartingOnTheLastValidDayCounts() {
        val freeze = FreezeSpan(d(2027, 2, 1), d(2027, 2, 6))
        assertEquals(d(2027, 2, 6), replay(join, listOf(paidJan), listOf(freeze)).expiry)
    }

    @Test
    fun aFreezeAfterExpiryHasNothingToProtect() {
        val freeze = FreezeSpan(d(2027, 2, 10), d(2027, 2, 20))
        assertEquals(d(2027, 2, 1), replay(join, listOf(paidJan), listOf(freeze)).expiry)
    }

    @Test
    fun severalFreezesAddUp() {
        val freezes = listOf(FreezeSpan(d(2027, 1, 5), d(2027, 1, 8)), FreezeSpan(d(2027, 1, 20), d(2027, 1, 25)))
        assertEquals(d(2027, 2, 9), replay(join, listOf(paidJan), freezes).expiry)
    }

    @Test
    fun aFreezeEndingBeforeItStartsCountsAsZeroDays() {
        val backwards = FreezeSpan(d(2027, 1, 10), d(2027, 1, 5))
        assertEquals(d(2027, 2, 1), replay(join, listOf(paidJan), listOf(backwards)).expiry)
    }

    @Test
    fun underRuleAAPaymentDuringAFreezeContinuesFromTheExtendedExpiry() {
        val freeze = FreezeSpan(d(2027, 1, 25), d(2027, 2, 4)) // 10 days, expiry 11 Feb.
        val during = renewal(2, d(2027, 2, 1))
        assertEquals(d(2027, 3, 11), replay(join, listOf(paidJan, during), listOf(freeze)).expiry)
    }

    @Test
    fun underRuleBAPeriodBeginsWhenTheFreezeEnds() {
        val freeze = FreezeSpan(d(2027, 1, 25), d(2027, 2, 4))
        val during = renewal(2, d(2027, 2, 1), ruleB)
        assertEquals(d(2027, 3, 4), replay(join, listOf(paidJan, during), listOf(freeze)).expiry)
    }

    @Test
    fun aFreezeOnTheDayOfAPaymentProtectsTheNewPeriod() {
        // Overdue since 1 Feb. Paying on 10 Feb then freezing that day must count the freeze.
        val paid = renewal(2, d(2027, 2, 10), ruleB)
        val freeze = FreezeSpan(d(2027, 2, 10), d(2027, 2, 15))
        assertEquals(d(2027, 3, 15), replay(join, listOf(paidJan, paid), listOf(freeze)).expiry)
    }

    @Test
    fun paymentsOnOneDayGoInTheOrderTheyWereRecorded() {
        val first = renewal(2, d(2027, 2, 1), months = 1)
        val second = renewal(3, d(2027, 2, 1), months = 3)
        val result = replay(join, listOf(second, paidJan, first))
        assertEquals(listOf(1L, 2L, 3L), result.periods.map { it.paymentId })
        assertEquals(d(2027, 6, 1), result.expiry)
    }

    /** A second, deliberately plain implementation to check the real one against. */
    private fun oracleExpiry(payments: List<LedgerPayment>): Long {
        var expiry = join
        for (payment in payments.filter { it.kind == PaymentKind.RENEWAL }.sortedWith(compareBy({ it.paidOn }, { it.id }))) {
            val continues = payment.rule == ruleA && payment.paidOn - expiry <= payment.restartAfterDays!!
            val start = if (continues) expiry else payment.paidOn
            expiry = LocalDate.ofEpochDay(start).plusMonths(payment.planMonths!!.toLong()).toEpochDay()
        }
        return expiry
    }

    @Test
    fun randomAddEditDeleteUndoSequencesAlwaysMatchAFreshReplay() {
        repeat(200) { seed ->
            val random = Random(seed)
            val ledger = mutableListOf<LedgerPayment>()
            var nextId = 1L
            var runningBalance = 0L

            repeat(40) {
                val before = replay(join, ledger).expiry
                when (random.nextInt(4)) {
                    0 -> {
                        val charged = random.nextLong(500, 3000)
                        val paid = random.nextLong(0, 3500)
                        ledger += LedgerPayment(
                            nextId++, PaymentKind.RENEWAL, charged, paid, join + random.nextLong(0, 500),
                            planMonths = random.nextInt(1, 13),
                            rule = if (random.nextBoolean()) ruleA else ruleB,
                            restartAfterDays = random.nextInt(10, 90),
                        )
                        runningBalance += charged - paid
                    }
                    1 -> {
                        val paid = random.nextLong(0, 2000)
                        ledger += LedgerPayment(nextId++, PaymentKind.BALANCE, 0, paid, join + random.nextLong(0, 500))
                        runningBalance -= paid
                        assertEquals("a balance payment moved expiry, seed $seed", before, replay(join, ledger).expiry)
                    }
                    2 -> if (ledger.isNotEmpty()) {
                        val index = random.nextInt(ledger.size)
                        val old = ledger[index]
                        val edited = old.copy(paid = random.nextLong(0, 3500), paidOn = join + random.nextLong(0, 500))
                        ledger[index] = edited
                        runningBalance += old.paid - edited.paid
                    }
                    else -> if (ledger.isNotEmpty()) {
                        // Delete, then undo: everything must return exactly to where it was.
                        val removed = ledger.removeAt(random.nextInt(ledger.size))
                        ledger += removed
                        assertEquals("undo changed expiry, seed $seed", before, replay(join, ledger).expiry)
                    }
                }
                val result = replay(join, ledger)
                assertEquals("differs from the plain implementation, seed $seed", oracleExpiry(ledger), result.expiry)
                assertEquals("depends on list order, seed $seed", result, replay(join, ledger.shuffled(random)))
                assertEquals("balance drifted, seed $seed", runningBalance, MemberRules.balance(ledger))
            }
        }
    }

    /**
     * Only while nobody passes the restart limit. Past it, a freeze can legitimately give an
     * earlier date: it keeps the member inside the limit, so they continue from the old expiry
     * instead of restarting from the payment date.
     */
    @Test
    fun freezesNeverShortenAMembership() {
        repeat(200) { seed ->
            val random = Random(seed)
            val payments = List(random.nextInt(1, 6)) {
                renewal(it + 1L, join + random.nextLong(0, 200)).copy(restartAfterDays = 100_000)
            }
            val without = replay(join, payments).expiry
            val freeze = (join + random.nextLong(0, 200)).let { FreezeSpan(it, it + random.nextLong(0, 30)) }
            val with = replay(join, payments, listOf(freeze)).expiry
            assertTrue("seed $seed shortened from $without to $with", with >= without)
        }
    }
}
