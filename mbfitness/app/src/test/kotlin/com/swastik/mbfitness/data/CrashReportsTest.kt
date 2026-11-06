package com.swastik.mbfitness.data

import com.swastik.mbfitness.domain.LogText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashReportsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun aCrashWaitsForTheNextStartThenGoesOnce() {
        val reports = CrashReports(folder.root)
        reports.write(1_000, "Crash (main)", "IllegalStateException: boom\n  at somewhere")
        val report = reports.take()
        assertEquals(CrashReports.Report(1_000, "Crash (main)", "IllegalStateException: boom\n  at somewhere"), report)
        assertNull(reports.take())
    }

    @Test
    fun theFileNeverHoldsAnAadhaarNumber() {
        val reports = CrashReports(folder.root)
        reports.write(1, "Crash", "bad input 999900000016")
        assertFalse(folder.root.listFiles()!!.single().readText().contains("999900000016"))
        assertEquals("bad input ${LogText.HIDDEN}", reports.take()!!.details)
    }

    @Test
    fun aLookLeavesTheReportWaiting() {
        val reports = CrashReports(folder.root)
        reports.write(5, "Crash", "details")
        assertEquals("details", reports.peek()!!.details)
        assertEquals("details", reports.take()!!.details)
        assertNull(reports.peek())
    }

    @Test
    fun crashTimesAreKeptUntilForgotten() {
        val reports = CrashReports(folder.root)
        assertEquals(emptyList<Long>(), reports.crashTimes())
        repeat(15) { reports.noteCrash(it.toLong()) }
        assertEquals("only the newest 10", (5L..14L).toList(), reports.crashTimes())
        reports.forgetCrashes()
        assertEquals(emptyList<Long>(), reports.crashTimes())
    }

    @Test
    fun nothingWaitingIsNull() {
        assertNull(CrashReports(folder.root).take())
    }

    @Test
    fun aDamagedFileStillGivesWhatItCan() {
        folder.root.resolve("last_crash.txt").writeText("not a time")
        assertEquals(CrashReports.Report(0, "", ""), CrashReports(folder.root).take())
    }
}
