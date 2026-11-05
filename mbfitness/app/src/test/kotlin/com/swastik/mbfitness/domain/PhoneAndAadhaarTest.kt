package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAndAadhaarTest {
    @Test
    fun normalizesEveryCommonWayOfWritingANumber() {
        val expected = "+919876543210"
        listOf(
            "9876543210", "98765 43210", "98765-43210", "(98765) 43210", "09876543210",
            "919876543210", "+919876543210", "+91 98765 43210", "+91-98765-43210", "00919876543210",
        ).forEach { assertEquals(it, expected, PhoneNumbers.normalize(it)) }
    }

    @Test
    fun aRealNumberStartingWith91KeepsItsDigits() {
        assertEquals("+919198765432", PhoneNumbers.normalize("9198765432"))
    }

    @Test
    fun rejectsAnythingElse() {
        listOf("", "   ", "12345", "5876543210", "1234567890", "98765432101", "98765abc10", "919876", "+1 9876543210")
            .forEach { assertNull(it, PhoneNumbers.normalize(it)) }
    }

    @Test
    fun displaysAndSearchesWithoutTheCountryCode() {
        assertEquals("98765 43210", PhoneNumbers.display("+919876543210"))
        assertEquals("oddly-stored", PhoneNumbers.display("oddly-stored"))
        assertTrue(PhoneNumbers.matchesDigits("+919876543210", "6543"))
        assertTrue(PhoneNumbers.matchesDigits("+919876543210", "98765 43"))
        assertFalse(PhoneNumbers.matchesDigits("+919876543210", "91987"))
        assertFalse(PhoneNumbers.matchesDigits("+919876543210", "Rahul"))
    }

    /** Made up: the 9999 prefix is the range UIDAI uses for test numbers. Never a real person's. */
    private fun fake(first11: String = "99990000001") = first11 + Aadhaar.checkDigitFor(first11)

    private fun valid(raw: String) = (Aadhaar.parse(raw) as Aadhaar.ParseResult.Valid).aadhaar

    @Test
    fun verhoeffMatchesPublishedExamples() {
        assertEquals('3', Aadhaar.checkDigitFor("236"))
        assertEquals('1', Aadhaar.checkDigitFor("12345"))
        assertEquals('0', Aadhaar.checkDigitFor("123456789012"))
        assertTrue(Aadhaar.checksumIsValid("2363"))
        assertTrue(Aadhaar.checksumIsValid("123451"))
        assertFalse(Aadhaar.checksumIsValid("2364"))
    }

    @Test
    fun acceptsAValidNumberWithOrWithoutSeparators() {
        val number = fake()
        assertEquals(number, valid(number).revealDigits())
        assertEquals(number, valid(number.chunked(4).joinToString(" ")).revealDigits())
        assertEquals(number, valid(number.chunked(4).joinToString("-")).revealDigits())
        assertEquals(number, valid("  $number ").revealDigits())
    }

    @Test
    fun everySingleDigitTypoIsCaught() {
        val number = fake()
        for (index in number.indices) {
            for (digit in '0'..'9') {
                if (digit == number[index]) continue
                val typo = number.replaceRange(index, index + 1, digit.toString())
                assertEquals(typo, Aadhaar.ParseResult.Invalid, Aadhaar.parse(typo))
            }
        }
    }

    @Test
    fun everyAdjacentSwapIsCaught() {
        val number = fake("99991234567")
        for (index in 0 until number.length - 1) {
            if (number[index] == number[index + 1]) continue
            val swapped = number.substring(0, index) + number[index + 1] + number[index] + number.substring(index + 2)
            assertEquals(swapped, Aadhaar.ParseResult.Invalid, Aadhaar.parse(swapped))
        }
    }

    @Test
    fun aNumberStartingWith0Or1IsRejectedEvenWithAGoodChecksum() {
        assertEquals(Aadhaar.ParseResult.Invalid, Aadhaar.parse(fake("01234567890")))
        assertEquals(Aadhaar.ParseResult.Invalid, Aadhaar.parse(fake("11234567890")))
    }

    @Test
    fun rejectsTheWrongShape() {
        listOf("", "999900000", fake() + "1", "9999 0000 00ab", "abcdefghijkl").forEach {
            assertEquals(it, Aadhaar.ParseResult.Invalid, Aadhaar.parse(it))
        }
    }

    @Test
    fun recognisesANumberExcelHasDamaged() {
        listOf("1.23457E+11", "1.23457e+11", "9.99E11", "1E+11").forEach {
            assertEquals(it, Aadhaar.ParseResult.DamagedByExcel, Aadhaar.parse(it))
        }
    }

    @Test
    fun printingNeverRevealsTheNumber() {
        val number = fake()
        val aadhaar = valid(number)
        val last4 = number.takeLast(4)
        assertEquals("XXXX XXXX $last4", aadhaar.toString())
        assertEquals("XXXX XXXX $last4", "$aadhaar")
        assertFalse(number in ImportRow(1, "A", "+919876543210", 1, 0, 0, null, "", aadhaar).toString())
    }

    @Test
    fun revealsOnlyWhenAskedByName() {
        val number = fake()
        val aadhaar = valid(number)
        assertEquals(number.takeLast(4), aadhaar.last4)
        assertEquals(number.chunked(4).joinToString(" "), aadhaar.revealGrouped())
        assertEquals("XXXX XXXX 1234", Aadhaar.masked("1234"))
    }
}
