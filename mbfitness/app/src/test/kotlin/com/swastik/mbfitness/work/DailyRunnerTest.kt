package com.swastik.mbfitness.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.data.ErrorLogger
import com.swastik.mbfitness.data.MemberDraft
import com.swastik.mbfitness.data.MemberSaveResult
import com.swastik.mbfitness.data.MessageRepository
import com.swastik.mbfitness.data.QuietDateEntity
import com.swastik.mbfitness.data.RenewalRequest
import com.swastik.mbfitness.data.TestEnvironment
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.domain.PaymentMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek

/** Friday 1 Jan 2027, so nothing lands on the default quiet Sunday by accident. */
@RunWith(AndroidJUnit4::class)
class DailyRunnerTest {
    private val env = TestEnvironment()
    private val db = env.db
    private val messages = MessageRepository(env.db, env.settings, env.clock)
    private val kicker = FakeKicker()
    private val notices = FakeNotices()
    private val runner = DailyRunner(env.repository, messages, env.settings, env.clock, kicker, notices, ErrorLogger(env.db, env.clock))
    private val today get() = env.clock.today()

    @After
    fun tearDown() = env.close()

    private suspend fun configure() = env.settings.update {
        it.copy(trainerPhone = "+919000000099", upiId = "gym@okbank", quietWeekdays = emptySet())
    }

    /** A member whose expiry is [expiry], agreed to reminders. */
    private suspend fun member(phone: String = "9876543210", expiry: Long = today, consent: Boolean = true, name: String = "Rahul Sharma", dob: Long? = null): Long {
        val id = (env.repository.addMember(MemberDraft(name, phone, 1, expiry, smsConsent = consent, dob = dob)) as MemberSaveResult.Saved).memberId
        return id
    }

    private suspend fun queued() = db.messages().withStatusOnly(MessageStatus.QUEUED)

    @Test
    fun aRunQueuesWhatIsDueAndStartsTheOutbox() = runTest {
        configure()
        member()
        val result = runner.run()
        assertEquals(MessageKind.ON_DUE, queued().single().kind)
        assertEquals(1, kicker.kicks)
        assertNotNull(result.plan)
        val settings = env.settings.current()
        assertEquals(today, settings.lastDailyRunDay)
        assertNotNull(settings.lastDailyRunAt)
    }

    @Test
    fun runningTheSameDayAgainSendsNothingNew() = runTest {
        configure()
        member()
        runner.run()
        runner.run()
        runner.run()
        assertEquals(1, queued().size)
    }

    @Test
    fun itOnlyRunsOnceADayOnItsOwn() = runTest {
        configure()
        member()
        assertTrue(runner.runIfDue())
        assertFalse(runner.runIfDue())
        assertEquals(1, notices.summaries.size)
    }

    @Test
    fun itWaitsForTheReminderTime() = runTest {
        configure()
        env.clock.hour = 8
        assertFalse(runner.runIfDue())
        env.clock.hour = 9
        assertTrue(runner.runIfDue())
    }

    @Test
    fun aMissedDayCatchesUpOnTheNextOpen() = runTest {
        configure()
        member()
        env.clock.day = today + 2
        assertTrue(runner.runIfDue())
        val message = queued().single()
        assertEquals(MessageKind.OVERDUE_1, message.kind)
        assertTrue(message.body, "2 days overdue" in message.body)
    }

    @Test
    fun noConsentMeansNoReminders() = runTest {
        configure()
        member(consent = false)
        runner.run()
        assertTrue(queued().isEmpty())
    }

    @Test
    fun aQuietDayQueuesNothingAndSaysWhatWillGo() = runTest {
        configure()
        member()
        db.quietDates().upsert(QuietDateEntity(date = today, label = "Diwali"))
        runner.run()
        assertTrue(queued().isEmpty())
        assertEquals("Quiet day (Diwali) · 1 will go tomorrow", notices.summaries.single().detail)
    }

    @Test
    fun aWeeklyQuietDayIsRespected() = runTest {
        configure()
        env.settings.update { it.copy(quietWeekdays = setOf(Dates.dayOfWeek(today))) }
        member()
        runner.run()
        assertTrue(queued().isEmpty())
    }

    @Test
    fun runRemindersNowCanOverrideAQuietDay() = runTest {
        configure()
        env.settings.update { it.copy(quietWeekdays = DayOfWeek.entries.toSet()) }
        member()
        runner.run(ignoreQuietDay = true)
        assertEquals(1, queued().size)
    }

