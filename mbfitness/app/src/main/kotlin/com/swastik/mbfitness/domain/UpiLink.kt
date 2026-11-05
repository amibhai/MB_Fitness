package com.swastik.mbfitness.domain

/** Builds the upi://pay link shown as a QR code. Works offline (SPEC 2, 10.2 screen 6). */
object UpiLink {
    private val VPA = Regex("""^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+$""")
    private const val UNRESERVED = "-._~@"

    fun isValidVpa(vpa: String): Boolean = VPA.matches(vpa.trim())

    /** The amount always has 2 decimals. Rupees are whole, so that is always ".00". */
    fun build(vpa: String, payeeName: String, amountRupees: Long, note: String): String =
        "upi://pay?pa=${encode(vpa.trim())}&pn=${encode(payeeName.trim())}" +
            "&am=$amountRupees.00&cu=INR&tn=${encode(note.trim())}"

    /** The note on the payment, e.g. "MB Fitness - Rahul" (SPEC 10.2 screen 6). */
    fun note(fullName: String): String = "MB Fitness - ${TemplateEngine.firstName(fullName)}"

    /**
     * Percent-encodes as UTF-8. A hand-written encoder rather than URLEncoder because that
     * turns spaces into "+", which some UPI apps show literally in the payee name.
     */
    internal fun encode(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in UNRESERVED) {
                append(char)
            } else {
                append('%').append("%02X".format(value))
            }
        }
    }
}
