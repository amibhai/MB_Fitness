package com.swastik.mbfitness.domain

enum class SmsEncoding { GSM7, UNICODE }

/**
 * How many SMS parts a text takes. [firstUnicode] is the first character that forced Unicode,
 * so the editor can say which one to remove.
 */
data class SmsCount(
    val parts: Int,
    val encoding: SmsEncoding,
    val units: Int,
    val firstUnicode: String?,
)

/** GSM-7 vs Unicode part counting (SPEC 8.7). Pacing and the part counter both rely on this. */
object SmsSegments {
    const val GSM_SINGLE = 160
    const val GSM_PER_PART = 153
    const val UNICODE_SINGLE = 70
    const val UNICODE_PER_PART = 67

    /** The editor warns above this many parts and refuses to save above [BLOCK_ABOVE_PARTS]. */
    const val WARN_ABOVE_PARTS = 2
    const val BLOCK_ABOVE_PARTS = 4

    private const val GSM_BASIC =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    /** These fit in GSM-7 but cost 2 units each, and the pair can't be split across parts. */
    private const val GSM_EXTENDED = "^{}\u005C[~]|€\u000C"

    private val basic = GSM_BASIC.toSet()
    private val extended = GSM_EXTENDED.toSet()

    fun count(text: String): SmsCount {
        val firstUnicode = firstNonGsm(text)
        return if (firstUnicode == null) countGsm(text) else countUnicode(text, firstUnicode)
    }

    /** "2 parts · Unicode (₹ used)" or "1 part · 42 of 160" (SPEC 8.7). */
    fun describe(count: SmsCount): String {
        val parts = if (count.parts == 1) "1 part" else "${count.parts} parts"
        return when (count.encoding) {
            SmsEncoding.UNICODE -> "$parts · Unicode (${count.firstUnicode} used)"
            SmsEncoding.GSM7 -> "$parts · ${count.units} of ${capacity(count.parts)}"
        }
    }

    private fun capacity(parts: Int): Int = if (parts <= 1) GSM_SINGLE else parts * GSM_PER_PART

    private fun firstNonGsm(text: String): String? {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val length = Character.charCount(codePoint)
            val isGsm = length == 1 && (text[index] in basic || text[index] in extended)
            if (!isGsm) return text.substring(index, index + length)
            index += length
        }
        return null
    }

    private fun countGsm(text: String): SmsCount {
        val weights = text.map { if (it in extended) 2 else 1 }
        val units = weights.sum()
        val parts = when {
            units == 0 -> 0
            units <= GSM_SINGLE -> 1
            else -> pack(weights, GSM_PER_PART)
        }
        return SmsCount(parts, SmsEncoding.GSM7, units, null)
    }

    private fun countUnicode(text: String, firstUnicode: String): SmsCount {
        val units = text.length
        val parts = if (units <= UNICODE_SINGLE) {
            1
        } else {
            val weights = buildList {
                var index = 0
                while (index < text.length) {
                    val length = Character.charCount(text.codePointAt(index))
                    add(length)
                    index += length
                }
            }
            pack(weights, UNICODE_PER_PART)
        }
        return SmsCount(parts, SmsEncoding.UNICODE, units, firstUnicode)
    }

    /** Fills parts in order. A unit that can't be split moves whole to the next part. */
    private fun pack(weights: List<Int>, capacity: Int): Int {
        var parts = 1
        var used = 0
        for (weight in weights) {
            if (used + weight > capacity) {
                parts++
                used = 0
            }
            used += weight
        }
        return parts
    }
}
