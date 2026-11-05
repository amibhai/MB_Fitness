package com.swastik.mbfitness.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.domain.AfterPayment
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.FreezeSpan
import com.swastik.mbfitness.domain.LedgerPayment
import com.swastik.mbfitness.domain.MemberEventType
import com.swastik.mbfitness.domain.MemberRules
import com.swastik.mbfitness.domain.MemberState
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.domain.PaymentKind
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.domain.RenewalRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** Payment round trips, undo and recalculation against a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
class PaymentFlowTest {
    private val env = TestEnvironment()
    private val repo = env.repository
    private val db = env.db
    private val today get() = env.clock.today()
    private val monthlyPlan = 1L
    private val quarterlyPlan = 2L

    @After
    fun tearDown() = env.close()

    private suspend fun addMember(
        phone: String = "9876543210",
        consent: Boolean = true,
        paid: Long? = null,
    ): Long {
        val draft = MemberDraft(
            name = "Rahul Sharma",
            phone = phone,
            planId = monthlyPlan,
            joinDate = today,
            smsConsent = consent,
            firstPayment = paid?.let { FirstPayment(it, PaymentMode.CASH) },
        )
        return (repo.addMember(draft) as MemberSaveResult.Saved).memberId
    }

    private suspend fun renew(memberId: Long, received: Long = 1200, plan: Long = monthlyPlan, on: Long = today) =
        repo.recordRenewal(RenewalRequest(memberId, plan, received, PaymentMode.UPI, on))

    private suspend fun member(id: Long) = db.members().get(id)!!
    private suspend fun balance(id: Long) = MemberRules.balance(db.payments().activeFor(id).map { LedgerPayment(it.id, it.kind, it.charged, it.paid, it.paidOn) })
    private fun d(year: Int, month: Int, day: Int) = Dates.of(year, month, day)

    private suspend fun queuedReminder(memberId: Long, kind: MessageKind = MessageKind.OVERDUE_1): Long =
        db.messages().insert(
            MessageEntity(
                memberId = memberId, phone = "+919876543210", kind = kind, body = "b", parts = 1,
                priority = kind.priority, dedupeKey = "key-$memberId-$kind", createdAt = 0, notBefore = 0,
            ),
        )

    @Test
    fun aFreshDatabaseIsSeeded() = runTest {
        val plans = db.plans().all()
        assertEquals(listOf("1 month" to 1200L, "3 months" to 3000L), plans.map { it.name to it.price })
        assertEquals(MessageKind.TEMPLATED.toSet(), db.templates().all().map { it.type }.toSet())
        assertEquals(1L, db.counters().value(AppDatabase.RECEIPT_COUNTER))
    }

    @Test
    fun aFullRenewalMovesExpiryAndClearsTheBalance() = runTest {
        val id = addMember(paid = 1200)
        assertEquals(d(2027, 2, 1), member(id).expiryDate)
        assertEquals(0L, balance(id))
        val saved = renew(id, on = d(2027, 2, 1))
        assertEquals(d(2027, 3, 1), saved.newExpiry)
        assertEquals("MBF-0002", saved.receipt)
        val payment = db.payments().get(saved.paymentId)!!
        assertEquals(d(2027, 2, 1), payment.periodStart)
        assertEquals(d(2027, 3, 1), payment.periodEnd)
    }

    @Test
    fun aPartPaymentLeavesABalanceThatABalancePaymentClears() = runTest {
        val id = addMember()
        val part = renew(id, received = 500)
        assertEquals(700L, part.balance)
        val expiry = member(id).expiryDate
        val rest = repo.recordBalance(BalanceRequest(id, 700, PaymentMode.CASH, today))
        assertEquals(0L, rest.balance)
        assertEquals(expiry, member(id).expiryDate)
        assertEquals("MBF-0002", rest.receipt)
    }

    @Test
    fun receiptNumbersAreSequentialAndNeverReused() = runTest {
        val id = addMember()
        val first = renew(id)
        val second = renew(id)
        repo.deletePayment(second.paymentId)
        repo.undoPayment(first)
        val third = renew(id)
        assertEquals(listOf("MBF-0001", "MBF-0002", "MBF-0003"), listOf(first.receipt, second.receipt, third.receipt))
    }

