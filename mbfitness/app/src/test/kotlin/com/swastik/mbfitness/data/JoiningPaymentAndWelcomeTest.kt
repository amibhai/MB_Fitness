package com.swastik.mbfitness.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.DedupeKeys
import com.swastik.mbfitness.domain.ImportRow
import com.swastik.mbfitness.domain.MemberEventType
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.domain.PackLanguage
import com.swastik.mbfitness.domain.Pacing
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.domain.RenewalRule
import com.swastik.mbfitness.domain.TemplatePacks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Version 1.0.2: a payment made at joining is dated the join date, changing the join date takes
 * it along, a welcome SMS goes to a new member who agreed to SMS, and 1.0.1's dates are fixed
 * where that's safe (docs/DECISIONS.md). The clock says Friday 1 Jan 2027, 10 AM.
 */
@RunWith(AndroidJUnit4::class)
class JoiningPaymentAndWelcomeTest {
    private val env = TestEnvironment()
    private val repo = env.repository
    private val db = env.db
    private val messages = MessageRepository(env.db, env.settings, env.clock)
    private val today get() = env.clock.today()

    @After
    fun tearDown() = env.close()

    private suspend fun add(
        joinDate: Long = today,
        phone: String = "9876543210",
        consent: Boolean = false,
        payment: FirstPayment? = FirstPayment(1200, PaymentMode.CASH),
    ): MemberSaveResult.Saved {
        val draft = MemberDraft("Rahul Sharma", phone, planId = 1, joinDate = joinDate, smsConsent = consent, firstPayment = payment)
        return repo.addMember(draft) as MemberSaveResult.Saved
    }

    /** What 1.0.1 saved: the payment dated the day the member was typed in. */
    private suspend fun addAsVersion101(joinDate: Long, received: Long = 1200): Long =
        add(joinDate, payment = FirstPayment(received, PaymentMode.CASH, paidOn = today)).memberId

    private suspend fun member(id: Long) = db.members().get(id)!!

    private suspend fun payments(id: Long) = db.payments().activeFor(id)

    private suspend fun queued(id: Long, kind: MessageKind) =
        db.messages().withStatus(id, MessageStatus.QUEUED).filter { it.kind == kind }

    /** The member as saved, with [joinDate] or [phone] changed. */
    private suspend fun edit(id: Long, joinDate: Long? = null, phone: String? = null): MemberEdit {
        val current = member(id)
        return MemberEdit(
            current.name,
            phone ?: current.phone,
            current.planId,
            joinDate ?: current.joinDate,
            current.dob,
            current.notes,
            current.isMinor,
            current.guardianName,
            current.guardianPhone,
        )
    }

    private fun now() = env.clock.nowMillis()

    // ---- The payment's date ----

    @Test
    fun aPaymentAtJoiningIsDatedTheJoinDate() = runTest {
        val joined = today - 5
        val id = add(joinDate = joined).memberId
        assertEquals(joined, payments(id).single().paidOn)
        assertEquals(Dates.addMonths(joined, 1), member(id).expiryDate)
        assertEquals(listOf(id), repo.observePaymentsOn(joined).first().map { it.memberId })
        assertTrue("not counted as money taken today", repo.observePaymentsOn(today).first().isEmpty())
    }

    @Test
    fun aJoinDateStillToComeIsPaidToday() = runTest {
        val id = add(joinDate = today + 3).memberId
        assertEquals(today, payments(id).single().paidOn)
        assertEquals(Dates.addMonths(today + 3, 1), member(id).expiryDate)
    }

    @Test
    fun aPaymentDateChosenOnTheFormIsKept() = runTest {
        val id = add(joinDate = today - 5, payment = FirstPayment(1200, PaymentMode.UPI, paidOn = today - 2)).memberId
        assertEquals(today - 2, payments(id).single().paidOn)
    }

    @Test
    fun underRuleBTheMonthStartsAtJoiningNow() = runTest {
        env.settings.update { it.copy(renewalRule = RenewalRule.START_FROM_PAYMENT) }
        val id = add(joinDate = today - 10).memberId
        assertEquals(Dates.addMonths(today - 10, 1), member(id).expiryDate)
    }

