package com.swastik.mbfitness.domain

/**
 * How fast SMS may go (SPEC 8.4): under Android's limit of 30 in 30 minutes, under the daily
 * cap, and never fast enough to look like a bulk sender. All time maths lives here.
 */
object Pacing {
    const val WINDOW_MILLIS = 30L * 60 * 1000
    const val MIN_SPACING_MILLIS = 3_000L
    const val MAX_SPACING_MILLIS = 5_000L

    /** Planned reminders stay inside these hours, even if a late catch-up run plans them. */
    const val PLANNED_FROM_HOUR = 8
    const val PLANNED_UNTIL_HOUR = 21

    data class Limits(
        val windowCapParts: Int = 25,
        val dailyCapParts: Int = 90,
        val morningHour: Int = 9,
        val morningMinute: Int = 0,
    )

    data class Sent(val atMillis: Long, val parts: Int)

    data class Queued(
        val id: Long,
        val parts: Int,
        val priority: Int,
        val createdAt: Long,
        val notBefore: Long,
        val isPlanned: Boolean,
    )

    enum class WaitReason { WINDOW, DAILY_CAP, NIGHT }

    sealed interface Decision {
        data object SendNow : Decision
        data class WaitUntil(val millis: Long, val reason: WaitReason) : Decision
    }

    sealed interface NextStep {
        data class Send(val message: Queued) : NextStep
        data class WakeAt(val millis: Long) : NextStep
        data object Idle : NextStep
    }

    data class Batch(val count: Int, val atMillis: Long)

    /** Whether one message may go now, and if not, the first moment it could. */
    fun decide(
        nowMillis: Long,
        parts: Int,
        isPlanned: Boolean,
        sent: List<Sent>,
        limits: Limits,
    ): Decision {
        val today = Dates.dayOf(nowMillis)
        if (isPlanned) nightWait(nowMillis)?.let { return it }

        // A message bigger than a whole cap still goes once nothing else is using it,
        // otherwise it would wait forever.
        val usedToday = sent.filter { Dates.dayOf(it.atMillis) == today }.sumOf { it.parts }
        if (usedToday > 0 && usedToday + parts > limits.dailyCapParts) {
            return Decision.WaitUntil(nextMorning(today, limits), WaitReason.DAILY_CAP)
        }

        val inWindow = sent.filter { it.atMillis > nowMillis - WINDOW_MILLIS }.sortedBy { it.atMillis }
        var used = inWindow.sumOf { it.parts }
        if (used == 0 || used + parts <= limits.windowCapParts) return Decision.SendNow
        for (record in inWindow) {
            used -= record.parts
            if (used == 0 || used + parts <= limits.windowCapParts) {
                return Decision.WaitUntil(record.atMillis + WINDOW_MILLIS, WaitReason.WINDOW)
            }
        }
        return Decision.SendNow
    }

    /**
     * Picks what to do next. Limits apply to everything, so a message held by one holds the
     * rest and priority stays strict. A planned message held overnight only holds itself.
     */
    fun next(queue: List<Queued>, nowMillis: Long, sent: List<Sent>, limits: Limits): NextStep {
        if (queue.isEmpty()) return NextStep.Idle
        val (due, later) = queue.partition { it.notBefore <= nowMillis }
        val wakes = later.map { it.notBefore }.toMutableList()
        val ordered = due.sortedWith(compareBy({ it.priority }, { it.createdAt }, { it.id }))
        for (message in ordered) {
            when (val decision = decide(nowMillis, message.parts, message.isPlanned, sent, limits)) {
                Decision.SendNow -> return NextStep.Send(message)
                is Decision.WaitUntil -> {
                    wakes += decision.millis
                    if (decision.reason != WaitReason.NIGHT) break
                }
            }
        }
        return NextStep.WakeAt(wakes.min())
    }

    /** When a broadcast's messages would go, by simulating the queue (SPEC 8.4). */
    fun planBroadcast(
        recipients: Int,
        partsEach: Int,
        nowMillis: Long,
        sent: List<Sent>,
        limits: Limits,
    ): List<Batch> {
        val simulated = sent.toMutableList()
        val batches = mutableListOf<Batch>()
        var clock = nowMillis
        var batchStart = nowMillis
        var batchCount = 0
        repeat(recipients) {
            val decision = decide(clock, partsEach, isPlanned = false, sent = simulated, limits = limits)
            if (decision is Decision.WaitUntil) {
                if (batchCount > 0) batches += Batch(batchCount, batchStart)
                clock = decision.millis
                batchStart = clock
                batchCount = 0
            }
            simulated += Sent(clock, partsEach)
            batchCount++
        }
        if (batchCount > 0) batches += Batch(batchCount, batchStart)
        return batches
    }

    /** "50 members × 1 part. 25 go now, 25 more at 10:05 AM." */
    fun describeBroadcast(recipients: Int, partsEach: Int, batches: List<Batch>, nowMillis: Long): String {
        val who = if (recipients == 1) "1 member" else "$recipients members"
        val parts = if (partsEach == 1) "1 part" else "$partsEach parts"
        val today = Dates.dayOf(nowMillis)
        val plan = batches.mapIndexed { index, batch ->
            val day = Dates.dayOf(batch.atMillis)
            val time = Dates.formatTime(batch.atMillis)
            val more = if (index == 0) "" else "more "
            when {
                batch.atMillis <= nowMillis -> "${batch.count} go now"
                day == today -> "${batch.count} ${more}at $time"
                day == today + 1 -> "${batch.count} ${more}tomorrow at $time"
                else -> "${batch.count} ${more}on ${Dates.formatShort(day)} at $time"
            }
        }
        return "$who × $parts. ${plan.joinToString(", ")}."
    }

    private fun nightWait(nowMillis: Long): Decision.WaitUntil? {
        val today = Dates.dayOf(nowMillis)
        val opens = Dates.millisAt(today, PLANNED_FROM_HOUR)
        val closes = Dates.millisAt(today, PLANNED_UNTIL_HOUR)
        return when {
            nowMillis < opens -> Decision.WaitUntil(opens, WaitReason.NIGHT)
            nowMillis >= closes -> Decision.WaitUntil(Dates.millisAt(today + 1, PLANNED_FROM_HOUR), WaitReason.NIGHT)
            else -> null
        }
    }

    private fun nextMorning(today: Long, limits: Limits): Long =
        Dates.millisAt(today + 1, limits.morningHour, limits.morningMinute)
}
