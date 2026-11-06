package com.swastik.mbfitness.domain

/** When crashes come this close together, opening the app normally isn't going to help. */
object CrashLoop {
    const val CRASHES = 3
    const val WITHIN_MILLIS = 10L * 60 * 1000

    fun keepsCrashing(crashTimes: List<Long>, nowMillis: Long): Boolean =
        crashTimes.count { it in (nowMillis - WITHIN_MILLIS)..nowMillis } >= CRASHES
}

/**
 * What goes into the error log. Swastik reads it to fix things he wasn't there to see, so a
 * failure is written out in full, but nothing in the log may hold an Aadhaar number (hard rule 11).
 */
object LogText {
    const val MAX_LENGTH = 2_000
    const val MAX_COPIED_LENGTH = 50_000
    private const val MAX_FRAMES = 12
    private const val MAX_CAUSES = 4
    const val HIDDEN = "[number hidden]"

    /** Twelve digits, with or without spaces or dashes in groups of four. A 10-digit phone with +91 is hidden too. */
    private val TWELVE_DIGITS = Regex("""(?<!\d)\d{4}[ -]?\d{4}[ -]?\d{4}(?!\d)""")

    fun redact(text: String): String = TWELVE_DIGITS.replace(text, HIDDEN).take(MAX_LENGTH)

    /**
     * The log as one text, newest first, cut to fit a WhatsApp message. Two hundred full entries
     * would be too big for the clipboard, and copying them would crash the app.
     */
    fun forCopying(lines: List<String>, limit: Int = MAX_COPIED_LENGTH): String = buildString {
        for (line in lines) {
            if (length + line.length + 1 > limit) break
            if (isNotEmpty()) append('\n')
            append(line)
        }
    }

    /** "IllegalStateException: No member 5", each cause after it, then where it happened. */
    fun describe(error: Throwable): String {
        val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).toList()
        val causes = chain.joinToString(" / caused by ") { "${it.javaClass.simpleName.ifEmpty { it.javaClass.name }}: ${it.message}" }
        val frames = chain.last().stackTrace.take(MAX_FRAMES).joinToString("\n") { "  at $it" }
        return redact(if (frames.isEmpty()) causes else "$causes\n$frames")
    }
}
