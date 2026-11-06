package com.swastik.mbfitness.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.data.MemberDraft
import com.swastik.mbfitness.data.MemberSaveResult
import com.swastik.mbfitness.data.MessageEntity
import com.swastik.mbfitness.data.MessageRepository
import com.swastik.mbfitness.data.TestEnvironment
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.domain.Pacing
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.data.RenewalRequest
import com.swastik.mbfitness.sms.BlockReason
import com.swastik.mbfitness.sms.FakeSmsSender
import com.swastik.mbfitness.sms.SendOutcome
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The SPEC 15 outbox tests, with a fake sender, so nothing here can ever send a real SMS. */
@RunWith(AndroidJUnit4::class)
class OutboxEngineTest {
    private val env = TestEnvironment()
    private val db = env.db
    private val messages = MessageRepository(env.db, env.settings, env.clock)
    private val sender = FakeSmsSender()
    private val blocked = mutableListOf<BlockReason>()
    private val pauses = mutableListOf<Long>()
    private val engine = OutboxEngine(
        messages,
        env.settings,
        sender,
        env.clock,
        memberName = { id -> env.repository.member(id)?.name },
        onBlocked = { blocked += it },
        pause = { pauses += it },
    )

    @After
    fun tearDown() = env.close()

    private suspend fun queue(
        kind: MessageKind = MessageKind.MANUAL,
        body: String = "Hello",
        parts: Int = 1,
        phone: String = "+919876543210",
        notBefore: Long = 0,
        createdAt: Long = 0,
        memberId: Long? = null,
    ): Long = db.messages().insert(
        MessageEntity(memberId = memberId, phone = phone, kind = kind, body = body, parts = parts, priority = kind.priority, createdAt = createdAt, notBefore = notBefore),
    )

    private suspend fun message(id: Long) = db.messages().get(id)!!

    @Test
    fun aMessageGoesToItsMemberWhenNotInTestMode() = runTest {
        val id = queue()
        assertNull(engine.run())
        assertEquals("+919876543210", sender.accepted.single().phone)
        assertEquals("Hello", sender.accepted.single().body)
        assertEquals(MessageStatus.SENDING, message(id).status)
        assertNotNull(message(id).submittedAt)
    }

    @Test
    fun anEmptyQueueHasNothingToDo() = runTest {
        assertNull(engine.run())
        assertTrue(sender.accepted.isEmpty())
    }

    @Test
    fun theSenderIsOnlyCalledOncePerMessage() = runTest {
        val id = queue()
        engine.run()
        engine.run()
        assertEquals(1, sender.accepted.size)
        assertEquals(1, message(id).attempts)
    }

    @Test
    fun aPartResultFinishesTheMessage() = runTest {
        val id = queue()
        engine.run()
        assertNull(messages.onPartResult(id, 1, 1, ok = true, reason = null))
        assertEquals(MessageStatus.SENT, message(id).status)
        assertNotNull(message(id).sentAt)
    }

    @Test
    fun aLongMessageWaitsForEveryPart() = runTest {
        val id = queue(body = "x".repeat(200), parts = 2)
        engine.run()
        messages.onPartResult(id, 1, 2, ok = true, reason = null)
        assertEquals(MessageStatus.SENDING, message(id).status)
        messages.onPartResult(id, 1, 2, ok = true, reason = null)
        assertEquals(MessageStatus.SENT, message(id).status)
    }

    @Test
    fun aFailedPartRetriesWithBackoffThenStops() = runTest {
        val id = queue()
        engine.run()
        val first = messages.onPartResult(id, 1, 1, ok = false, reason = "No signal")
        assertNotNull(first)
        assertEquals(MessageStatus.QUEUED, message(id).status)
        assertEquals("No signal", message(id).lastError)
        assertTrue(message(id).notBefore - env.clock.nowMillis() in (4 * 60_000L)..(5 * 60_000L))

        // Not yet: it waits out the backoff.
        assertEquals(1, sender.accepted.size)
        engine.run()
        assertEquals(1, sender.accepted.size)

        env.clock.advance(5 * 60_000L)
        engine.run()
        assertEquals(2, sender.accepted.size)
        assertEquals(2, message(id).attempts)
        val second = messages.onPartResult(id, 2, 1, ok = false, reason = "No signal")
        assertTrue(second!! - env.clock.nowMillis() in (29 * 60_000L)..(30 * 60_000L))

        env.clock.advance(30 * 60_000L)
        engine.run()
        assertEquals(3, sender.accepted.size)
        assertNull(messages.onPartResult(id, 3, 1, ok = false, reason = "No signal"))
        assertEquals(MessageStatus.FAILED, message(id).status)
    }

