package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC 17: "numbers match a hand-calculated seeded dataset". Every expected number below was
 * worked out by hand from this small gym, and the working is in the comments.
 *
 * Aug: Asha joins and pays 1200. Bala joins and pays 1200.
 * Sep: Asha renews, paying 1200. Chirag joins, charged 3000, paying 2000, so he owes 1000.
 * Oct: Bala leaves, owing 300 from a renewal he part paid (charged 1200, paid 900). Dev is
 *      imported with an opening balance of 500, and pays nothing.
 * Nov: Chirag pays his 1000. Asha freezes. Esha joins and pays 1500 on a 1200 plan, so she's 300
 *      ahead. Dev renews, charged 1200 and paying 1200, so he still owes his 500.
 */
class ReportRulesTest {
    private val asha = 1L
    private val bala = 2L
    private val chirag = 3L
    private val dev = 4L
    private val esha = 5L

    private fun day(month: Int, date: Int) = Dates.of(2026, month, date)

    private val events = listOf(
        ReportEvent(asha, MemberEventType.JOINED, day(8, 5)),
        ReportEvent(bala, MemberEventType.JOINED, day(8, 20)),
        ReportEvent(chirag, MemberEventType.JOINED, day(9, 10)),
        ReportEvent(bala, MemberEventType.LEFT, day(10, 25)),
        ReportEvent(dev, MemberEventType.IMPORTED, day(10, 28)),
        ReportEvent(asha, MemberEventType.FROZEN, day(11, 3)),
        ReportEvent(esha, MemberEventType.JOINED, day(11, 12)),
    )

    private val payments = listOf(
        ReportPayment(1, asha, PaymentKind.RENEWAL, 1200, 1200, day(8, 5)),
        ReportPayment(2, bala, PaymentKind.RENEWAL, 1200, 1200, day(8, 20)),
        ReportPayment(3, asha, PaymentKind.RENEWAL, 1200, 1200, day(9, 5)),
        ReportPayment(4, chirag, PaymentKind.RENEWAL, 3000, 2000, day(9, 10)),
        ReportPayment(5, bala, PaymentKind.RENEWAL, 1200, 900, day(10, 2)),
        ReportPayment(6, dev, PaymentKind.OPENING, 500, 0, day(10, 28)),
        ReportPayment(7, chirag, PaymentKind.BALANCE, 0, 1000, day(11, 4)),
        ReportPayment(8, esha, PaymentKind.RENEWAL, 1200, 1500, day(11, 12)),
        ReportPayment(9, dev, PaymentKind.RENEWAL, 1200, 1200, day(11, 20)),
    )

    private val today = day(11, 25)
    private val november = ReportRules.build(day(11, 1), today, payments, events)
    private val october = ReportRules.build(day(10, 1), today, payments, events)
    private val september = ReportRules.build(day(9, 1), today, payments, events)
    private val august = ReportRules.build(day(8, 1), today, payments, events)

    @Test
    fun collectedIsWhatCameInThatMonth() {
        assertEquals(2400, august.collected) // 1200 + 1200
        assertEquals(3200, september.collected) // 1200 + 2000
        assertEquals(900, october.collected) // 900 + 0
        assertEquals(3700, november.collected) // 1000 + 1500 + 1200
    }

    @Test
    fun theChangeComparesWithLastMonth() {
        assertNull("July had nothing", august.change)
        assertEquals("↑ 33% vs Aug", september.change) // 800 / 2400 = 33.3
        assertEquals("↓ 72% vs Sep", october.change) // 2300 / 3200 = 71.9
        assertEquals("↑ 311% vs Oct", november.change) // 2800 / 900 = 311.1
    }

    @Test
    fun aSameMonthSaysSo() {
        val flat = listOf(
            ReportPayment(1, asha, PaymentKind.RENEWAL, 1000, 1000, day(8, 1)),
            ReportPayment(2, asha, PaymentKind.RENEWAL, 1000, 1000, day(9, 1)),
        )
        assertEquals("Same as Aug", ReportRules.build(day(9, 1), today, flat, events).change)
    }