    // ---- Changing the join date ----

    @Test
    fun changingTheJoinDateMovesTheJoinedEventAndTheFirstPayment() = runTest {
        val id = add(joinDate = today - 5).memberId
        assertEquals(payments(id).single().id, repo.joiningPayment(id)?.id)

        repo.updateMember(id, edit(id, joinDate = today - 7))

        val payment = payments(id).single()
        assertEquals(today - 7, payment.paidOn)
        assertNotNull(payment.editedAt)
        assertEquals("the old date is kept", listOf(today - 5), paymentHistory(payment.editHistory).map { it.paidOn })
        assertEquals(listOf(today - 7), db.events().dates(id, MemberEventType.JOINED))
        assertEquals(Dates.addMonths(today - 7, 1), member(id).expiryDate)
    }

    @Test
    fun aFirstPaymentWithItsOwnDateStaysPut() = runTest {
        val id = add(joinDate = today - 5, payment = FirstPayment(1200, PaymentMode.CASH, paidOn = today - 2)).memberId
        repo.updateMember(id, edit(id, joinDate = today - 6))
        val payment = payments(id).single()
        assertEquals(today - 2, payment.paidOn)
        assertNull(payment.editedAt)
        assertEquals(listOf(today - 6), db.events().dates(id, MemberEventType.JOINED))
    }

    @Test
    fun onlyThePaymentMadeOnAddMemberIsTheJoiningPayment() = runTest {
        val id = add(payment = null).memberId
        repo.recordRenewal(RenewalRequest(id, 1, 1200, PaymentMode.CASH, today))
        assertNull(repo.joiningPayment(id))
        repo.updateMember(id, edit(id, joinDate = today - 3))
        assertEquals(today, payments(id).single().paidOn)
    }

    @Test
    fun changingTheJoinDateLetsTheNextRunRewordWaitingReminders() = runTest {
        val id = add().memberId
        val reminder = db.messages().insert(
            MessageEntity(memberId = id, phone = "+919876543210", kind = MessageKind.ON_DUE, body = "due", parts = 1, priority = 3, dedupeKey = "k", createdAt = 0, notBefore = 0),
        )
        repo.updateMember(id, edit(id, joinDate = today - 1))
        assertEquals(MessageStatus.CANCELLED, db.messages().get(reminder)!!.status)
    }

    // ---- The welcome SMS ----

    @Test
    fun aMemberWhoAgreedGetsOneWelcomeJustBeforeTheirThankYou() = runTest {
        val saved = add(consent = true)
        assertTrue(saved.welcomeQueued)
        val welcome = queued(saved.memberId, MessageKind.WELCOME).single()
        val thanks = queued(saved.memberId, MessageKind.PAYMENT_THANKS).single()
        assertEquals(
            "Hi Rahul, welcome to MB Fitness Club! This is the automated SMS reminder service of MB Fitness Club. Your fee reminders will come from this number.",
            welcome.body,
        )
        assertEquals("+919876543210", welcome.phone)
        assertEquals(DedupeKeys.welcome(saved.memberId), welcome.dedupeKey)
        assertEquals(1, welcome.parts)
        assertTrue("it waits as long as the thank-you", welcome.notBefore - welcome.createdAt >= MemberRepository.THANKS_DELAY_MILLIS)

        val queue = listOf(thanks, welcome).map { Pacing.Queued(it.id, it.parts, it.priority, it.createdAt, it.notBefore, it.kind.isPlanned) }
        val next = Pacing.next(queue, maxOf(welcome.notBefore, thanks.notBefore), emptyList(), Pacing.Limits())
        assertEquals("the welcome goes first", welcome.id, (next as Pacing.NextStep.Send).message.id)
    }

    @Test
    fun aWelcomeGoesEvenWithNothingPaid() = runTest {
        val saved = add(consent = true, payment = null)
        assertTrue(saved.welcomeQueued)
        assertEquals(1, queued(saved.memberId, MessageKind.WELCOME).size)
    }