    @Test
    fun noSignalJustWaitsAndNothingIsLost() = runTest {
        val id = queue()
        sender.respond = { SendOutcome.Failed("No signal") }
        val wake = engine.run()
        assertNotNull(wake)
        assertEquals(MessageStatus.QUEUED, message(id).status)
        sender.respond = { SendOutcome.Accepted(1) }
        env.clock.advance(5 * 60_000L)
        engine.run()
        assertEquals(1, sender.accepted.size)
    }

    @Test
    fun aResultFromAnOlderAttemptIsIgnored() = runTest {
        val id = queue()
        engine.run()
        messages.onPartResult(id, 1, 1, ok = false, reason = "No signal")
        env.clock.advance(5 * 60_000L)
        engine.run()
        messages.onPartResult(id, 1, 1, ok = true, reason = null)
        assertEquals(MessageStatus.SENDING, message(id).status)
        messages.onPartResult(id, 2, 1, ok = true, reason = null)
        assertEquals(MessageStatus.SENT, message(id).status)
    }

    @Test
    fun aMissingDeliveryReportIsNotAFailure() = runTest {
        val id = queue()
        engine.run()
        messages.onPartResult(id, 1, 1, ok = true, reason = null)
        assertEquals(MessageStatus.SENT, message(id).status)
        messages.onDelivered(id, 1, 1)
        assertEquals(MessageStatus.DELIVERED, message(id).status)
        assertNotNull(message(id).deliveredAt)
    }

    /** SPEC 8.5 and docs/DECISIONS.md: never double-send. */
    @Test
    fun aRowThatNeverReachedTheRadioIsQueuedAgainAfterARestart() = runTest {
        val id = queue()
        messages.markSending(id, 1)
        env.clock.advance(11 * 60_000L)
        engine.run()
        assertEquals(1, sender.accepted.size)
    }

    @Test
    fun aRowTheRadioTookWithNoResultIsNotSentAgain() = runTest {
        val id = queue()
        engine.run()
        assertEquals(1, sender.accepted.size)
        env.clock.advance(11 * 60_000L)
        engine.run()
        assertEquals("must not send twice", 1, sender.accepted.size)
        assertEquals(MessageStatus.FAILED, message(id).status)
        assertEquals(MessageRepository.NO_RESULT, message(id).lastError)
    }

    @Test
    fun aRecentRowIsLeftAlone() = runTest {
        val id = queue()
        engine.run()
        env.clock.advance(5 * 60_000L)
        engine.run()
        assertEquals(MessageStatus.SENDING, message(id).status)
    }

    @Test
    fun aFailedMessageCanBeRetriedByHand() = runTest {
        val id = queue()
        engine.run()
        env.clock.advance(11 * 60_000L)
        engine.run()
        messages.retry(id)
        assertEquals(MessageStatus.QUEUED, message(id).status)
        assertEquals(0, message(id).attempts)
        engine.run()
        assertEquals(2, sender.accepted.size)
    }

    @Test
    fun aCancelledMessageNeverGoes() = runTest {
        val id = queue()
        messages.cancel(id)
        engine.run()
        assertTrue(sender.accepted.isEmpty())
    }

    @Test
    fun lostPermissionStopsSendingWithoutUsingUpATry() = runTest {
        val id = queue()
        queue()
        sender.respond = { SendOutcome.Blocked(BlockReason.NO_PERMISSION) }
        assertNotNull(engine.run())
        assertEquals(MessageStatus.QUEUED, message(id).status)
        assertEquals(0, message(id).attempts)
        assertEquals(listOf(BlockReason.NO_PERMISSION), blocked)
    }

    @Test
    fun testModeOnlyEverTextsTheOwnerAndMarksIt() = runTest {
        env.settings.update { it.copy(testMode = true, trainerPhone = "+919000000099") }
        val member = (env.repository.addMember(MemberDraft("Rahul Sharma", "9876543210", 1, env.clock.today())) as MemberSaveResult.Saved).memberId
        queue(memberId = member, body = "Hi Rahul")
        queue(phone = "+919111111111", body = "Hello")
        engine.run()
        assertEquals(listOf("+919000000099", "+919000000099"), sender.accepted.map { it.phone })
        assertEquals(setOf("[TEST > Rahul] Hi Rahul", "[TEST > Test] Hello"), sender.accepted.map { it.body }.toSet())
    }

