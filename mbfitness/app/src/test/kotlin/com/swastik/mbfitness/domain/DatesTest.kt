package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class DatesTest {
    private fun d(year: Int, month: Int, day: Int) = Dates.of(year, month, day)

    @Test
    fun addMonthsClampsToTheEndOfTheMonth() {
        assertEquals(d(2027, 2, 28), Dates.addMonths(d(2027, 1, 31), 1))
        assertEquals(d(2028, 2, 29), Dates.addMonths(d(2028, 1, 31), 1))
        assertEquals(d(2027, 2, 28), Dates.addMonths(d(2026, 11, 30), 3))
        assertEquals(d(2028, 2, 29), Dates.addMonths(d(2027, 11, 30), 3))
        assertEquals(d(2027, 4, 30), Dates.addMonths(d(2027, 3, 31), 1))
    }

    @Test
    fun addMonthsCrossesTheYearBoundary() {
        assertEquals(d(2027, 1, 31), Dates.addMonths(d(2026, 12, 31), 1))
        assertEquals(d(2027, 2, 15), Dates.addMonths(d(2026, 12, 15), 2))
        assertEquals(d(2028, 12, 15), Dates.addMonths(d(2026, 12, 15), 24))
    }

    @Test
    fun addMonthsHandlesLeapDays() {
        assertEquals(d(2028, 3, 29), Dates.addMonths(d(2028, 2, 29), 1))
        assertEquals(d(2029, 2, 28), Dates.addMonths(d(2028, 2, 29), 12))
        assertEquals(d(2032, 2, 29), Dates.addMonths(d(2028, 2, 29), 48))
    }

    @Test
    fun renewalsSpanningALeapYearKeepTheirDay() {
        var expiry = d(2027, 12, 15)
        repeat(3) { expiry = Dates.addMonths(expiry, 1) }
        assertEquals(d(2028, 3, 15), expiry)
    }

    @Test
    fun formatsDatesInEnglishWhateverThePhoneLocale() {
        assertEquals("12 Dec", Dates.formatShort(d(2026, 12, 12)))
        assertEquals("12 Dec 2026", Dates.formatFull(d(2026, 12, 12)))
        assertEquals("Wed, 4 Nov", Dates.formatHeader(d(2026, 11, 4)))
        assertEquals(DayOfWeek.WEDNESDAY, Dates.dayOfWeek(d(2026, 11, 4)))
        assertEquals(2026, Dates.year(d(2026, 11, 4)))
    }

    @Test
    fun convertsBetweenTheClockAndGymDays() {
        val morning = Dates.millisAt(d(2026, 11, 4), 10, 5)
        assertEquals("10:05 AM", Dates.formatTime(morning))
        assertEquals(d(2026, 11, 4), Dates.dayOf(morning))
        assertEquals(d(2026, 11, 4), Dates.dayOf(Dates.millisAt(d(2026, 11, 4), 0)))
        assertEquals(d(2026, 11, 4), Dates.dayOf(Dates.millisAt(d(2026, 11, 4), 23, 59)))
    }

    @Test
    fun theDayIsDecidedInIndiaNotUtc() {
        // 19:00 UTC on the 3rd is already 00:30 on the 4th in India.
        val millis = LocalDate.of(2026, 11, 3).atTime(19, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertEquals(d(2026, 11, 4), Dates.dayOf(millis))
    }

    @Test
    fun systemProviderReadsTheRealClock() {
        val before = System.currentTimeMillis()
        val now = SystemTodayProvider.nowMillis()
        assertTrue(now >= before)
        assertEquals(Dates.dayOf(now), SystemTodayProvider.today())
    }

    @Test
    fun readsImportDatesStrictly() {
        assertEquals(d(2026, 12, 31), Dates.parseCsvDate("31-12-2026"))
        assertEquals(d(2026, 2, 1), Dates.parseCsvDate("1-2-2026"))
        assertEquals(d(2026, 2, 1), Dates.parseCsvDate(" 01/02/2026 "))
        assertEquals(d(2026, 2, 1), Dates.parseCsvDate("01.02.2026"))
        assertEquals(d(2028, 2, 29), Dates.parseCsvDate("29-02-2028"))
        assertNull(Dates.parseCsvDate("29-02-2027"))
        assertNull(Dates.parseCsvDate("31-04-2026"))
        assertNull(Dates.parseCsvDate("2026-12-31"))
        assertNull(Dates.parseCsvDate("31-12-26"))
        assertNull(Dates.parseCsvDate("soon"))
        assertNull(Dates.parseCsvDate(""))
    }

    @Test
    fun birthdaysFallOnTheSameDayEachYear() {
        val dob = d(1995, 8, 15)
        assertTrue(Dates.isBirthday(dob, d(2026, 8, 15)))
        assertFalse(Dates.isBirthday(dob, d(2026, 8, 14)))
        assertFalse(Dates.isBirthday(dob, d(2026, 9, 15)))
    }

    @Test
    fun leapDayBirthdaysMoveTo28FebInOrdinaryYears() {
        val dob = d(2000, 2, 29)
        assertTrue(Dates.isBirthday(dob, d(2027, 2, 28)))
        assertFalse(Dates.isBirthday(dob, d(2027, 3, 1)))
        assertTrue(Dates.isBirthday(dob, d(2028, 2, 29)))
        assertFalse(Dates.isBirthday(dob, d(2028, 2, 28)))
    }

    @Test
    fun theDayOfBirthItselfIsNotABirthday() {
        val dob = d(2026, 8, 15)
        assertFalse(Dates.isBirthday(dob, dob))
    }
}
