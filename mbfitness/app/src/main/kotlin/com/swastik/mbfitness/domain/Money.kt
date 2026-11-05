package com.swastik.mbfitness.domain

/** Money is whole rupees as Long everywhere (hard rule 5). */
object Money {
    /** "₹1,200" and "₹1,25,000", grouped the Indian way (SPEC conventions). */
    fun format(rupees: Long): String {
        val sign = if (rupees < 0) "-" else ""
        return "$sign₹${group(Math.abs(rupees))}"
    }

    /** What TalkBack should read: "1200 rupees" (SPEC 10.1). */
    fun spoken(rupees: Long): String {
        val sign = if (rupees < 0) "minus " else ""
        val amount = Math.abs(rupees)
        return "$sign$amount ${if (amount == 1L) "rupee" else "rupees"}"
    }

    private fun group(rupees: Long): String {
        val digits = rupees.toString()
        if (digits.length <= 3) return digits
        val lastThree = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val pairs = rest.reversed().chunked(2).joinToString(",").reversed()
        return "$pairs,$lastThree"
    }
}