    @Test
    fun yesterdaysUnsentReminderIsRewordedNotSentStale() = runTest {
        configure()
        val id = member()
        runner.run()
        val yesterdays = queued().single()
        assertEquals(MessageKind.ON_DUE, yesterdays.kind)

        env.clock.day = today + 1
        runner.run()

        val cancelled = db.messages().get(yesterdays.id)!!
        assertEquals(MessageStatus.CANCELLED, cancelled.status)
        val fresh = queued().single()
        assertEquals(id, fresh.memberId)
        assertEquals(MessageKind.OVERDUE_1, fresh.kind)
        assertTrue(fresh.body, "1 day overdue" in fresh.body)
        assertEquals("it keeps its place in the queue", yesterdays.createdAt, fresh.createdAt)
    }

    @Test
    fun payingAfterTheRunCancelsTheReminder() = runTest {
        configure()
        val id = member()
        runner.run()
        val reminder = queued().single { it.kind == MessageKind.ON_DUE }
        env.repository.recordRenewal(RenewalRequest(id, 1, 1200, PaymentMode.CASH, today))
        assertEquals(MessageStatus.CANCELLED, db.messages().get(reminder.id)!!.status)
    }

    @Test
    fun theSummaryCountsTheDay() = runTest {
        configure()
        member()
        member(phone = "9123456780", expiry = today - 5, name = "Anil Kumar")
        runner.run()
        val text = notices.summaries.single()
        assertEquals("Today: 1 due · 1 overdue · ₹0 balance pending", text.title)
        assertEquals("2 reminders on their way", text.detail)
    }

    @Test
    fun birthdaysAreInTheSummary() = runTest {
        configure()
        member(expiry = today + 30, dob = Dates.of(1995, 1, 1))
        runner.run()
        assertTrue(notices.summaries.single().detail, "Birthday: Rahul" in notices.summaries.single().detail)
    }

    @Test
    fun aMissingSettingHoldsTheReminderAndSaysSo() = runTest {
        configure()
        env.settings.update { it.copy(trainerPhone = "") }
        member(expiry = today - 7)
        runner.run()
        assertTrue(queued().isEmpty())
        assertTrue(notices.summaries.single().detail, "waiting" in notices.summaries.single().detail)
    }

    @Test
    fun theSummaryCanBeTurnedOff() = runTest {
        configure()
        env.settings.update { it.copy(morningSummary = false) }
        member()
        runner.run()
        assertTrue(notices.summaries.isEmpty())
        assertEquals(1, queued().size)
    }

    @Test
    fun allClearOnlyNotifiesIfWanted() = runTest {
        configure()
        runner.run()
        assertEquals(1, notices.summaries.size)
        env.settings.update { it.copy(allClearNotice = false, lastDailyRunDay = null) }
        runner.run()
        assertEquals(1, notices.summaries.size)
    }

    @Test
    fun oneFailingStepDoesNotStopTheOthers() = runTest {
        configure()
        member()
        val failing = object : Notices {
            override fun morningSummary(text: com.swastik.mbfitness.domain.SummaryText) {
                error("Notifications broke")
            }

            override fun problem(kind: ProblemKind) = Unit
        }
        val runner = DailyRunner(env.repository, messages, env.settings, env.clock, kicker, failing, ErrorLogger(env.db, env.clock))
        runner.run()
        assertEquals("the reminder still queued", 1, queued().size)
        assertEquals("the run is still recorded", today, env.settings.current().lastDailyRunDay)
        val logged = db.errorLog().observeAll().first()
        assertTrue(logged.single().message, "summary failed" in logged.single().message)
    }

    /** Otherwise the watchdog wouldn't try again and the "stopped" notice could never fire. */
    @Test
    fun aRunThatCouldNotPlanDoesNotCountAsARun() = runTest {
        configure()
        member()
        db.openHelper.writableDatabase.execSQL("DROP TABLE templates")
        assertEquals(null, runner.run().plan)
        assertEquals(null, env.settings.current().lastDailyRunDay)
        assertTrue(notices.summaries.isEmpty())
        assertTrue(db.errorLog().observeAll().first().single().message.startsWith("planning failed"))
    }

