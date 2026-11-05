package com.swastik.mbfitness.domain

import com.swastik.mbfitness.domain.Pacing.Decision
import com.swastik.mbfitness.domain.Pacing.Limits
import com.swastik.mbfitness.domain.Pacing.NextStep
import com.swastik.mbfitness.domain.Pacing.Queued
import com.swastik.mbfitness.domain.Pacing.Sent
import com.swastik.mbfitness.domain.Pacing.WaitReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingTest {
    private val day = Dates.of(2026, 11, 4)
    private val limits = Limits()
    private fun at(hour: Int, minute: Int = 0) = Dates.millisAt(day, hour, minute)
    private val now = at(10)

    private fun decide(sent: List<Sent>, parts: Int = 1, planned: Boolean = false, clock: Long = now) =
        Pacing.decide(clock, parts, planned, sent, limits)

    private fun queued(id: Long, priority: Int = 3, parts: Int = 1, notBefore: Long = 0, createdAt: Long = 0, planned: Boolean = false) =
        Queued(id, parts, priority, createdAt, notBefore, planned)

    @Test
    fun theLimitsStayUnderAndroidsThirtyInThirtyMinutes() {
        assertTrue(limits.windowCapParts < 30)
        assertEquals(30L * 60 * 1000, Pacing.WINDOW_MILLIS)
        assertEquals(25, limits.windowCapParts)
        assertEquals(90, limits.dailyCapParts)
    }

    @Test
    fun sendsUntilTheWindowIsFull() {
        assertEquals(Decision.SendNow, decide(emptyList()))
        assertEquals(Decision.SendNow, decide(List(24) { Sent(now - 1000, 1) }))
    }

    @Test
    fun aFullWindowWaitsForTheOldestToAgeOut() {
        val sent = List(25) { Sent(now - 60_000 + it, 1) }
        assertEquals(Decision.WaitUntil(sent[0].atMillis + Pacing.WINDOW_MILLIS, WaitReason.WINDOW), decide(sent))
    }

    @Test
    fun aLargerMessageWaitsForEnoughRoom() {
        // 24 used and 3 needed: two of the oldest must age out.
        val sent = List(24) { Sent(now - 60_000 + it, 1) }
        assertEquals(Decision.WaitUntil(sent[1].atMillis + Pacing.WINDOW_MILLIS, WaitReason.WINDOW), decide(sent, parts = 3))
    }

    @Test
    fun partsOlderThanTheWindowDoNotCount() {
        val old = List(25) { Sent(now - Pacing.WINDOW_MILLIS, 1) }
        assertEquals(Decision.SendNow, decide(old))
    }

    @Test
    fun theDailyCapHoldsMessagesUntilTomorrowMorning() {
        val earlier = List(90) { Sent(at(8, 30), 1) }
        val tomorrowMorning = Dates.millisAt(day + 1, 9)
        assertEquals(Decision.WaitUntil(tomorrowMorning, WaitReason.DAILY_CAP), decide(earlier))
    }

    @Test
    fun yesterdaysPartsDoNotCountTowardsTheDailyCap() {
        val yesterday = List(90) { Sent(Dates.millisAt(day - 1, 15), 1) }
        assertEquals(Decision.SendNow, decide(yesterday))
    }

    @Test
    fun theMorningTimeFollowsTheSetting() {
        val earlier = List(90) { Sent(at(8, 30), 1) }
        val custom = Limits(morningHour = 7, morningMinute = 45)
        assertEquals(
            Decision.WaitUntil(Dates.millisAt(day + 1, 7, 45), WaitReason.DAILY_CAP),
            Pacing.decide(now, 1, false, earlier, custom),
        )
    }

    @Test
    fun aMessageBiggerThanACapCanStillGoWhenNothingElseIsUsingIt() {
        assertEquals(Decision.SendNow, Pacing.decide(now, 30, false, emptyList(), limits))
        val busy = listOf(Sent(now - 1000, 1))
        assertEquals(Decision.WaitUntil(busy[0].atMillis + Pacing.WINDOW_MILLIS, WaitReason.WINDOW), Pacing.decide(now, 30, false, busy, limits))
    }

    @Test
    fun plannedRemindersWaitOutTheNight() {
        assertEquals(Decision.WaitUntil(at(8), WaitReason.NIGHT), decide(emptyList(), planned = true, clock = at(7, 59)))
        assertEquals(Decision.SendNow, decide(emptyList(), planned = true, clock = at(8)))
        assertEquals(Decision.SendNow, decide(emptyList(), planned = true, clock = at(20, 59)))
        assertEquals(
            Decision.WaitUntil(Dates.millisAt(day + 1, 8), WaitReason.NIGHT),
            decide(emptyList(), planned = true, clock = at(21)),
        )
    }

    @Test
    fun bhaiyasOwnMessagesGoAtNight() {
        assertEquals(Decision.SendNow, decide(emptyList(), planned = false, clock = at(23)))
    }

    @Test
    fun anEmptyQueueIsIdle() {
        assertEquals(NextStep.Idle, Pacing.next(emptyList(), now, emptyList(), limits))
    }

    @Test
    fun theQueueGoesInPriorityOrderThenOldestFirst() {
        val queue = listOf(
            queued(1, priority = 5, createdAt = 1),
            queued(2, priority = 2, createdAt = 9),
            queued(3, priority = 2, createdAt = 3),
            queued(4, priority = 1, createdAt = 9),
        )
        val order = mutableListOf<Long>()
        val remaining = queue.toMutableList()
        while (remaining.isNotEmpty()) {
            val next = Pacing.next(remaining, now, emptyList(), limits) as NextStep.Send
            order += next.message.id
            remaining -= next.message
        }
        assertEquals(listOf(4L, 3L, 2L, 1L), order)
    }

    @Test
    fun yesterdaysLeftoversGoAheadOfNewRemindersAtTheSamePriority() {
        val leftover = queued(9, priority = 2, createdAt = now - 86_400_000)
        val fresh = queued(1, priority = 2, createdAt = now)
        assertEquals(NextStep.Send(leftover), Pacing.next(listOf(fresh, leftover), now, emptyList(), limits))
    }

    @Test
    fun messagesNotYetDueWakeTheWorkerLater() {
        val soon = queued(1, notBefore = now + 5000)
        val later = queued(2, notBefore = now + 9000)
        assertEquals(NextStep.WakeAt(now + 5000), Pacing.next(listOf(later, soon), now, emptyList(), limits))
    }

    @Test
    fun aFullWindowHoldsEverythingSoPriorityStaysStrict() {
        val sent = List(25) { Sent(now - 1000, 1) }
        val queue = listOf(queued(1, priority = 1), queued(2, priority = 5))
        assertEquals(NextStep.WakeAt(now - 1000 + Pacing.WINDOW_MILLIS), Pacing.next(queue, now, sent, limits))
    }

    @Test
    fun aReminderHeldOvernightDoesNotHoldBackBhaiyasBroadcast() {
        val night = at(22)
        val reminder = queued(1, priority = 2, planned = true)
        val broadcast = queued(2, priority = 5, planned = false)
        assertEquals(NextStep.Send(broadcast), Pacing.next(listOf(reminder, broadcast), night, emptyList(), limits))
        assertEquals(NextStep.WakeAt(Dates.millisAt(day + 1, 8)), Pacing.next(listOf(reminder), night, emptyList(), limits))
    }

    @Test
    fun aBroadcastIsDescribedInWordsLikeTheSpecSays() {
        val start = at(9, 35)
        val batches = Pacing.planBroadcast(50, 1, start, emptyList(), limits)
        assertEquals(listOf(Pacing.Batch(25, start), Pacing.Batch(25, at(10, 5))), batches)
        assertEquals("50 members × 1 part. 25 go now, 25 more at 10:05 AM.", Pacing.describeBroadcast(50, 1, batches, start))
    }

    @Test
    fun aBroadcastCountsPartsNotPeople() {
        val batches = Pacing.planBroadcast(20, 2, now, emptyList(), limits)
        assertEquals(listOf(12, 8), batches.map { it.count })
    }

    @Test
    fun aBroadcastAccountsForWhatWasAlreadySent() {
        val sent = List(20) { Sent(now - 60_000, 1) }
        val batches = Pacing.planBroadcast(10, 1, now, sent, limits)
        assertEquals(5, batches[0].count)
        assertEquals(now - 60_000 + Pacing.WINDOW_MILLIS, batches[1].atMillis)
    }

    @Test
    fun aHugeBroadcastCarriesOverTheDailyCapToTomorrow() {
        val batches = Pacing.planBroadcast(100, 1, now, emptyList(), limits)
        assertEquals(listOf(25, 25, 25, 15, 10), batches.map { it.count })
        val text = Pacing.describeBroadcast(100, 1, batches, now)
        assertEquals("100 members × 1 part. 25 go now, 25 more at 10:30 AM, 25 more at 11:00 AM, 15 more at 11:30 AM, 10 more tomorrow at 9:00 AM.", text)
    }

    @Test
    fun aBroadcastThatCantStartTodaySaysSo() {
        val full = List(90) { Sent(at(8, 30), 1) }
        val batches = Pacing.planBroadcast(1, 2, now, full, limits)
        assertEquals("1 member × 2 parts. 1 tomorrow at 9:00 AM.", Pacing.describeBroadcast(1, 2, batches, now))
    }

    @Test
    fun aBroadcastBeyondTomorrowNamesTheDate() {
        val batches = listOf(Pacing.Batch(5, now), Pacing.Batch(5, Dates.millisAt(day + 2, 9)))
        assertEquals("10 members × 1 part. 5 go now, 5 more on 6 Nov at 9:00 AM.", Pacing.describeBroadcast(10, 1, batches, now))
    }
}