    @Test
    fun activeCountsWhoWasActiveAtTheEndOfTheMonth() {
        assertEquals(2, august.active) // Asha, Bala
        assertEquals(3, september.active) // + Chirag
        assertEquals(3, october.active) // - Bala who left, + Dev imported
        assertEquals(3, november.active) // Chirag, Dev, Esha. Asha is frozen.
    }

    @Test
    fun newJoinsAreNewPeopleNotImports() {
        assertEquals(2, august.newJoins)
        assertEquals(1, september.newJoins)
        assertEquals("Dev was imported", 0, october.newJoins)
        assertEquals(1, november.newJoins)
    }

    @Test
    fun leftCountsThatMonthOnly() {
        assertEquals(0, september.left)
        assertEquals(1, october.left)
        assertEquals(0, november.left)
    }

    @Test
    fun renewalsLeaveOutSomeonesFirstPayment() {
        assertEquals("both are joining", 0, august.renewals)
        assertEquals("Asha renewed, Chirag joined", 1, september.renewals)
        assertEquals("Bala renewed", 1, october.renewals)
        // Dev renewed after his opening balance. Esha joined. Chirag's is a balance payment.
        assertEquals(1, november.renewals)
    }

    @Test
    fun pendingIsWhatWasOwedAtTheEndOfTheMonth() {
        assertEquals(0, august.pending)
        assertEquals(1000, september.pending) // Chirag
        assertEquals("Chirag 1000 and Dev 500. Bala's 300 went when he left.", 1500, october.pending)
        // Dev: 500 + 1200 - 1200 = 500. Esha is 300 ahead, which doesn't cancel Dev's debt.
        assertEquals(500, november.pending)
    }

    @Test
    fun theAverageIsCollectedPerActiveMember() {
        assertEquals(1200, august.averagePerMember) // 2400 / 2
        assertEquals(1067, september.averagePerMember) // 3200 / 3 = 1066.67
        assertEquals(300, october.averagePerMember) // 900 / 3
        assertEquals(1233, november.averagePerMember) // 3700 / 3 = 1233.33
    }

    @Test
    fun theAverageWithNobodyActiveIsZero() {
        assertEquals(0, ReportRules.build(day(1, 1), today, payments, events).averagePerMember)
    }

    @Test
    fun theChartsCoverTheSixMonthsEndingHere() {
        assertEquals(listOf("Jun", "Jul", "Aug", "Sep", "Oct", "Nov"), november.collections.map { it.label })
        assertEquals(listOf<Long>(0, 0, 2400, 3200, 900, 3700), november.collections.map { it.value })
        assertEquals(listOf<Long>(0, 0, 2, 3, 3, 3), november.activeMembers.map { it.value })
    }

    @Test
    fun theChartsCrossTheYear() {
        val report = ReportRules.build(Dates.of(2027, 2, 1), Dates.of(2027, 2, 10), payments, events)
        assertEquals(listOf("Sep", "Oct", "Nov", "Dec", "Jan", "Feb"), report.collections.map { it.label })
    }

    @Test
    fun theCurrentMonthStopsAtToday() {
        val early = ReportRules.build(day(11, 1), day(11, 2), payments, events)
        assertEquals("Asha, Chirag and Dev. Asha hasn't frozen yet, and Esha hasn't joined.", 3, early.active)
        assertEquals("Chirag hasn't paid off his 1000 yet", 1500, early.pending)
    }

    @Test
    fun aMonthsDatesAreRight() {
        assertEquals(day(2, 1), ReportRules.firstOfMonth(day(2, 17)))
        assertEquals(day(2, 28), ReportRules.lastOfMonth(day(2, 1)))
        assertEquals(Dates.of(2028, 2, 29), ReportRules.lastOfMonth(Dates.of(2028, 2, 3)))
        assertEquals(day(12, 31), ReportRules.lastOfMonth(day(12, 1)))
        assertEquals(Dates.of(2027, 1, 1), ReportRules.addMonths(day(12, 1), 1))
        assertEquals("November 2026", november.title)
    }