    @Test
    fun testModeWithNoNumberSendsNothingAtAll() = runTest {
        env.settings.update { it.copy(testMode = true, trainerPhone = "") }
        val id = queue()
        engine.run()
        assertTrue(sender.accepted.isEmpty())
        assertEquals(listOf(BlockReason.NO_TRAINER_NUMBER), blocked)
        assertEquals(MessageStatus.QUEUED, message(id).status)
    }

    @Test
    fun theWindowLimitStopsAt25PartsAndComesBackLater() = runTest {
        repeat(30) { queue() }
        val wake = engine.run()
        assertEquals(25, sender.accepted.size)
        assertNotNull(wake)
        assertTrue(wake!! > env.clock.nowMillis() + Pacing.WINDOW_MILLIS - 60_000)
    }

    @Test
    fun partsCountNotMessages() = runTest {
        repeat(20) { queue(parts = 2, body = "x".repeat(200)) }
        engine.run()
        assertEquals(12, sender.accepted.size)
    }

    @Test
    fun theRestGoAfterTheWindowPasses() = runTest {
        repeat(30) { queue() }
        engine.run()
        env.clock.advance(Pacing.WINDOW_MILLIS + 1_000)
        engine.run()
        assertEquals(30, sender.accepted.size)
    }

    /** Hard rule 3: with no signal, a whole queue must not hit the radio at once. */
    @Test
    fun failedTriesCountAgainstTheWindow() = runTest {
        repeat(30) { queue() }
        sender.respond = { SendOutcome.Failed("No signal") }
        assertNotNull(engine.run())
        assertEquals(25, sender.calls.size)
        env.clock.advance(5 * 60_000L)
        engine.run()
        assertEquals("the window is still full", 25, sender.calls.size)
    }

    @Test
    fun aRetryCountsAsAnotherSend() = runTest {
        repeat(10) { queue() }
        sender.respond = { SendOutcome.Failed("No signal") }
        engine.run()
        assertEquals(10, sender.calls.size)

        repeat(20) { queue() }
        env.clock.advance(5 * 60_000L)
        engine.run()
        assertEquals(25, sender.calls.size)
    }

    @Test
    fun aBlockedTryDoesNotUseUpTheLimits() = runTest {
        repeat(3) { queue() }
        sender.respond = { SendOutcome.Blocked(BlockReason.NO_PERMISSION) }
        engine.run()
        assertTrue(messages.sendsForPacing().isEmpty())
    }

    @Test
    fun theRealPartCountReplacesTheEstimate() = runTest {
        queue(parts = 1)
        sender.respond = { SendOutcome.Accepted(3) }
        engine.run()
        assertEquals(3, messages.sendsForPacing().single().parts)
    }

    @Test
    fun aClockMovedBackDoesNotFreezeSending() = runTest {
        repeat(25) { queue() }
        engine.run()
        assertEquals(25, sender.accepted.size)
        queue(body = "later")
        env.clock.day -= 1
        engine.run()
        assertEquals(26, sender.accepted.size)
    }

    @Test
    fun turningTestModeOnAppliesToTheVeryNextMessage() = runTest {
        repeat(2) { queue() }
        var first = true
        val engine = OutboxEngine(
            messages,
            env.settings,
            sender,
            env.clock,
            memberName = { null },
            pause = {
                if (first) env.settings.update { it.copy(testMode = true, trainerPhone = "+919000000099") }
                first = false
            },
        )
        engine.run()
        assertEquals(listOf("+919876543210", "+919000000099"), sender.accepted.map { it.phone })
    }

    @Test
    fun aLateResultCorrectsARowGivenUpOn() = runTest {
        val id = queue()
        engine.run()
        env.clock.advance(11 * 60_000L)
        engine.run()
        assertEquals(MessageStatus.FAILED, message(id).status)
        messages.onPartResult(id, 1, 1, ok = true, reason = null)
        assertEquals(MessageStatus.SENT, message(id).status)
        assertNull(message(id).lastError)
    }

