package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRulesTest {
    private val phone = "+919876543210"

    @Test
    fun searchFindsNamesAndAnyDigitsOfThePhone() {
        assertTrue(ScreenRules.matchesSearch("Rahul Sharma", phone, ""))
        assertTrue(ScreenRules.matchesSearch("Rahul Sharma", phone, "  "))
        assertTrue(ScreenRules.matchesSearch("Rahul Sharma", phone, "rahul"))
        assertTrue(ScreenRules.matchesSearch("Rahul Sharma", phone, "SHAR"))
        assertTrue(ScreenRules.matchesSearch("Rahul Sharma", phone, "6543"))
        assertFalse(ScreenRules.matchesSearch("Rahul Sharma", phone, "Anil"))
        assertFalse(ScreenRules.matchesSearch("Rahul Sharma", phone, "1111"))
    }

    @Test
    fun describesWhatAPaymentLeaves() {
        assertEquals("₹700 will remain due", ScreenRules.describe(AfterPayment.Remaining(700)))
        assertEquals("Fully paid", ScreenRules.describe(AfterPayment.FullyPaid))
        assertEquals("₹200 extra. Check the amount.", ScreenRules.describe(AfterPayment.Extra(200)))
    }

    @Test
    fun countsTheDaysLeftInTheRecycleBin() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(30, ScreenRules.recycleBinDaysLeft(0, 0, 30))
        assertEquals(30, ScreenRules.recycleBinDaysLeft(0, day - 1, 30))
        assertEquals(29, ScreenRules.recycleBinDaysLeft(0, day, 30))
        assertEquals(0, ScreenRules.recycleBinDaysLeft(0, day * 45, 30))
    }

    @Test
    fun amountsAreWholeRupeesWithALimit() {
        assertEquals(1200L, ScreenRules.parseAmount("1200"))
        assertEquals(0L, ScreenRules.parseAmount("0"))
        assertNull(ScreenRules.parseAmount(""))
        assertNull(ScreenRules.parseAmount("12.5"))
        assertNull(ScreenRules.parseAmount("12345678"))
    }

    @Test
    fun amountInputKeepsOnlyWhatMakesSense() {
        assertEquals("1200", ScreenRules.amountInput("1,200"))
        assertEquals("1200", ScreenRules.amountInput("001200"))
        assertEquals("0", ScreenRules.amountInput("000"))
        assertEquals("", ScreenRules.amountInput("abc"))
        assertEquals("1234567", ScreenRules.amountInput("12345678"))
    }

    private val entries = listOf(
        ClosingEntry("Rahul", "MBF-0001", 1200, PaymentMode.CASH),
        ClosingEntry("Anil", "MBF-0002", 3000, PaymentMode.UPI),
        ClosingEntry("Vijay", "MBF-0003", 500, PaymentMode.OTHER),
        ClosingEntry("Sumit", "MBF-0004", 800, PaymentMode.CASH),
    )

    @Test
    fun closingAddsUpByMode() {
        assertEquals(ClosingTotals(2000, 3000, 500), ClosingSummary.totals(entries))
        assertEquals(5500L, ClosingSummary.totals(entries).total)
        assertEquals(0L, ClosingSummary.totals(emptyList()).total)
    }

    @Test
    fun theSharedSummaryIsPlainText() {
        val text = ClosingSummary.shareText("MB Fitness Club", Dates.of(2026, 11, 5), entries.take(1))
        assertEquals(
            "MB Fitness Club: closing for 5 Nov 2026\nCash: ₹1,200\nUPI: ₹0\nOther: ₹0\nTotal: ₹1,200\nMBF-0001  Rahul  ₹1,200  CASH",
            text,
        )
    }
}
