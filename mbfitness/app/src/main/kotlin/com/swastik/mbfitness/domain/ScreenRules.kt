package com.swastik.mbfitness.domain

/** Small rules behind the member screens, kept here so they can be unit-tested (hard rule 4). */
object ScreenRules {
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    /** Search by name, or by any digits of the phone (SPEC 4-A). Never by Aadhaar. */
    fun matchesSearch(name: String, phone: String, query: String): Boolean {
        val text = query.trim()
        return text.isEmpty() || name.contains(text, ignoreCase = true) || PhoneNumbers.matchesDigits(phone, text)
    }

    /** "₹700 will remain due", shown before saving a payment (SPEC 7.3). */
    fun describe(after: AfterPayment): String = when (after) {
        is AfterPayment.Remaining -> "${Money.format(after.amount)} will remain due"
        AfterPayment.FullyPaid -> "Fully paid"
        is AfterPayment.Extra -> "${Money.format(after.amount)} extra. Check the amount."
    }

    /** Whole days left in the recycle bin, never below 0 (SPEC 10.2 screen 16). */
    fun recycleBinDaysLeft(deletedAt: Long, nowMillis: Long, keepDays: Long): Long {
        val gone = (nowMillis - deletedAt) / MILLIS_PER_DAY
        return (keepDays - gone).coerceAtLeast(0)
    }

    /** Digits only, and no longer than a real amount, so a slip can't save a huge number. */
    fun parseAmount(text: String): Long? = text.takeIf { it.isNotEmpty() && it.length <= MAX_AMOUNT_DIGITS && it.all(Char::isDigit) }?.toLong()

    private const val MAX_AMOUNT_DIGITS = 7

    fun amountInput(text: String): String = text.filter(Char::isDigit).take(MAX_AMOUNT_DIGITS).trimStart('0').ifEmpty { if (text.contains('0')) "0" else "" }
}

data class ClosingEntry(val memberName: String, val receipt: String?, val amount: Long, val mode: PaymentMode)

data class ClosingTotals(val cash: Long, val upi: Long, val other: Long) {
    val total: Long get() = cash + upi + other
}

/** Today's closing (SPEC 10.2 screen 8). */
object ClosingSummary {
    fun totals(entries: List<ClosingEntry>): ClosingTotals = ClosingTotals(
        cash = entries.filter { it.mode == PaymentMode.CASH }.sumOf { it.amount },
        upi = entries.filter { it.mode == PaymentMode.UPI }.sumOf { it.amount },
        other = entries.filter { it.mode == PaymentMode.OTHER }.sumOf { it.amount },
    )

    /** Plain text for the clipboard. It only ever holds names, receipts and amounts. */
    fun shareText(gymName: String, day: Long, entries: List<ClosingEntry>): String {
        val totals = totals(entries)
        return buildString {
            appendLine("$gymName: closing for ${Dates.formatFull(day)}")
            appendLine("Cash: ${Money.format(totals.cash)}")
            appendLine("UPI: ${Money.format(totals.upi)}")
            appendLine("Other: ${Money.format(totals.other)}")
            append("Total: ${Money.format(totals.total)}")
            for (entry in entries) {
                appendLine()
                append("${entry.receipt ?: "-"}  ${entry.memberName}  ${Money.format(entry.amount)}  ${entry.mode}")
            }
        }
    }
}