    @Test
    fun noWelcomeWithoutConsentOrWithItSwitchedOff() = runTest {
        val noConsent = add(consent = false)
        assertFalse(noConsent.welcomeQueued)
        assertTrue(queued(noConsent.memberId, MessageKind.WELCOME).isEmpty())

        messages.saveTemplate(TemplatePacks.preset(MessageKind.WELCOME, PackLanguage.ENGLISH)!!.copy(enabled = false))
        val switchedOff = add(phone = "9123456780", consent = true)
        assertFalse(switchedOff.welcomeQueued)
        assertTrue(queued(switchedOff.memberId, MessageKind.WELCOME).isEmpty())
    }

    @Test
    fun aDatabaseFromBefore102StillWelcomesInThePacksLanguage() = runTest {
        // 1.0.1 made its templates before the welcome existed.
        db.openHelper.writableDatabase.execSQL("DELETE FROM templates WHERE type = 'WELCOME'")
        assertTrue(MessageKind.WELCOME in messages.templates().map { it.kind })
        assertTrue(MessageKind.WELCOME in messages.observeTemplates().first().map { it.kind })

        val english = add(consent = true)
        assertTrue(queued(english.memberId, MessageKind.WELCOME).single().body.startsWith("Hi Rahul, welcome to MB Fitness Club!"))

        env.settings.update { it.copy(packLanguage = PackLanguage.HINGLISH) }
        val hinglish = add(phone = "9123456780", consent = true)
        assertEquals(
            "Rahul ji, MB Fitness Club mein aapka swagat hai! Yeh MB Fitness Club ki automated SMS reminder service hai. Fees ke reminders isi number se aayenge.",
            queued(hinglish.memberId, MessageKind.WELCOME).single().body,
        )
        assertNull("nothing is written until it's edited", db.templates().get(MessageKind.WELCOME))
    }

    @Test
    fun aWelcomeSavedInTheEditorIsUsed() = runTest {
        db.openHelper.writableDatabase.execSQL("DELETE FROM templates WHERE type = 'WELCOME'")
        val edited = TemplatePacks.preset(MessageKind.WELCOME, PackLanguage.ENGLISH)!!.copy(body = "Welcome {name}! - {gym_name}")
        messages.saveTemplate(edited)
        assertEquals(edited, messages.templates().single { it.kind == MessageKind.WELCOME })
        val saved = add(consent = true)
        assertEquals("Welcome Rahul! - MB Fitness Club", queued(saved.memberId, MessageKind.WELCOME).single().body)
    }

    @Test
    fun importedMembersGetNoWelcome() = runTest {
        val row = ImportRow(2, "Anil Kumar", "+919123456780", 1, today + 10, 0, null, "", null)
        repo.importMembers(listOf(row), smsConsent = true)
        assertTrue(db.messages().withStatusOnly(MessageStatus.QUEUED).none { it.kind == MessageKind.WELCOME })
    }

    @Test
    fun stoppingSmsOrDeletingTheMemberStopsAWaitingWelcome() = runTest {
        val stopped = add(consent = true, payment = null).memberId
        repo.stopSms(stopped)
        assertTrue(queued(stopped, MessageKind.WELCOME).isEmpty())

        val deleted = add(phone = "9123456780", consent = true, payment = null).memberId
        repo.deleteMember(deleted)
        assertTrue(queued(deleted, MessageKind.WELCOME).isEmpty())
    }

    @Test
    fun aNumberFixedBeforeTheWelcomeGoesIsWhereItGoes() = runTest {
        val id = add(consent = true, payment = null).memberId
        repo.updateMember(id, edit(id, phone = "9123456780"))
        assertEquals("+919123456780", queued(id, MessageKind.WELCOME).single().phone)
    }

    // ---- Fixing the dates 1.0.1 saved ----

