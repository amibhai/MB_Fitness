package com.swastik.mbfitness.domain

/** "MBF-0017": sequential and never reused (SPEC 4-B). The counter itself lives in Room. */
object ReceiptNumbers {
    private const val PREFIX = "MBF-"
    private const val MIN_DIGITS = 4

    fun format(number: Long): String = PREFIX + number.toString().padStart(MIN_DIGITS, '0')

    fun parse(text: String): Long? =
        text.trim().takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toLongOrNull()?.takeIf { it > 0 }
}
