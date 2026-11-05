package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test
    fun groupsDigitsTheIndianWay() {
        assertEquals("₹0", Money.format(0))
        assertEquals("₹999", Money.format(999))
        assertEquals("₹1,200", Money.format(1200))
        assertEquals("₹12,000", Money.format(12000))
        assertEquals("₹1,25,000", Money.format(125000))
        assertEquals("₹12,50,000", Money.format(1250000))
        assertEquals("₹1,00,00,000", Money.format(10000000))
    }

    @Test
    fun showsNegativeAmountsWithASign() {
        assertEquals("-₹200", Money.format(-200))
    }

    @Test
    fun readsMoneyAsRupeesForTalkBack() {
        assertEquals("1200 rupees", Money.spoken(1200))
        assertEquals("1 rupee", Money.spoken(1))
        assertEquals("minus 200 rupees", Money.spoken(-200))
    }
}
