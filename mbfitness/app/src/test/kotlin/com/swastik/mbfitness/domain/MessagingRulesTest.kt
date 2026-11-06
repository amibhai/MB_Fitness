package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MessagingRulesTest {
    private val day = Dates.of(2026, 11, 4)
    private fun at(hour: Int, minute: Int = 0, onDay: Long = day) = Dates.millisAt(onDay, hour, minute)
    private val hour = 60L * 60 * 1000

    @Test
    fun retriesBackOffThenStop() {
        assertEquals(5 * 60_000L, RetryPolicy.delayAfter(1))
        assertEquals(30 * 60_000L, RetryPolicy.delayAfter(2))
        assertNull(RetryPolicy.delayAfter(0))
        assertNull(RetryPolicy.delayAfter(3))
        assertNull(RetryPolicy.delayAfter(9))
    }

    @Test
    fun spacingStaysBetween3And5Seconds() {
        val random = Random(1)
        val all = List(500) { SendingRules.spacingMillis(random) }
        assertTrue(all.all { it in 3_000L..5_000L })
        assertTrue("should vary", all.toSet().size > 100)
    }

    @Test
    fun testBodyNamesTheMemberAndStaysGsm7() {
        val body = SendingRules.testBody("Rahul Sharma", "Hi Rahul")
        assertEquals("[TEST > Rahul] Hi Rahul", body)
        assertEquals(SmsEncoding.GSM7, SmsSegments.count(body).encoding)
        assertEquals("[TEST > Test] x", SendingRules.testBody(null, "x"))
        assertEquals("[TEST > Test] x", SendingRules.testBody("  ", "x"))
    }

    @Test
    fun theNextRunIsTodayUntilTheTimePasses() {
        assertEquals(at(9), ReminderSchedule.nextRunAt(at(8, 59), 9, 0))
        assertEquals(at(9, onDay = day + 1), ReminderSchedule.nextRunAt(at(9), 9, 0))
        assertEquals(at(9, onDay = day + 1), ReminderSchedule.nextRunAt(at(15), 9, 0))
        assertEquals(at(7, 30, day + 1), ReminderSchedule.nextRunAt(at(8), 7, 30))
    }

    @Test
    fun aRunIsDueOnceThePastTimeAndNotDoneToday() {
        assertFalse(ReminderSchedule.isRunDue(at(8, 59), day - 1, 9, 0))
        assertTrue(ReminderSchedule.isRunDue(at(9), day - 1, 9, 0))
        assertTrue(ReminderSchedule.isRunDue(at(23), null, 9, 0))
        assertFalse("already ran today", ReminderSchedule.isRunDue(at(23), day, 9, 0))
    }

    @Test
    fun healthGoesAmberAt26HoursAndRedAt50() {
        val now = at(12)
        assertEquals(RunHealth.OK, ReminderSchedule.health(now, null))
        assertEquals(RunHealth.OK, ReminderSchedule.health(now, now - 26 * hour))
        assertEquals(RunHealth.LATE, ReminderSchedule.health(now, now - 26 * hour - 1))
        assertEquals(RunHealth.LATE, ReminderSchedule.health(now, now - 50 * hour))
        assertEquals(RunHealth.VERY_LATE, ReminderSchedule.health(now, now - 50 * hour - 1))
    }

    @Test
    fun lastRanSaysWhenInWords() {
        assertEquals("Reminders haven't run yet", ReminderSchedule.lastRanText(at(12), null))
        assertEquals("Reminders last ran: today 9:00 AM", ReminderSchedule.lastRanText(at(12), at(9)))
        assertEquals("Reminders last ran: yesterday 9:00 AM", ReminderSchedule.lastRanText(at(12), at(9, onDay = day - 1)))
        assertEquals("Reminders last ran: 1 Nov 9:00 AM", ReminderSchedule.lastRanText(at(12), at(9, onDay = day - 3)))
    }

    @Test
    fun remindersCountAsStoppedAfter2Days() {
        assertFalse(ReminderSchedule.hasStopped(at(12), null))
        assertFalse(ReminderSchedule.hasStopped(at(12), day - 1))
        assertTrue(ReminderSchedule.hasStopped(at(12), day - 2))
    }

    private val facts = SummaryFacts(due = 3, overdue = 4, balancePending = 2400, onTheirWay = 12, failed = 0)

    @Test
    fun theSummaryReadsLikeTheSpec() {
        val text = MorningSummary.build(facts)
        assertEquals("Today: 3 due · 4 overdue · ₹2,400 balance pending", text.title)
        assertEquals("12 reminders on their way", text.detail)
        assertFalse(text.opensMessages)
        assertEquals("1 reminder on their way", MorningSummary.build(facts.copy(onTheirWay = 1)).detail)
    }

    @Test
    fun failuresComeFirstAndOpenMessages() {
        val text = MorningSummary.build(facts.copy(failed = 2))
        assertEquals("⚠ 2 failed. Tap to retry.", text.detail)
        assertTrue(text.opensMessages)
    }

    @Test
    fun aQuietDaySaysWhatWillGoNext() {
        val text = MorningSummary.build(facts.copy(onTheirWay = 0, quietReason = "Diwali", willGoNext = 6))
        assertEquals("Quiet day (Diwali) · 6 will go tomorrow", text.detail)
        val later = MorningSummary.build(facts.copy(quietReason = "Diwali", willGoNext = 6, nextIsTomorrow = false))
        assertEquals("Quiet day (Diwali) · 6 will go next", later.detail)
        assertEquals("Quiet day (Diwali) · 0 will go tomorrow", MorningSummary.build(facts.copy(quietReason = "Diwali")).detail)
    }

    @Test
    fun heldRemindersAskForTheMissingSetting() {
        assertEquals(
            "⚠ 2 waiting. Add your phone number in Settings.",
            MorningSummary.build(facts.copy(onTheirWay = 0, held = 2)).detail,
        )
    }

    @Test
    fun birthdaysAreAddedOn() {
        val text = MorningSummary.build(facts.copy(birthdays = listOf("Rahul", "Anil")))
        assertEquals("12 reminders on their way · 🎂 Birthday: Rahul, Anil", text.detail)
    }

    @Test
    fun aDayWithNothingIsAllClear() {
        val nothing = SummaryFacts(0, 0, 0, 0, 0)
        assertTrue(MorningSummary.isAllClear(nothing))
        assertEquals("No reminders today", MorningSummary.build(nothing).detail)
        assertFalse(MorningSummary.isAllClear(nothing.copy(due = 1)))
        assertFalse(MorningSummary.isAllClear(nothing.copy(birthdays = listOf("Rahul"))))
    }

    private fun member(id: Long, state: MemberState = MemberState.ACTIVE, expiry: Long = day + 30, balance: Long = 0, consent: Boolean = true) =
        AudienceMember(id, "M$id", "+91900000000$id", state, expiry, balance, consent)

    private val people = listOf(
        member(1),
        member(2, expiry = day - 1),
        member(3, expiry = day + 2),
        member(4, balance = 700),
        member(5, state = MemberState.FROZEN),
        member(6, state = MemberState.LEFT),
        member(7, consent = false),
    )

    private fun ids(audience: BroadcastAudience, picked: Set<Long> = emptySet()) =
        BroadcastRules.recipients(audience, people, picked, day).map { it.id }

    @Test
    fun eachAudienceReachesTheRightPeople() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(BroadcastAudience.ALL_ACTIVE))
        assertEquals(listOf(2L), ids(BroadcastAudience.OVERDUE))
        assertEquals(listOf(3L), ids(BroadcastAudience.DUE_SOON))
        assertEquals(listOf(4L), ids(BroadcastAudience.BALANCE_PENDING))
        assertEquals(listOf(5L), ids(BroadcastAudience.FROZEN))
    }

    @Test
    fun membersWithoutConsentNeverGetABroadcastEvenIfPicked() {
        assertEquals(listOf(1L), ids(BroadcastAudience.HAND_PICKED, setOf(1, 7)))
        BroadcastAudience.entries.forEach { assertFalse(7L in ids(it, setOf(7))) }
    }

    @Test
    fun exMembersAreOnlyReachedByHandWithAWarning() {
        (BroadcastAudience.entries - BroadcastAudience.HAND_PICKED).forEach { assertFalse(6L in ids(it)) }
        assertEquals(listOf(6L), ids(BroadcastAudience.HAND_PICKED, setOf(6)))
        assertTrue(BroadcastRules.picksLeftMembers(people, setOf(6)))
        assertFalse(BroadcastRules.picksLeftMembers(people, setOf(1)))
    }

    @Test
    fun scheduleFieldsHaveSensibleLimits() {
        assertNull(TemplateRules.scheduleProblem(MessageKind.BEFORE_DUE, -3, null, null))
        assertEquals("Must be 1 to 60 days before expiry", TemplateRules.scheduleProblem(MessageKind.BEFORE_DUE, 3, null, null))
        assertEquals("Must be 1 to 60 days before expiry", TemplateRules.scheduleProblem(MessageKind.BEFORE_DUE, -61, null, null))
        assertNull(TemplateRules.scheduleProblem(MessageKind.OVERDUE_2, 7, 7, 4))
        assertEquals("Must be 1 to 60 days after", TemplateRules.scheduleProblem(MessageKind.OVERDUE_1, 0, null, null))
        assertEquals("Repeat must be every 1 to 30 days", TemplateRules.scheduleProblem(MessageKind.OVERDUE_2, 7, 0, 4))
        assertEquals("Can repeat 1 to 12 times", TemplateRules.scheduleProblem(MessageKind.OVERDUE_2, 7, 7, 13))
        assertNull(TemplateRules.scheduleProblem(MessageKind.ON_DUE, 0, null, null))
    }

    @Test
    fun onlyTheRightTemplatesCanMove() {
        assertTrue(TemplateRules.hasOffset(MessageKind.BEFORE_DUE))
        assertFalse(TemplateRules.hasOffset(MessageKind.ON_DUE))
        assertFalse(TemplateRules.hasOffset(MessageKind.BIRTHDAY))
        assertTrue(TemplateRules.hasRepeat(MessageKind.BALANCE_DUE))
        assertFalse(TemplateRules.hasRepeat(MessageKind.OVERDUE_1))
    }

    @Test
    fun theSenderGoesOnTheEndOnlyIfItIsNotAlreadyThere() {
        assertEquals("Hello - MB Fitness Club", SenderLine.add(" Hello ", "MB Fitness Club"))
        assertEquals("From mb fitness club, hello", SenderLine.add("From mb fitness club, hello", "MB Fitness Club"))
        assertEquals("Hello", SenderLine.add("Hello", " "))
    }

    @Test
    fun aBroadcastIsRenderedPerPerson() {
        val result = BroadcastRules.render("Hi {name}, call {trainer_phone}. {gym_name}", "Rahul Sharma", "MB Fitness Club", "98765 43210", PackLanguage.ENGLISH)
        assertEquals("Hi Rahul, call 98765 43210. MB Fitness Club", result.text)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun aBroadcastAddsTheGymAndStraightensQuotes() {
        val result = BroadcastRules.render("Don’t forget", "Rahul", "MB Fitness Club", "", PackLanguage.ENGLISH)
        assertEquals("Don't forget - MB Fitness Club", result.text)
    }

    @Test
    fun aBroadcastWaitingForASettingSaysSo() {
        val result = BroadcastRules.render("Call {trainer_phone}", "Rahul", "MB Fitness Club", "", PackLanguage.ENGLISH)
        assertEquals(setOf("trainer_phone"), result.missing)
    }

    @Test
    fun remindersOffStillGetBroadcastsUnlessExcluded() {
        val quiet = AudienceMember(1, "A", "+919000000001", MemberState.ACTIVE, day + 30, 0, smsConsent = true, remindersEnabled = false)
        val list = listOf(quiet)
        assertEquals(listOf(quiet), BroadcastRules.recipients(BroadcastAudience.ALL_ACTIVE, list, emptySet(), day))
        assertTrue(BroadcastRules.recipients(BroadcastAudience.ALL_ACTIVE, list, emptySet(), day, skipRemindersOff = true).isEmpty())
    }

    @Test
    fun everyPresetUsesOnlyPlaceholdersItsKindCanFill() {
        PackLanguage.entries.forEach { language ->
            TemplatePacks.defaults(language).forEach { template ->
                assertEquals(
                    "${template.kind} in $language",
                    emptyList<TemplateProblem>(),
                    TemplateEngine.validate(template.body, TemplateRules.placeholdersFor(template.kind), language),
                )
            }
        }
    }

    @Test
    fun aTemplateCantAskForWhatItsKindHasNoValueFor() {
        fun allowed(kind: MessageKind, name: String) = name in TemplateRules.placeholdersFor(kind)
        assertFalse(allowed(MessageKind.ON_DUE, "receipt_no"))
        assertFalse(allowed(MessageKind.ON_DUE, "days_overdue"))
        assertTrue(allowed(MessageKind.OVERDUE_1, "days_overdue"))
        assertFalse(allowed(MessageKind.OVERDUE_1, "receipt_no"))
        assertTrue(allowed(MessageKind.PAYMENT_THANKS, "receipt_no"))
        assertFalse(allowed(MessageKind.PAYMENT_THANKS, "plan"))
        assertEquals(TemplateEngine.BROADCAST_PLACEHOLDERS, TemplateRules.placeholdersFor(MessageKind.BROADCAST))
        assertTrue(TemplateRules.placeholdersFor(MessageKind.MANUAL).isEmpty())
        assertTrue(TemplateRules.placeholdersFor(MessageKind.TEST).isEmpty())
    }

    @Test
    fun anAadhaarPlaceholderIsNeverAllowed() {
        MessageKind.entries.forEach { kind ->
            assertFalse(kind.name, "aadhaar" in TemplateRules.placeholdersFor(kind))
            assertEquals(
                TemplateProblem.UnknownPlaceholder("aadhaar"),
                TemplateEngine.validate("{aadhaar}", TemplateRules.placeholdersFor(kind)).first(),
            )
        }
    }

    @Test
    fun problemsAreInPlainWords() {
        assertEquals("Write a message first", TemplateRules.describe(TemplateProblem.Empty))
        assertEquals("{x} can't be used here", TemplateRules.describe(TemplateProblem.UnknownPlaceholder("x")))
        assertTrue("Links" in TemplateRules.describe(TemplateProblem.ContainsLink))
        assertEquals("Too long: 5 SMS parts. The limit is 4.", TemplateRules.describe(TemplateProblem.TooManyParts(5)))
    }

    @Test
    fun longMessagesAreWarnedAbout() {
        assertNull(TemplateRules.lengthWarning(2))
        assertEquals("This costs 3 SMS per member. Shorter is better.", TemplateRules.lengthWarning(3))
    }

    @Test
    fun aMissingSettingIsNamed() {
        assertNull(TemplateRules.describeMissing(emptySet()))
        assertTrue("phone number" in TemplateRules.describeMissing(setOf("trainer_phone"))!!)
        assertTrue("UPI ID" in TemplateRules.describeMissing(setOf("upi_id"))!!)
        assertTrue("gym's name" in TemplateRules.describeMissing(setOf("gym_name"))!!)
        assertTrue("{plan}" in TemplateRules.describeMissing(setOf("plan"))!!)
    }

    @Test
    fun everyKindHasALabel() {
        assertEquals(MessageKind.entries.size, MessageKind.entries.map(MessageLabels::kind).toSet().size)
    }

    @Test
    fun timesReadNaturally() {
        val now = at(10)
        assertEquals("9:00 AM", MessageLabels.whenText(at(9), now))
        assertEquals("yesterday 9:00 AM", MessageLabels.whenText(at(9, onDay = day - 1), now))
        assertEquals("tomorrow 9:00 AM", MessageLabels.whenText(at(9, onDay = day + 1), now))
        assertEquals("${Dates.formatShort(day - 5)}, 9:00 AM", MessageLabels.whenText(at(9, onDay = day - 5), now))
    }

    @Test
    fun statusLinesSayWhatIsHappening() {
        val now = at(10)
        fun line(status: MessageStatus, notBefore: Long = 0, sent: Long? = null, delivered: Long? = null, error: String? = null) =
            MessageLabels.status(status, notBefore, sent, delivered, error, now)
        assertEquals("Waiting to send", line(MessageStatus.QUEUED))
        assertEquals("Goes 10:05 AM", line(MessageStatus.QUEUED, notBefore = at(10, 5)))
        assertEquals("No signal. Tries again 10:05 AM.", line(MessageStatus.QUEUED, notBefore = at(10, 5), error = "No signal"))
        assertEquals("Waiting: SMS permission is off", line(MessageStatus.QUEUED, error = "SMS permission is off"))
        assertEquals("Sending", line(MessageStatus.SENDING))
        assertEquals("Sent 9:00 AM", line(MessageStatus.SENT, sent = at(9)))
        assertEquals("Sent", line(MessageStatus.SENT))
        assertEquals("Delivered 9:00 AM", line(MessageStatus.DELIVERED, delivered = at(9)))
        assertEquals("Delivered", line(MessageStatus.DELIVERED))
        assertEquals("No signal", line(MessageStatus.FAILED, error = "No signal"))
        assertEquals("Could not send", line(MessageStatus.FAILED))
        assertEquals("Cancelled", line(MessageStatus.CANCELLED))
        assertEquals("Replaced", line(MessageStatus.CANCELLED, error = "Replaced"))
    }

    @Test
    fun theNextQuietDayCountsTodayAndHolidays() {
        val friday = Dates.of(2027, 1, 1)
        val sundays = QuietCalendar(setOf(java.time.DayOfWeek.SUNDAY))
        assertEquals(friday + 2, sundays.nextQuietDay(friday))
        assertEquals(friday + 2, sundays.nextQuietDay(friday + 2))
        val holiday = QuietCalendar(setOf(java.time.DayOfWeek.SUNDAY), listOf(QuietDate(friday + 1, "Diwali")))
        assertEquals(friday + 1, holiday.nextQuietDay(friday))
        assertNull(QuietCalendar(emptySet()).nextQuietDay(friday))
    }
}