    @Test
    fun theSwitcherGoesBackToTheFirstMonthWithData() {
        assertEquals(day(8, 1), ReportRules.earliestMonth(payments, events, today))
        assertEquals(day(11, 1), ReportRules.earliestMonth(emptyList(), emptyList(), today))
    }

    @Test
    fun someoneWhoRejoinedIsBackAndOneWhoWasDeletedAndRestoredIsAsTheyWere() {
        val history = listOf(
            ReportEvent(asha, MemberEventType.JOINED, day(1, 1)),
            ReportEvent(asha, MemberEventType.LEFT, day(2, 1)),
            ReportEvent(asha, MemberEventType.REJOINED, day(3, 1)),
            ReportEvent(bala, MemberEventType.JOINED, day(1, 1)),
            ReportEvent(bala, MemberEventType.LEFT, day(2, 1)),
            ReportEvent(bala, MemberEventType.DELETED, day(3, 1)),
            ReportEvent(bala, MemberEventType.RESTORED, day(3, 2)),
            ReportEvent(chirag, MemberEventType.JOINED, day(1, 1)),
            ReportEvent(chirag, MemberEventType.FROZEN, day(2, 1)),
            ReportEvent(chirag, MemberEventType.UNFROZEN, day(3, 1)),
        )
        val march = ReportRules.build(day(3, 1), day(3, 31), emptyList(), history)
        assertEquals("Asha and Chirag. Bala is still Left.", 2, march.active)
    }

    @Test
    fun aMemberWithNoEventsDoesntCountTowardsPendingAsLeft() {
        val orphan = listOf(ReportPayment(1, 99, PaymentKind.RENEWAL, 1000, 0, day(11, 1)))
        assertEquals(1000, ReportRules.build(day(11, 1), today, orphan, emptyList()).pending)
    }

    // ---- The widget ----

    private fun member(name: String, state: MemberState = MemberState.ACTIVE, expiry: Long = today, balance: Long = 0) =
        WidgetRules.Member(name, state, expiry, balance)

    private val gym = listOf(
        member("Asha Rao", expiry = today - 2, balance = 700),
        member("Bala Iyer", expiry = today - 30),
        member("Chirag Shah", expiry = today - 10),
        member("Dev Patel", expiry = today - 1),
        member("Esha Nair", expiry = today),
        member("Farah Khan", expiry = today + 2),
        member("Gita Roy", expiry = today + 60, balance = 300),
        member("Hari Lal", MemberState.LEFT, today - 100, 900),
        member("Isha Sen", MemberState.FROZEN, today - 50),
    )

    @Test
    fun theWidgetSaysHowManyAreDueAndOverdue() {
        val summary = WidgetRules.summarize(gym, today, pinSet = false)
        assertEquals(2, summary.due)
        assertEquals(4, summary.overdue)
        assertEquals("2 due · 4 overdue", summary.headline)
    }

    @Test
    fun theWidgetNamesTheLongestOverdueFirst() {
        assertEquals(listOf("Bala", "Chirag", "Asha"), WidgetRules.summarize(gym, today, pinSet = false).overdueNames)
    }

    @Test
    fun theWidgetLeavesOutWhatLeftMembersOwe() {
        assertEquals(1000L, WidgetRules.summarize(gym, today, pinSet = false).pending)
    }

    @Test
    fun withAPinTheWidgetOnlyShowsCounts() {
        val summary = WidgetRules.summarize(gym, today, pinSet = true)
        assertEquals("2 due · 4 overdue", summary.headline)
        assertNull(summary.pending)
        assertTrue(summary.overdueNames.isEmpty())
    }

    @Test
    fun anEmptyGymIsFine() {
        assertEquals("0 due · 0 overdue", WidgetRules.summarize(emptyList(), today, pinSet = false).headline)
    }
}