    @Test
    fun aJoiningPaymentDatedTheDayItWasTypedInMovesToTheJoinDate() = runTest {
        val id = addAsVersion101(joinDate = today - 20)
        val expiry = member(id).expiryDate
        assertEquals(1, repo.fixOldJoiningPaymentDates(createdBefore = now()))
        val payment = payments(id).single()
        assertEquals(today - 20, payment.paidOn)
        assertEquals("the old date is kept", listOf(today), paymentHistory(payment.editHistory).map { it.paidOn })
        assertEquals("nothing else changes", expiry, member(id).expiryDate)
        assertEquals(listOf(id), repo.observePaymentsOn(today - 20).first().map { it.memberId })
        assertEquals("once only", 0, repo.fixOldJoiningPaymentDates(createdBefore = now()))
    }

    @Test
    fun aMemberWhoseExpiryWouldMoveIsLeftAlone() = runTest {
        // Joined long before being entered, so 1.0.1 started their month the day they were entered.
        val id = addAsVersion101(joinDate = today - 90)
        assertEquals(Dates.addMonths(today, 1), member(id).expiryDate)
        assertEquals(0, repo.fixOldJoiningPaymentDates(createdBefore = now()))
        assertEquals(today, payments(id).single().paidOn)
        assertEquals(Dates.addMonths(today, 1), member(id).expiryDate)
    }

    @Test
    fun ruleBMembersAreLeftAlone() = runTest {
        env.settings.update { it.copy(renewalRule = RenewalRule.START_FROM_PAYMENT) }
        val id = addAsVersion101(joinDate = today - 10)
        assertEquals(0, repo.fixOldJoiningPaymentDates(createdBefore = now()))
        assertEquals(today, payments(id).single().paidOn)
    }

    @Test
    fun aPartPaymentStillOwedIsLeftAlone() = runTest {
        // Its balance reminders are timed from this payment.
        val id = addAsVersion101(joinDate = today - 10, received = 500)
        assertEquals(0, repo.fixOldJoiningPaymentDates(createdBefore = now()))
        assertEquals(today, payments(id).single().paidOn)
    }

    @Test
    fun aPartPaymentSettledSinceCanMove() = runTest {
        val id = addAsVersion101(joinDate = today - 10, received = 500)
        repo.recordBalance(BalanceRequest(id, 700, PaymentMode.CASH, today))
        assertEquals(1, repo.fixOldJoiningPaymentDates(createdBefore = now()))
        assertEquals(today - 10, repo.joiningPayment(id)!!.paidOn)
    }

    @Test
    fun aPaymentAlreadyCorrectedByHandIsLeftAlone() = runTest {
        val id = addAsVersion101(joinDate = today - 10)
        val payment = payments(id).single()
        repo.editPayment(payment.id, PaymentEdit(payment.charged, payment.paid, today - 1, payment.mode, payment.note))
        assertEquals(0, repo.fixOldJoiningPaymentDates(createdBefore = now()))
        assertEquals(today - 1, payments(id).single().paidOn)
    }

    @Test
    fun aMemberInTheRecycleBinIsLeftAlone() = runTest {
        val id = addAsVersion101(joinDate = today - 10)
        repo.deleteMember(id)
        assertEquals(0, repo.fixOldJoiningPaymentDates(createdBefore = now()))
        assertEquals(today, payments(id).single().paidOn)
    }

    @Test
    fun theFirstOpenAfterUpdatingFixesWhatWasThere() = runTest {
        val id = addAsVersion101(joinDate = today - 20)
        assertEquals(1, repo.fixOldJoiningPaymentDates())
        assertEquals(today - 20, payments(id).single().paidOn)
    }

    @Test
    fun membersAddedSinceTheUpdateAreNeverTouched() = runTest {
        // The first open after updating notes the time, in the database.
        assertEquals(0, repo.fixOldJoiningPaymentDates())
        val cutoff = db.counters().value(AppDatabase.JOINING_FIX_CUTOFF)
        assertNotNull(cutoff)
        // A payment date chosen on purpose looks just like 1.0.1's.
        val id = add(joinDate = today - 10, payment = FirstPayment(1200, PaymentMode.CASH, paidOn = today)).memberId
        assertEquals(0, repo.fixOldJoiningPaymentDates())
        assertEquals(today, payments(id).single().paidOn)
        assertEquals("noted once", cutoff, db.counters().value(AppDatabase.JOINING_FIX_CUTOFF))
    }
}
