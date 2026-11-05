package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class ReminderPlannerTest {
    private fun d(year: Int, month: Int, day: Int) = Dates.of(year, month, day)

    /** A Wednesday, so offsets don't land on the default quiet Sunday by accident. */
    private val expiry = d(2026, 12, 16)
    private val noQuietDays = QuietCalendar(weekdays = emptySet())
    private val settings = PlannerSettings("MB Fitness Club", "gym@okbank", "98765 43210", PackLanguage.ENGLISH)

    private fun member(
        id: Long = 1,
        expiry: Long = this.expiry,
        balance: Long = 0,
        state: MemberState = MemberState.ACTIVE,
        dob: Long? = null,
        consent: Boolean = true,
        reminders: Boolean = true,
        part: PartPayment? = null,
    ) = PlannerMember(id, "Rahul Sharma", "+919876543210", state, expiry, balance, 1200, "1 month", dob, consent, reminders, part)

    private fun input(
        today: Long,
        vararg members: PlannerMember,
        history: PlannerHistory = PlannerHistory(),
        quiet: QuietCalendar = noQuietDays,
        templates: List<TemplateConfig> = TemplatePacks.defaults(PackLanguage.ENGLISH),
        settings: PlannerSettings = this.settings,
        ignoreQuietDay: Boolean = false,
    ) = PlannerInput(today, members.toList(), templates, history, quiet, settings, ignoreQuietDay)

    private fun plan(today: Long, vararg members: PlannerMember, history: PlannerHistory = PlannerHistory(), quiet: QuietCalendar = noQuietDays) =
        ReminderPlanner.plan(input(today, *members, history = history, quiet = quiet)).messages

    private fun without(vararg kinds: MessageKind) =
        TemplatePacks.defaults(PackLanguage.ENGLISH).map { if (it.kind in kinds) it.copy(enabled = false) else it }

    /** Runs the planner once a day and records what went, feeding history back in like the app does. */
    private fun simulate(
        from: Long,
        to: Long,
        member: PlannerMember,
        quiet: QuietCalendar = noQuietDays,
        templates: List<TemplateConfig> = TemplatePacks.defaults(PackLanguage.ENGLISH),
        skipDays: Set<Long> = emptySet(),
    ): List<Pair<Long, PlannedMessage>> {
        val used = mutableSetOf<String>()
        val last = mutableMapOf<Long, Long>()
        val sent = mutableListOf<Pair<Long, PlannedMessage>>()
        for (day in from..to) {
            if (day in skipDays) continue
            val planned = ReminderPlanner.plan(
                input(day, member, history = PlannerHistory(used, last), quiet = quiet, templates = templates),
            ).messages
            planned.forEach {
                used += it.dedupeKey
                last[it.memberId] = day
                sent += day to it
            }
        }
        return sent
    }

    @Test
    fun everyTemplateFiresOnItsDayAndOverdueRepeatsStopAtTheMax() {
        val sent = simulate(expiry - 10, expiry + 60, member())
        assertEquals(
            listOf(
                -3L to MessageKind.BEFORE_DUE,
                0L to MessageKind.ON_DUE,
                3L to MessageKind.OVERDUE_1,
                7L to MessageKind.OVERDUE_2,
                14L to MessageKind.OVERDUE_2,
                21L to MessageKind.OVERDUE_2,
                28L to MessageKind.OVERDUE_2,
            ),
            sent.map { (day, message) -> day - expiry to message.kind },
        )
    }

    @Test
    fun theWordingOfEachMessageIsTrueOnTheDayItGoes() {
        val sent = simulate(expiry - 3, expiry + 7, member()).associate { (day, m) -> day - expiry to m.body }
        assertEquals(
            "Hi Rahul, your MB Fitness Club membership expires on 16 Dec. Fee: Rs 1200. UPI: gym@okbank. Thank you!",
            sent[-3],
        )
        assertEquals("Hi Rahul, your MB Fitness Club fee of Rs 1200 is due today. UPI: gym@okbank. Please ignore if already paid.", sent[0])
        assertEquals("Hi Rahul, your MB Fitness Club fee of Rs 1200 is 3 days overdue. Kindly pay soon. UPI: gym@okbank", sent[3])
        assertEquals("Hi Rahul, reminder: your MB Fitness Club fee is 7 days pending. Please pay or call 98765 43210.", sent[7])
    }

    @Test
    fun runningTheSameDayTwiceSendsNothingNew() {
        val first = plan(expiry, member())
        assertEquals(1, first.size)
        val history = PlannerHistory(first.map { it.dedupeKey }.toSet(), mapOf(1L to expiry))
        assertTrue(plan(expiry, member(), history = history).isEmpty())
        // Even if only the keys survived, the keys alone must stop it.
        assertTrue(plan(expiry, member(), history = PlannerHistory(first.map { it.dedupeKey }.toSet())).isEmpty())
    }

    @Test
    fun noConsentOrRemindersOffOrFrozenOrLeftMeansNothing() {
        assertTrue(plan(expiry, member(consent = false)).isEmpty())
        assertTrue(plan(expiry, member(reminders = false)).isEmpty())
        assertTrue(plan(expiry, member(state = MemberState.FROZEN)).isEmpty())
        assertTrue(plan(expiry, member(state = MemberState.LEFT)).isEmpty())
    }

    @Test
    fun aDisabledTemplateIsNeverSent() {
        val sent = simulate(expiry - 3, expiry, member(), templates = without(MessageKind.ON_DUE, MessageKind.BEFORE_DUE))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun theAmountIncludesAnyBalanceAndAdvances() {
        assertTrue("Rs 1900" in plan(expiry, member(balance = 700)).single().body)
        assertTrue("Rs 1000" in plan(expiry, member(balance = -200)).single().body)
        assertTrue("Rs 0" in plan(expiry, member(balance = -2000)).single().body)
    }

    @Test
    fun onlyOneSmsPerMemberPerDayWithTheHighestPriorityWinning() {
        val part = PartPayment(5, expiry - 7)
        val busy = member(balance = 700, part = part, dob = d(1995, 12, 16))
        assertEquals(MessageKind.ON_DUE, plan(expiry, busy).single().kind)
    }

    @Test
    fun aBirthdayGoesWhenNothingElseIsDueAndOnlyOnceAYear() {
        val birthday = member(expiry = expiry + 100, dob = d(1995, 12, 16))
        val message = plan(expiry, birthday).single()
        assertEquals(MessageKind.BIRTHDAY, message.kind)
        assertEquals("m1:BIRTHDAY:y2026", message.dedupeKey)
        assertTrue(plan(expiry, birthday, history = PlannerHistory(setOf(message.dedupeKey))).isEmpty())
        assertEquals(1, plan(d(2027, 12, 16), birthday.copy(expiry = d(2028, 6, 1))).size)
    }

    @Test
    fun aLeapDayBirthdayGoesOn28FebInAnOrdinaryYear() {
        val leap = member(expiry = d(2027, 9, 1), dob = d(2000, 2, 29))
        assertEquals(MessageKind.BIRTHDAY, plan(d(2027, 2, 28), leap).single().kind)
        assertTrue(plan(d(2027, 3, 1), leap).isEmpty())
    }

    @Test
    fun aMissedBirthdayIsNotSentLate() {
        val birthday = member(expiry = expiry + 100, dob = d(1995, 12, 15))
        assertTrue(plan(expiry, birthday).isEmpty())
    }

    @Test
    fun balanceRemindersFollowThePartPaymentWeeklyUpToThree() {
        val paidOn = expiry - 60
        val sent = simulate(paidOn, expiry - 5, member(balance = 700, part = PartPayment(5, paidOn)))
        assertEquals(listOf(7L, 14L, 21L), sent.map { (day, _) -> day - paidOn })
        assertTrue(sent.all { (_, m) -> m.kind == MessageKind.BALANCE_DUE })
        assertTrue("Rs 700" in sent.first().second.body)
    }

    @Test
    fun noBalanceReminderOnceClearedOrExpired() {
        val paidOn = expiry - 7
        assertTrue(plan(expiry - 0, member(expiry = expiry - 1, balance = 700, part = PartPayment(5, paidOn))).none { it.kind == MessageKind.BALANCE_DUE })
        assertTrue(plan(expiry, member(expiry = expiry + 30, balance = 0, part = PartPayment(5, paidOn))).isEmpty())
        assertTrue(plan(expiry, member(expiry = expiry + 30, balance = -100, part = PartPayment(5, paidOn))).isEmpty())
    }

    @Test
    fun aNewPartPaymentRestartsTheBalanceSchedule() {
        val today = expiry
        val old = DedupeKeys.balance(1, 5, 1)
        val fresh = member(expiry = expiry + 30, balance = 300, part = PartPayment(6, today - 7))
        val message = plan(today, fresh, history = PlannerHistory(setOf(old))).single()
        assertEquals(DedupeKeys.balance(1, 6, 1), message.dedupeKey)
    }

    @Test
    fun aBalanceReminderKeepsItsDistanceFromOtherReminders() {
        val today = expiry - 10
        val m = member(balance = 700, part = PartPayment(5, today - 7))
        assertTrue(plan(today, m, history = PlannerHistory(lastReminderDay = mapOf(1L to today - 1))).isEmpty())
        assertTrue(plan(today, m, history = PlannerHistory(lastReminderDay = mapOf(1L to today - 2))).isEmpty())
        assertEquals(1, plan(today, m, history = PlannerHistory(lastReminderDay = mapOf(1L to today - 3))).size)
    }

    @Test
    fun aQuietDayDelaysRemindersToTheNextNormalDayWithTruthfulWording() {
        // The spec's example: expiry falls on a quiet Sunday, Monday says "1 day overdue".
        val sunday = d(2026, 12, 20)
        val quiet = QuietCalendar(setOf(DayOfWeek.SUNDAY))
        val sent = simulate(sunday - 3, sunday + 8, member(expiry = sunday), quiet = quiet)
        assertEquals(
            listOf(-3L to MessageKind.BEFORE_DUE, 1L to MessageKind.OVERDUE_1, 8L to MessageKind.OVERDUE_2),
            sent.map { (day, m) -> day - sunday to m.kind },
        )
        assertTrue("1 day overdue" in sent[1].second.body)
        assertTrue("8 days pending" in sent[2].second.body)
    }

    @Test
    fun aDatedHolidayIsQuietToo() {
        val quiet = QuietCalendar(emptySet(), listOf(QuietDate(expiry, "Diwali")))
        assertTrue(plan(expiry, member(), quiet = quiet).isEmpty())
        assertEquals(MessageKind.OVERDUE_1, plan(expiry + 1, member(), quiet = quiet).single().kind)
    }

    @Test
    fun consecutiveQuietDaysSendOneMessageAfterwards() {
        val quiet = QuietCalendar(emptySet(), listOf(QuietDate(expiry, "A"), QuietDate(expiry + 1, "B"), QuietDate(expiry + 2, "C")))
        val sent = simulate(expiry, expiry + 6, member(), quiet = quiet)
        assertEquals(listOf(3L to MessageKind.OVERDUE_1), sent.map { (day, m) -> day - expiry to m.kind })
    }

    @Test
    fun birthdaysStillGoOnAQuietDay() {
        val quiet = QuietCalendar(setOf(Dates.dayOfWeek(expiry)))
        val birthday = member(expiry = expiry, dob = d(1995, 12, 16))
        assertEquals(MessageKind.BIRTHDAY, plan(expiry, birthday, quiet = quiet).single().kind)
    }

    @Test
    fun runRemindersNowCanOverrideAQuietDay() {
        val quiet = QuietCalendar(setOf(Dates.dayOfWeek(expiry)))
        val forced = ReminderPlanner.plan(input(expiry, member(), quiet = quiet, ignoreQuietDay = true)).messages
        assertEquals(MessageKind.ON_DUE, forced.single().kind)
    }

    @Test
    fun theNextNormalDayPlanSaysWhatWillGo() {
        val quiet = QuietCalendar(setOf(Dates.dayOfWeek(expiry)))
        val (day, result) = ReminderPlanner.planForNextNormalDay(input(expiry, member(), quiet = quiet))!!
        assertEquals(expiry + 1, day)
        assertEquals(MessageKind.OVERDUE_1, result.messages.single().kind)
        val allQuiet = QuietCalendar(DayOfWeek.entries.toSet())
        assertEquals(null, ReminderPlanner.planForNextNormalDay(input(expiry, member(), quiet = allQuiet)))
    }

    @Test
    fun catchUpAfterThreeMissedDaysSendsOneTruthfulMessage() {
        // Phone off from 2 days before expiry until 2 days after.
        val off = (expiry - 2..expiry + 1).toSet()
        val sent = simulate(expiry - 3, expiry + 3, member(), skipDays = off)
        assertEquals(
            listOf(-3L to MessageKind.BEFORE_DUE, 2L to MessageKind.OVERDUE_1),
            sent.map { (day, m) -> day - expiry to m.kind },
        )
        assertTrue("2 days overdue" in sent[1].second.body)
    }

    @Test
    fun aMissedBeforeDueStillGoesIfItsWordingIsStillTrue() {
        assertEquals(MessageKind.BEFORE_DUE, plan(expiry - 2, member()).single().kind)
        assertEquals(MessageKind.BEFORE_DUE, plan(expiry - 1, member()).single().kind)
    }

    @Test
    fun catchUpSkipsAnythingDisabledRatherThanInventingWording() {
        fun firstFrom(today: Long, vararg off: MessageKind) =
            ReminderPlanner.plan(input(today, member(), templates = without(*off))).messages
        // Before-due is the latest point, but on the expiry day it would have to become on-due.
        assertTrue(firstFrom(expiry, MessageKind.ON_DUE).isEmpty())
        // On-due is the latest point, but a day late it would have to become overdue-1.
        assertTrue(firstFrom(expiry + 1, MessageKind.OVERDUE_1).isEmpty())
        // With on-due off, a before-due a day late moves on to overdue-1, which is true.
        assertEquals(MessageKind.OVERDUE_1, firstFrom(expiry + 1, MessageKind.ON_DUE).single().kind)
    }

    @Test
    fun aRepeatedOverdueSkipsToTheLatestPointAfterAGap() {
        val off = (expiry + 4..expiry + 15).toSet()
        val sent = simulate(expiry, expiry + 16, member(), skipDays = off)
        assertEquals(listOf(0L, 3L, 16L), sent.map { (day, _) -> day - expiry })
        assertEquals("m1:OVERDUE_2:e$expiry:n2", sent.last().second.dedupeKey)
    }

    @Test
    fun longGoneMembersAreNeverTexted() {
        // The last point is day 28. It stays valid for a week, then is dropped for good.
        assertEquals(1, plan(expiry + 35, member()).size)
        assertTrue(plan(expiry + 36, member()).isEmpty())
        assertTrue(plan(expiry + 400, member()).isEmpty())
    }

    @Test
    fun aRenewalStartsAFreshScheduleForTheNewExpiry() {
        val oldKeys = simulate(expiry - 3, expiry + 3, member()).map { (_, m) -> m.dedupeKey }.toSet()
        val renewed = member(expiry = d(2027, 1, 16))
        assertEquals(MessageKind.BEFORE_DUE, plan(d(2027, 1, 13), renewed, history = PlannerHistory(oldKeys)).single().kind)
    }

    @Test
    fun aBlankSettingHoldsTheReminderInsteadOfSendingItBroken() {
        val noPhone = settings.copy(trainerPhone = "")
        val result = ReminderPlanner.plan(input(expiry + 7, member(), settings = noPhone))
        assertTrue(result.messages.isEmpty())
        assertEquals(HeldReminder(1, MessageKind.OVERDUE_2, setOf("trainer_phone")), result.held.single())
        // Nothing was used up, so it goes as soon as the setting is filled in.
        assertEquals(1, ReminderPlanner.plan(input(expiry + 7, member())).messages.size)
    }

    @Test
    fun aHeldReminderDoesNotBlockALowerPriorityOne() {
        val noPhone = settings.copy(trainerPhone = "")
        val birthday = member(dob = d(1995, 12, 23))
        val result = ReminderPlanner.plan(input(expiry + 7, birthday, settings = noPhone))
        assertEquals(MessageKind.BIRTHDAY, result.messages.single().kind)
        assertEquals(MessageKind.OVERDUE_2, result.held.single().kind)
    }

    @Test
    fun aBlankUpiIdStillSendsWithoutTheUpiClause() {
        val noUpi = settings.copy(upiId = "")
        val message = ReminderPlanner.plan(input(expiry, member(), settings = noUpi)).messages.single()
        assertTrue("UPI" !in message.body)
    }

    @Test
    fun hinglishPackUsesHinglishUnits() {
        val templates = TemplatePacks.defaults(PackLanguage.HINGLISH)
        val message = ReminderPlanner.plan(
            input(expiry + 3, member(), templates = templates, settings = settings.copy(language = PackLanguage.HINGLISH)),
        ).messages.single()
        assertTrue(message.body, "3 din se pending" in message.body)
    }

    @Test
    fun theSameInputAlwaysGivesTheSameOutput() {
        val members = (1L..50L).map { member(id = it, expiry = expiry + it % 10 - 5) }
        val forward = ReminderPlanner.plan(input(expiry, *members.toTypedArray()))
        val backward = ReminderPlanner.plan(input(expiry, *members.reversed().toTypedArray()))
        assertEquals(forward, backward)
    }

    @Test
    fun dedupeKeysKeepEachScheduleSeparate() {
        assertEquals("m1:ON_DUE:e100", DedupeKeys.expiry(1, MessageKind.ON_DUE, 100))
        assertEquals("m1:OVERDUE_2:e100:n3", DedupeKeys.expiry(1, MessageKind.OVERDUE_2, 100, 3))
        assertEquals("m1:BALANCE_DUE:p7:n2", DedupeKeys.balance(1, 7, 2))
        assertEquals("m1:BIRTHDAY:y2027", DedupeKeys.birthday(1, 2027))
        assertEquals("m1:PAYMENT_THANKS:p7", DedupeKeys.thanks(1, 7))
    }
}