    @Test
    fun aFailureInsideAPaymentLeavesNothingBehind() = runTest {
        val id = addMember()
        val expiryBefore = member(id).expiryDate
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repo.recordRenewal(RenewalRequest(id, planId = 999, received = 100, mode = PaymentMode.CASH, paidOn = today))
            }
        }
        assertTrue(db.payments().activeFor(id).isEmpty())
        assertEquals(expiryBefore, member(id).expiryDate)
        assertEquals(1L, db.counters().value(AppDatabase.RECEIPT_COUNTER))
    }

    @Test
    fun anEditKeepsTheOldValuesAndWorksExpiryOutAgain() = runTest {
        val id = addMember()
        val saved = renew(id)
        val change = repo.editPayment(
            saved.paymentId,
            PaymentEdit(charged = 3000, paid = 2500, paidOn = today, mode = PaymentMode.CASH, note = "fixed", planId = quarterlyPlan),
        )
        assertEquals(ExpiryChange(d(2027, 2, 1), d(2027, 4, 1)), change)
        val payment = db.payments().get(saved.paymentId)!!
        assertEquals(2500L, payment.paid)
        assertEquals(3, payment.planMonths)
        assertNotNull(payment.editedAt)
        val history = Json.decodeFromString<List<PaymentVersion>>(payment.editHistory)
        assertEquals(1200L, history.single().paid)
        assertEquals(PaymentMode.UPI, history.single().mode)
        assertEquals(1, history.single().planMonths)
        assertEquals(500L, balance(id))
    }

    @Test
    fun everyEditAddsToTheHistory() = runTest {
        val saved = renew(addMember())
        repeat(3) { index ->
            repo.editPayment(saved.paymentId, PaymentEdit(1200, 100L + index, today, PaymentMode.CASH, ""))
        }
        val history = Json.decodeFromString<List<PaymentVersion>>(db.payments().get(saved.paymentId)!!.editHistory)
        assertEquals(listOf(1200L, 100L, 101L), history.map { it.paid })
    }

    @Test
    fun deletingAndRestoringAPaymentRoundTrips() = runTest {
        val id = addMember()
        renew(id)
        val second = renew(id, received = 500)
        assertEquals(d(2027, 3, 1), member(id).expiryDate)
        assertEquals(700L, balance(id))

        assertEquals(ExpiryChange(d(2027, 3, 1), d(2027, 2, 1)), repo.previewDeletePayment(second.paymentId))
        assertEquals(ExpiryChange(d(2027, 3, 1), d(2027, 2, 1)), repo.deletePayment(second.paymentId))
        assertEquals(d(2027, 2, 1), member(id).expiryDate)
        assertEquals(0L, balance(id))
        assertEquals(1, repo.observePayments(id).first().size)

        assertEquals(ExpiryChange(d(2027, 2, 1), d(2027, 3, 1)), repo.restorePayment(second.paymentId))
        assertEquals(d(2027, 3, 1), member(id).expiryDate)
        assertEquals(700L, balance(id))
    }

    @Test
    fun aPaymentCancelsStaleRemindersButNotOtherMessages() = runTest {
        val id = addMember()
        val reminder = queuedReminder(id, MessageKind.OVERDUE_1)
        val birthday = queuedReminder(id, MessageKind.BIRTHDAY)
        renew(id)
        val cancelled = db.messages().get(reminder)!!
        assertEquals(MessageStatus.CANCELLED, cancelled.status)
        assertNull("the key must be freed so it can be planned again", cancelled.dedupeKey)
        assertEquals(MessageStatus.QUEUED, db.messages().get(birthday)!!.status)
    }

    @Test
    fun theThankYouWaitsLongerThanTheUndoWindow() = runTest {
        val id = addMember()
        val saved = renew(id, received = 500)
        assertTrue(saved.thanksQueued)
        val thanks = db.messages().withStatus(id, MessageStatus.QUEUED).single()
        assertEquals(MessageKind.PAYMENT_THANKS, thanks.kind)
        assertTrue(thanks.notBefore - thanks.createdAt >= 10_000)
        assertTrue(thanks.body, "Received Rs 500" in thanks.body)
        assertTrue(thanks.body, "MBF-0001" in thanks.body)
        assertTrue(thanks.body, "Balance due: Rs 700" in thanks.body)
    }

    @Test
    fun aFullyPaidThankYouDropsTheBalanceClause() = runTest {
        val id = addMember()
        renew(id, received = 1200)
        val thanks = db.messages().withStatus(id, MessageStatus.QUEUED).single()
        assertFalse(thanks.body, "Balance" in thanks.body)
    }

    @Test
    fun noThankYouWithoutConsentOrMoneyOrWithTheTemplateOff() = runTest {
        val noConsent = addMember(consent = false)
        assertFalse(renew(noConsent).thanksQueued)
        val noMoney = addMember(phone = "9123456780")
        assertFalse(renew(noMoney, received = 0).thanksQueued)
        val templateOff = addMember(phone = "9123456781")
        val template = db.templates().get(MessageKind.PAYMENT_THANKS)!!
        db.templates().upsert(template.copy(enabled = false))
        assertFalse(renew(templateOff).thanksQueued)
    }

    @Test
    fun undoReversesThePaymentAndCancelsTheThankYou() = runTest {
        val id = addMember()
        val before = member(id).expiryDate
        val saved = renew(id)
        repo.undoPayment(saved)
        assertEquals(before, member(id).expiryDate)
        assertEquals(0L, balance(id))
        assertTrue(db.messages().withStatus(id, MessageStatus.QUEUED).isEmpty())
    }

    @Test
    fun undoOfAPaymentThatBroughtAMemberBackPutsThemBack() = runTest {
        val id = addMember(paid = 1200)
        repo.markLeft(id)
        env.clock.day = d(2027, 2, 20)
        val saved = renew(id, on = today)
        assertEquals(MemberState.ACTIVE, member(id).status)
        assertEquals(listOf(today), db.events().dates(id, MemberEventType.REJOINED))
        assertEquals(d(2027, 3, 20), saved.newExpiry)

        repo.undoPayment(saved)
        assertEquals(MemberState.LEFT, member(id).status)
        assertTrue(db.events().dates(id, MemberEventType.REJOINED).isEmpty())
        assertEquals(d(2027, 2, 1), member(id).expiryDate)
    }

    @Test
    fun aRenewalMovesTheMemberToThatPlan() = runTest {
        val id = addMember()
        renew(id, plan = quarterlyPlan)
        assertEquals(quarterlyPlan, member(id).planId)
    }

    @Test
    fun theRenewalPreviewMatchesWhatSavingDoes() = runTest {
        val id = addMember()
        val preview = repo.previewRenewal(id, quarterlyPlan, today, received = 2000)
        assertEquals(AfterPayment.Remaining(1000), preview.after)
        assertEquals(3000L, preview.defaultReceived)
        assertEquals(renew(id, received = 2000, plan = quarterlyPlan).newExpiry, preview.newExpiry)
    }

    @Test
    fun thePreviewSaysWhenAMemberWhoLeftStartsAfresh() = runTest {
        val id = addMember(paid = 1200)
        repo.markLeft(id)
        env.clock.day = d(2027, 1, 20)
        val preview = repo.previewRenewal(id, monthlyPlan, today)
        assertTrue(preview.startsFresh)
        assertEquals(today, preview.periodStart)
    }

    @Test
    fun changingTheRenewalRuleDoesNotRewriteOldPayments() = runTest {
        val id = addMember()
        renew(id, on = d(2027, 1, 1))
        env.settings.update { it.copy(renewalRule = RenewalRule.START_FROM_PAYMENT) }
        val second = renew(id, on = d(2027, 2, 10))
        assertEquals(d(2027, 3, 10), second.newExpiry)
        // Any later recalculation must keep the first payment under the old rule.
        env.settings.update { it.copy(renewalRule = RenewalRule.CONTINUE_FROM_EXPIRY) }
        val third = renew(id, on = d(2027, 3, 1))
        assertEquals(d(2027, 4, 10), third.newExpiry)
        assertEquals(d(2027, 2, 1), db.payments().activeFor(id).first().periodEnd)
    }

    @Test
    fun editingAPlanDoesNotChangeAnyonesExpiry() = runTest {
        val id = addMember()
        renew(id)
        val plan = db.plans().get(monthlyPlan)!!
        repo.updatePlan(plan.copy(months = 12, price = 9000))
        val edited = db.payments().activeFor(id).single()
        repo.editPayment(edited.id, PaymentEdit(edited.charged, edited.paid, edited.paidOn, edited.mode, "note only"))
        assertEquals(d(2027, 2, 1), member(id).expiryDate)
    }

    @Test
    fun aRetiredPlanStaysOnOldPayments() = runTest {
        val id = addMember()
        renew(id)
        repo.setPlanActive(monthlyPlan, false)
        assertEquals(listOf(quarterlyPlan), repo.observeActivePlans().first().map { it.id })
        assertEquals(monthlyPlan, db.payments().activeFor(id).single().planId)
    }

    @Test
    fun freezingAndUnfreezingExtendsExpiry() = runTest {
        val id = addMember(paid = 1200)
        val reminder = queuedReminder(id, MessageKind.BEFORE_DUE)
        env.clock.day = d(2027, 1, 10)
        repo.freeze(id)
        assertEquals(MemberState.FROZEN, member(id).status)
        assertEquals(MessageStatus.CANCELLED, db.messages().get(reminder)!!.status)
        assertEquals(d(2027, 2, 1), member(id).expiryDate)

        env.clock.day = d(2027, 1, 20)
        assertEquals(ExpiryChange(d(2027, 2, 1), d(2027, 2, 11)), repo.unfreeze(id))
        assertEquals(MemberState.ACTIVE, member(id).status)
        assertEquals(d(2027, 2, 11), member(id).expiryDate)
    }

    @Test
    fun thereIsOnlyEverOneOpenFreeze() = runTest {
        val id = addMember(paid = 1200)
        repo.freeze(id)
        repo.freeze(id)
        assertEquals(1, db.freezes().forMember(id).size)
        repo.unfreeze(id)
        assertNull(repo.unfreeze(id))
    }

    @Test
    fun leavingClosesAnOpenFreeze() = runTest {
        val id = addMember(paid = 1200)
        repo.freeze(id)
        env.clock.day = today + 5
        repo.markLeft(id)
        assertNull(db.freezes().openFor(id))
        assertEquals(MemberState.LEFT, member(id).status)
    }

    @Test
    fun aMemberWhoRejoinsStartsFromTheirNextPayment() = runTest {
        val id = addMember(paid = 1200)
        repo.markLeft(id)
        env.clock.day = d(2027, 1, 20)
        repo.rejoin(id)
        assertEquals(MemberState.ACTIVE, member(id).status)
        assertEquals(d(2027, 2, 22), renew(id, on = d(2027, 1, 22)).newExpiry)
    }

    /** SPEC 7.4: expiry is always exactly what a fresh replay of the stored records gives. */
    @Test
    fun expiryAlwaysMatchesAFreshReplayAfterRandomOperations() = runTest {
        repeat(5) { seed ->
            val random = Random(seed)
            val phone = "98765432${10 + seed}"
            val id = addMember(phone = phone, consent = random.nextBoolean())
            val saved = mutableListOf<PaymentSaved>()

            repeat(60) {
                env.clock.day = today + random.nextLong(0, 3)
                when (random.nextInt(8)) {
                    0, 1 -> saved += renew(id, received = random.nextLong(0, 1500), plan = if (random.nextBoolean()) monthlyPlan else quarterlyPlan)
                    2 -> saved += repo.recordBalance(BalanceRequest(id, random.nextLong(0, 500), PaymentMode.CASH, today))
                    3 -> saved.randomOrNull(random)?.let { repo.editPayment(it.paymentId, PaymentEdit(1200, random.nextLong(0, 1200), today, PaymentMode.OTHER, "")) }
                    4 -> saved.randomOrNull(random)?.let { repo.deletePayment(it.paymentId) }
                    5 -> saved.randomOrNull(random)?.let { repo.restorePayment(it.paymentId) }
                    6 -> if (member(id).status == MemberState.FROZEN) repo.unfreeze(id) else repo.freeze(id)
                    else -> if (member(id).status == MemberState.LEFT) repo.rejoin(id) else repo.markLeft(id)
                }
                val stored = member(id)
                val ledger = db.payments().activeFor(id).map {
                    LedgerPayment(it.id, it.kind, it.charged, it.paid, it.paidOn, it.planMonths, it.ruleApplied, it.restartAfterDays, it.periodEnd.takeIf { _ -> it.kind == PaymentKind.OPENING })
                }
                val fresh = MemberRules.replay(
                    stored.joinDate,
                    ledger,
                    db.freezes().forMember(id).map { FreezeSpan(it.fromDate, it.toDate) },
                    db.events().dates(id, MemberEventType.REJOINED),
                )
                assertEquals("seed $seed", fresh.expiry, stored.expiryDate)
            }
        }
    }
}