    @Test
    fun aLateDeliveryReportCorrectsARowGivenUpOn() = runTest {
        val id = queue()
        engine.run()
        env.clock.advance(11 * 60_000L)
        engine.run()
        messages.onDelivered(id, 1, 1)
        assertEquals(MessageStatus.DELIVERED, message(id).status)
        assertNotNull(message(id).sentAt)
    }

    @Test
    fun aDeliveryReportCanOvertakeTheSentResult() = runTest {
        val id = queue()
        engine.run()
        messages.onDelivered(id, 1, 1)
        assertEquals(MessageStatus.SENDING, message(id).status)
        messages.onPartResult(id, 1, 1, ok = true, reason = null)
        assertEquals(MessageStatus.DELIVERED, message(id).status)
    }

    @Test
    fun aCrashBeforeTheRadioDoesNotUseUpATry() = runTest {
        val id = queue()
        messages.markSending(id, 1)
        env.clock.advance(11 * 60_000L)
        messages.recoverStuck()
        assertEquals(MessageStatus.QUEUED, message(id).status)
        assertEquals(0, message(id).attempts)
    }

    @Test
    fun theDailyCapHoldsTheRestForTomorrow() = runTest {
        env.settings.update { it.copy(dailyCapParts = 5) }
        repeat(8) { queue() }
        val wake = engine.run()
        assertEquals(5, sender.accepted.size)
        assertEquals(Dates.millisAt(env.clock.today() + 1, 9), wake)
    }

    @Test
    fun aThankYouGoesBeforeABroadcast() = runTest {
        queue(kind = MessageKind.BROADCAST, body = "broadcast", createdAt = 1)
        queue(kind = MessageKind.OVERDUE_1, body = "overdue", createdAt = 2)
        queue(kind = MessageKind.PAYMENT_THANKS, body = "thanks", createdAt = 3)
        engine.run()
        assertEquals(listOf("thanks", "overdue", "broadcast"), sender.accepted.map { it.body })
    }

    @Test
    fun messagesAreSpacedApart() = runTest {
        repeat(3) { queue() }
        engine.run()
        assertEquals(3, pauses.size)
        assertTrue(pauses.all { it in 3_000L..5_000L })
    }

    @Test
    fun aMessageWaitsForItsTime() = runTest {
        val at = env.clock.nowMillis() + 10_000
        val id = queue(notBefore = at)
        assertEquals(at, engine.run())
        assertTrue(sender.accepted.isEmpty())
        assertEquals(MessageStatus.QUEUED, message(id).status)
    }

    @Test
    fun plannedRemindersWaitOutTheNightButManualOnesDoNot() = runTest {
        env.clock.hour = 22
        queue(kind = MessageKind.OVERDUE_1, body = "reminder")
        queue(kind = MessageKind.MANUAL, body = "manual")
        val wake = engine.run()
        assertEquals(listOf("manual"), sender.accepted.map { it.body })
        assertEquals(Dates.millisAt(env.clock.today() + 1, 8), wake)
    }

    @Test
    fun thePaymentThankYouStaysCancellableThroughTheUndoWindow() = runTest {
        env.settings.update { it.copy(gymName = "MB Fitness Club") }
        val id = (env.repository.addMember(MemberDraft("Rahul Sharma", "9876543210", 1, env.clock.today(), smsConsent = true)) as MemberSaveResult.Saved).memberId
        val saved = env.repository.recordRenewal(RenewalRequest(id, 1, 1200, PaymentMode.CASH, env.clock.today()))

        // The outbox runs straight away, but the thank-you waits.
        assertNotNull(engine.run())
        assertTrue(sender.accepted.isEmpty())

        env.repository.undoPayment(saved)
        env.clock.advance(11_000)
        assertNull(engine.run())
        assertTrue("an undone payment must never be thanked", sender.accepted.isEmpty())
    }

    @Test
    fun theThankYouGoesOnceUndoCanNoLongerCancelIt() = runTest {
        val id = (env.repository.addMember(MemberDraft("Rahul Sharma", "9876543210", 1, env.clock.today(), smsConsent = true)) as MemberSaveResult.Saved).memberId
        env.repository.recordRenewal(RenewalRequest(id, 1, 1200, PaymentMode.CASH, env.clock.today()))
        env.clock.advance(11_000)
        engine.run()
        assertTrue(sender.accepted.single().body, "Received Rs 1200" in sender.accepted.single().body)
        assertFalse(sender.accepted.single().body.startsWith("[TEST"))
    }
}
