package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The error log is for fixing things, and must never hold an Aadhaar number (hard rule 11). */
class LogTextTest {
    @Test
    fun anAadhaarNumberNeverReachesTheLogInAnyLayout() {
        for (number in listOf("999900000016", "9999 0000 0016", "9999-0000-0016")) {
            val logged = LogText.redact("Could not save $number for Rahul")
            assertFalse(logged, logged.contains("0016"))
            assertEquals("Could not save ${LogText.HIDDEN} for Rahul", logged)
        }
    }

    @Test
    fun aPhoneWithItsCountryCodeIsHiddenToo() {
        assertEquals("To +${LogText.HIDDEN}", LogText.redact("To +919876543210"))
    }

    @Test
    fun otherNumbersStay() {
        val text = "Message 15 at 1762412345678, phone 9876543210, receipt MBF-0017"
        assertEquals(text, LogText.redact(text))
    }

    @Test
    fun aLongEntryIsCut() {
        assertEquals(LogText.MAX_LENGTH, LogText.redact("x".repeat(LogText.MAX_LENGTH + 50)).length)
    }

    @Test
    fun threeCrashesCloseTogetherCountAsCrashingOnEveryStart() {
        val now = 100_000_000L
        val minute = 60_000L
        assertFalse(CrashLoop.keepsCrashing(emptyList(), now))
        assertFalse(CrashLoop.keepsCrashing(listOf(now - minute, now - 2 * minute), now))
        assertTrue(CrashLoop.keepsCrashing(listOf(now - minute, now - 2 * minute, now - 3 * minute), now))
        assertFalse("spread out, they're just bad luck", CrashLoop.keepsCrashing(listOf(now - minute, now - 2 * minute, now - 11 * minute), now))
        assertFalse("a clock moved back can't trigger it", CrashLoop.keepsCrashing(listOf(now + minute, now + 2 * minute, now + 3 * minute), now))
    }

    /** Two hundred full entries would be too big for the clipboard. */
    @Test
    fun copyingKeepsTheNewestAndFitsAMessage() {
        assertEquals("new\nold", LogText.forCopying(listOf("new", "old")))
        val many = List(200) { "entry $it ".padEnd(LogText.MAX_LENGTH, 'x') }
        val copied = LogText.forCopying(many)
        assertTrue(copied.length <= LogText.MAX_COPIED_LENGTH)
        assertTrue("the newest comes first", copied.startsWith("entry 0 "))
        assertEquals("", LogText.forCopying(emptyList()))
    }

    @Test
    fun aFailureIsDescribedWithItsCausesAndWhereItHappened() {
        val error = IllegalStateException("No member 5", IllegalArgumentException("bad id"))
        val text = LogText.describe(error)
        assertTrue(text, text.startsWith("IllegalStateException: No member 5 / caused by IllegalArgumentException: bad id"))
        assertTrue(text, text.contains("\n  at "))
        assertTrue(text, text.contains("LogTextTest"))
    }

    @Test
    fun aDescriptionHidesNumbersToo() {
        val text = LogText.describe(NumberFormatException("For input string: \"999900000016\""))
        assertFalse(text, text.contains("999900000016"))
    }

    @Test
    fun aFailureWithNoStackStillReads() {
        val error = RuntimeException("short").apply { stackTrace = emptyArray() }
        assertEquals("RuntimeException: short", LogText.describe(error))
    }

    @Test
    fun aClassWithNoSimpleNameUsesItsFullName() {
        val anonymous = object : RuntimeException("odd") {}
        assertTrue(LogText.describe(anonymous).contains(": odd"))
    }
}
