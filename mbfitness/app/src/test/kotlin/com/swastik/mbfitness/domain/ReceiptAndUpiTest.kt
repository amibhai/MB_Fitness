package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptAndUpiTest {
    @Test
    fun receiptNumbersArePaddedAndCanOutgrowThePadding() {
        assertEquals("MBF-0001", ReceiptNumbers.format(1))
        assertEquals("MBF-0017", ReceiptNumbers.format(17))
        assertEquals("MBF-9999", ReceiptNumbers.format(9999))
        assertEquals("MBF-12345", ReceiptNumbers.format(12345))
    }

    @Test
    fun receiptNumbersParseBack() {
        assertEquals(17L, ReceiptNumbers.parse("MBF-0017"))
        assertEquals(12345L, ReceiptNumbers.parse(" MBF-12345 "))
        assertNull(ReceiptNumbers.parse("0017"))
        assertNull(ReceiptNumbers.parse("MBF-"))
        assertNull(ReceiptNumbers.parse("MBF-0000"))
        assertNull(ReceiptNumbers.parse("MBF-ABC"))
    }

    @Test
    fun upiLinkHasTheExactFormat() {
        assertEquals(
            "upi://pay?pa=gymowner@okbank&pn=Jeetu%20Bhaiya&am=1200.00&cu=INR&tn=MB%20Fitness%20-%20Rahul",
            UpiLink.build("gymowner@okbank", "Jeetu Bhaiya", 1200, "MB Fitness - Rahul"),
        )
    }

    @Test
    fun upiLinkEncodesAnythingThatCouldBreakTheUrl() {
        val link = UpiLink.build(" a.b@bank ", "A&B=C D", 1, "50% off?")
        assertEquals("upi://pay?pa=a.b@bank&pn=A%26B%3DC%20D&am=1.00&cu=INR&tn=50%25%20off%3F", link)
    }

    @Test
    fun upiLinkEncodesNonAsciiNamesAsUtf8() {
        assertEquals("%E0%A4%9C", UpiLink.encode("ज"))
    }

    @Test
    fun theNoteUsesTheFirstName() {
        assertEquals("MB Fitness - Rahul", UpiLink.note("Rahul Kumar Sharma"))
    }

    @Test
    fun checksTheShapeOfAUpiId() {
        assertTrue(UpiLink.isValidVpa("gym.owner-1_x@okbank"))
        assertTrue(UpiLink.isValidVpa(" name@ybl "))
        assertFalse(UpiLink.isValidVpa("name"))
        assertFalse(UpiLink.isValidVpa("@bank"))
        assertFalse(UpiLink.isValidVpa("name@"))
        assertFalse(UpiLink.isValidVpa("na me@bank"))
    }
}
