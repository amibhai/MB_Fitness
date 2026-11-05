package com.swastik.mbfitness.domain

/**
 * A checked Aadhaar number (SPEC 7.10). It can only be made by [parse], and it masks itself
 * in [toString], so printing or logging one can't leak the number (hard rule 11). The digits
 * only come out through [revealDigits], which is easy to audit by name.
 */
@JvmInline
value class Aadhaar private constructor(private val digits: String) {
    val last4: String get() = digits.takeLast(LAST4_LENGTH)

    fun revealDigits(): String = digits

    /** "1234 5678 9012", for the 30-second reveal only. */
    fun revealGrouped(): String = digits.chunked(4).joinToString(" ")

    override fun toString(): String = masked(last4)

    sealed interface ParseResult {
        data class Valid(val aadhaar: Aadhaar) : ParseResult
        data object Invalid : ParseResult

        /** Excel turned the number into something like 1.23457E+11, losing digits for good. */
        data object DamagedByExcel : ParseResult
    }

    companion object {
        const val LAST4_LENGTH = 4
        private const val LENGTH = 12
        private val SEPARATORS = Regex("""[\s-]""")
        private val SCIENTIFIC = Regex("""^\d+(\.\d+)?[eE]\+?\d+$""")

        // The Verhoeff tables: the dihedral group D5, a permutation and the inverses.
        private val D = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
            intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
            intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
            intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
            intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
            intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
            intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
            intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
            intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
        )
        private val P = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
            intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
            intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
            intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
            intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
            intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
            intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
        )
        private val INVERSE = intArrayOf(0, 4, 3, 2, 1, 5, 6, 7, 8, 9)

        fun parse(raw: String): ParseResult {
            val text = raw.trim()
            if (SCIENTIFIC.matches(text)) return ParseResult.DamagedByExcel
            val digits = text.replace(SEPARATORS, "")
            val wellFormed = digits.length == LENGTH && digits.all { it in '0'..'9' } && digits.first() in '2'..'9'
            return if (wellFormed && checksumIsValid(digits)) {
                ParseResult.Valid(Aadhaar(digits))
            } else {
                ParseResult.Invalid
            }
        }

        /** "XXXX XXXX 1234": all the screen ever shows unless the owner asks to reveal it. */
        fun masked(last4: String): String = "XXXX XXXX $last4"

        internal fun checksumIsValid(digits: String): Boolean {
            var check = 0
            digits.reversed().forEachIndexed { index, char ->
                check = D[check][P[index % P.size][char - '0']]
            }
            return check == 0
        }

        internal fun checkDigitFor(digits: String): Char {
            var check = 0
            digits.reversed().forEachIndexed { index, char ->
                check = D[check][P[(index + 1) % P.size][char - '0']]
            }
            return '0' + INVERSE[check]
        }
    }
}
