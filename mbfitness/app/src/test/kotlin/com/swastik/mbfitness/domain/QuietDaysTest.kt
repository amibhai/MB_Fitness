package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class QuietDaysTest {
    private val sunday = Dates.of(2026, 11, 8)
    private val holiday = QuietDate(Dates.of(2026, 11, 11), "Diwali")

    @Test
    fun weeklyDaysAndHolidaysAreQuiet() {
        val calendar = QuietCalendar(setOf(DayOfWeek.SUNDAY), listOf(holiday))
        assertTrue(calendar.isQuiet(sunday))
        assertTrue(calendar.isQuiet(holiday.date))
        assertFalse(calendar.isQuiet(sunday + 1))
    }

    @Test
    fun sundayIsQuietByDefault() {
        assertTrue(QuietCalendar().isQuiet(sunday))
    }

    @Test
    fun theNoticeNamesTheReasonWithHolidaysFirst() {
        val onSunday = QuietDate(sunday, "Festival")
        val calendar = QuietCalendar(setOf(DayOfWeek.SUNDAY), listOf(onSunday))
        assertEquals("Festival", calendar.reason(sunday))
        assertEquals("Sunday", calendar.reason(sunday + 7))
        assertNull(calendar.reason(sunday + 1))
    }

    @Test
    fun findsTheNextNormalDay() {
        val calendar = QuietCalendar(setOf(DayOfWeek.SUNDAY), listOf(QuietDate(sunday + 1, "Holiday")))
        assertEquals(sunday + 2, calendar.nextNormalDay(sunday))
        assertEquals(sunday + 2, calendar.nextNormalDay(sunday - 1))
        assertEquals(sunday - 1, calendar.nextNormalDay(sunday - 2))
    }

    @Test
    fun everyDayQuietDoesNotHang() {
        assertNull(QuietCalendar(DayOfWeek.entries.toSet()).nextNormalDay(sunday))
    }

    @Test
    fun pastHolidaysAreHiddenAtOnceAndPurgedAfter30Days() {
        val date = holiday.date
        assertFalse(QuietCalendar.isHidden(date, date))
        assertTrue(QuietCalendar.isHidden(date, date + 1))
        assertFalse(QuietCalendar.shouldPurge(date, date + 30))
        assertTrue(QuietCalendar.shouldPurge(date, date + 31))
    }
}
