package com.swastik.mbfitness.domain

/** Indian mobile numbers, stored as "+91XXXXXXXXXX" (SPEC 7.8). */
object PhoneNumbers {
    private const val LOCAL_LENGTH = 10
    private val ALLOWED = Regex("""^[0-9+\-\s().]*$""")

    /** Returns the stored form, or null if this isn't a valid Indian mobile number. */
    fun normalize(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || !ALLOWED.matches(text)) return null
        val digits = text.filter { it.isDigit() }
        val local = when {
            // Only strip a prefix when the length proves it is one. A real 10-digit number
            // can begin with 91, and stripping that would corrupt it.
            digits.length == LOCAL_LENGTH -> digits
            digits.length == LOCAL_LENGTH + 1 && digits.startsWith("0") -> digits.drop(1)
            digits.length == LOCAL_LENGTH + 2 && digits.startsWith("91") -> digits.drop(2)
            digits.length == LOCAL_LENGTH + 4 && digits.startsWith("0091") -> digits.drop(4)
            else -> return null
        }
        return if (local.first() in '6'..'9') "+91$local" else null
    }

    /** "98765 43210" from the stored form. */
    fun display(stored: String): String {
        val local = stored.removePrefix("+91")
        return if (local.length == LOCAL_LENGTH) "${local.take(5)} ${local.drop(5)}" else stored
    }

    /** Search by any digits of the phone (SPEC 4-A). A query with no digits never matches. */
    fun matchesDigits(stored: String, query: String): Boolean {
        val digits = query.filter { it.isDigit() }
        return digits.isNotEmpty() && stored.removePrefix("+91").contains(digits)
    }
}