    /** Found on the phone: a cancelled reminder used to come straight back at the next run. */
    @Test
    fun aReminderYouCancelStaysCancelled() = runTest {
        configure()
        member()
        runner.run()
        messages.cancel(queued().single().id)
        assertTrue(messages.previewPlan().messages.isEmpty())
        runner.run()
        assertTrue(queued().isEmpty())

        val start = today
        env.clock.day = start + 1
        runner.run()
        assertTrue("not reworded and sent the next day either", queued().isEmpty())

        env.clock.day = start + 3
        runner.run()
        assertEquals("later reminders still go", MessageKind.OVERDUE_1, queued().single().kind)
    }

    private suspend fun failIt(id: Long) {
        db.messages().update(db.messages().get(id)!!.copy(status = MessageStatus.FAILED, attempts = 3, lastError = "No signal"))
    }

    @Test
    fun retryingTodaysFailedReminderSendsTheSameOneAgain() = runTest {
        configure()
        member()
        runner.run()
        val id = queued().single().id
        failIt(id)
        assertEquals(MessageRepository.RetryResult.SENDING_AGAIN, messages.retry(id))
        assertEquals(id, queued().single().id)
        assertEquals(0, queued().single().attempts)
    }

    @Test
    fun retryingAnOldFailedReminderRewordsItSoItStaysTrue() = runTest {
        configure()
        member()
        runner.run()
        val old = queued().single()
        assertTrue(old.body, "due today" in old.body)
        failIt(old.id)

        env.clock.day = today + 1
        assertEquals(MessageRepository.RetryResult.REWORDED, messages.retry(old.id))
        assertEquals(MessageStatus.CANCELLED, db.messages().get(old.id)!!.status)
        assertTrue(queued().single().body, "1 day overdue" in queued().single().body)
    }

    @Test
    fun retryingAReminderForSomeoneWhoHasSincePaidSendsNothing() = runTest {
        configure()
        val id = member()
        runner.run()
        val old = queued().single { it.kind == MessageKind.ON_DUE }
        failIt(old.id)
        env.repository.recordRenewal(RenewalRequest(id, 1, 1200, PaymentMode.CASH, today))

        env.clock.day = today + 1
        assertEquals(MessageRepository.RetryResult.NOT_DUE, messages.retry(old.id))
        assertTrue(queued().none { it.kind.isPlanned })
    }

    @Test
    fun thePaymentThankYouStaysTrueSoItIsRetriedAsIs() = runTest {
        val id = db.messages().insert(
            com.swastik.mbfitness.data.MessageEntity(memberId = null, phone = "+919876543210", kind = MessageKind.PAYMENT_THANKS, body = "Thanks", parts = 1, priority = 1, status = MessageStatus.FAILED, createdAt = env.clock.nowMillis(), notBefore = 0),
        )
        env.clock.day = today + 3
        assertEquals(MessageRepository.RetryResult.SENDING_AGAIN, messages.retry(id))
        assertEquals("Thanks", queued().single().body)
    }

    @Test
    fun theErrorLogKeepsTheNewest200() = runTest {
        val log = ErrorLogger(env.db, env.clock)
        repeat(205) { log.log("Test", "Entry $it") }
        val entries = db.errorLog().observeAll().first()
        assertEquals(200, entries.size)
        assertEquals("Entry 204", entries.first().message)
    }

    @Test
    fun thePreviewChangesNothing() = runTest {
        configure()
        member()
        assertEquals(1, messages.previewPlan().messages.size)
        assertTrue(queued().isEmpty())
        assertEquals(1, messages.previewPlan().messages.size)
    }

    @Test
    fun aBroadcastBecomesOneMessagePerPerson() = runTest {
        val id = member()
        val other = member(phone = "9123456780", name = "Anil Kumar")
        messages.queueBroadcast(
            "Closed tomorrow",
            "ALL_ACTIVE",
            listOf(MessageRepository.BroadcastRecipient(id, "+919876543210", "A"), MessageRepository.BroadcastRecipient(other, "+919123456780", "B")),
        )
        val rows = queued()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.kind == MessageKind.BROADCAST && it.broadcastId != null })
        assertEquals(2, messages.observeRecentBroadcasts(5).first().single().recipientCount)
    }

    @Test
    fun aTestMessageGoesToTheOwnerOrNowhere() = runTest {
        assertEquals(null, messages.queueTest("hello"))
        configure()
        assertNotNull(messages.queueTest("hello"))
        assertEquals("+919000000099", queued().single().phone)
    }
}
