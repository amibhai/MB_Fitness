package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsSegmentsTest {
    private fun parts(text: String) = SmsSegments.count(text).parts
    private fun a(count: Int) = "a".repeat(count)

    @Test
    fun gsm7Boundaries() {
        assertEquals(0, parts(""))
        assertEquals(1, parts(a(1)))
        assertEquals(1, parts(a(160)))
        assertEquals(2, parts(a(161)))
        assertEquals(2, parts(a(306)))
        assertEquals(3, parts(a(307)))
        assertEquals(3, parts(a(459)))
        assertEquals(4, parts(a(460)))
        assertEquals(SmsEncoding.GSM7, SmsSegments.count(a(10)).encoding)
        assertNull(SmsSegments.count(a(10)).firstUnicode)
    }

    @Test
    fun extendedCharactersCostTwoUnits() {
        assertEquals(1, parts("{".repeat(80)))
        assertEquals(162, SmsSegments.count("{".repeat(81)).units)
        assertEquals(2, parts("{".repeat(81)))
        listOf("^", "}", "\u005C", "[", "~", "]", "|", "€").forEach {
            assertEquals(it, 2, SmsSegments.count(it).units)
            assertEquals(it, SmsEncoding.GSM7, SmsSegments.count(it).encoding)
        }
    }

    @Test
    fun anExtendedCharacterIsNeverSplitAcrossParts() {
        // 305 units would fit in 2 parts by simple division, but the { can't straddle them.
        assertEquals(2, parts(a(152) + "{" + a(151)))
        assertEquals(3, parts(a(152) + "{" + a(152)))
    }

    @Test
    fun theRupeeSignForcesUnicode() {
        val count = SmsSegments.count("Fee: ₹1200")
        assertEquals(SmsEncoding.UNICODE, count.encoding)
        assertEquals("₹", count.firstUnicode)
    }

    @Test
    fun unicodeBoundaries() {
        assertEquals(1, parts("₹" + a(69)))
        assertEquals(2, parts("₹" + a(70)))
        assertEquals(2, parts("₹" + a(133)))
        assertEquals(3, parts("₹" + a(134)))
    }

    @Test
    fun devanagariCountsAsUnicode() {
        val count = SmsSegments.count("नमस्ते")
        assertEquals(SmsEncoding.UNICODE, count.encoding)
        assertEquals("न", count.firstUnicode)
        assertEquals(1, count.parts)
        assertEquals(2, parts("न".repeat(71)))
    }

    @Test
    fun anEmojiIsNeverSplitAcrossParts() {
        val emoji = "🎂"
        assertEquals(emoji, SmsSegments.count(emoji).firstUnicode)
        assertEquals(2, SmsSegments.count(emoji).units)
        // 66 units then an emoji: it moves whole to part 2, and 65 more fill that part.
        assertEquals(2, parts(a(66) + emoji + a(65)))
        assertEquals(3, parts(a(66) + emoji + a(66)))
    }

    @Test
    fun describesTheCountInPlainWords() {
        assertEquals("1 part · 5 of 160", SmsSegments.describe(SmsSegments.count(a(5))))
        assertEquals("2 parts · 200 of 306", SmsSegments.describe(SmsSegments.count(a(200))))
        assertEquals("1 part · Unicode (₹ used)", SmsSegments.describe(SmsSegments.count("₹1")))
    }
}
