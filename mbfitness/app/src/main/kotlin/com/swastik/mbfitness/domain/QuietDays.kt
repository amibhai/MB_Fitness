package com.swastik.mbfitness.domain

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** A dated holiday. Festival dates are never hardcoded; bhaiya adds them (SPEC 8.3). */
data class QuietDate(val date: Long, val label: String)

class QuietCalendar(
    private val weekdays: Set<DayOfWeek> = setOf(DayOfWeek.SUNDAY),
    dates: List<QuietDate> = emptyList(),
) {
    private val byDate = dates.associateBy { it.date }

    fun isQuiet(day: Long): Boolean = day in byDate || Dates.dayOfWeek(day) in weekdays

    /** The name for the notice: "Diwali", or "Sunday" for a weekly day off. */
    fun reason(day: Long): String? = byDate[day]?.label
        ?: Dates.dayOfWeek(day).takeIf { it in weekdays }?.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    /** The next day reminders can go. Null only if every upcoming day is quiet. */
    fun nextNormalDay(after: Long): Long? =
        (after + 1..after + MAX_DAYS_AHEAD).firstOrNull { !isQuiet(it) }

    companion object {
        private const val MAX_DAYS_AHEAD = 366L
        const val PURGE_AFTER_DAYS = 30L

        /** Past holidays are hidden at once and deleted 30 days later (SPEC 8.3). */
        fun isHidden(date: Long, today: Long): Boolean = date < today

        fun shouldPurge(date: Long, today: Long): Boolean = today - date > PURGE_AFTER_DAYS
    }
}
