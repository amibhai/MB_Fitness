package com.swastik.mbfitness.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/** Business dates are epoch days in this zone (hard rule 5). India never changes zone. */
val GYM_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

/** The only way the app reads the clock, so tests can set any date (SPEC 5.3). */
interface TodayProvider {
    /** Today in [GYM_ZONE], as an epoch day. */
    fun today(): Long

    /** The current instant, for audit fields and pacing. */
    fun nowMillis(): Long
}

object SystemTodayProvider : TodayProvider {
    override fun today(): Long = LocalDate.now(GYM_ZONE).toEpochDay()
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Spots a phone whose clock is plainly wrong, such as after its battery ran flat. */
object ClockCheck {
    /** The day this app was first released. A phone showing an earlier date can't be right. */
    private val FIRST_RELEASED = Dates.of(2026, 11, 6)

    fun looksWrong(today: Long): Boolean = today < FIRST_RELEASED
}

object Dates {
    private val SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val FULL = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)
    private val HEADER = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(GYM_ZONE)
    private val CSV = DateTimeFormatter.ofPattern("d-M-uuuu", Locale.ENGLISH)
        .withResolverStyle(ResolverStyle.STRICT)
    private val CSV_OUT = DateTimeFormatter.ofPattern("dd-MM-uuuu", Locale.ENGLISH)

    fun of(year: Int, month: Int, day: Int): Long = LocalDate.of(year, month, day).toEpochDay()

    /** Clamps to the end of the month: 31 Jan + 1 month is 28 or 29 Feb (SPEC 7.1). */
    fun addMonths(day: Long, months: Int): Long =
        LocalDate.ofEpochDay(day).plusMonths(months.toLong()).toEpochDay()

    fun dayOfWeek(day: Long): DayOfWeek = LocalDate.ofEpochDay(day).dayOfWeek

    fun year(day: Long): Int = LocalDate.ofEpochDay(day).year

    /** "12 Dec" */
    fun formatShort(day: Long): String = SHORT.format(LocalDate.ofEpochDay(day))

    /** "12 Dec 2026" */
    fun formatFull(day: Long): String = FULL.format(LocalDate.ofEpochDay(day))

    /** "Wed, 4 Nov" */
    fun formatHeader(day: Long): String = HEADER.format(LocalDate.ofEpochDay(day))

    /** "10:05 AM" in [GYM_ZONE]. */
    fun formatTime(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis))

    /** The epoch day [millis] falls on in [GYM_ZONE]. */
    fun dayOf(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(GYM_ZONE).toLocalDate().toEpochDay()

    fun millisAt(day: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.ofEpochDay(day).atTime(hour, minute).atZone(GYM_ZONE).toInstant().toEpochMilli()

    /** Reads DD-MM-YYYY. Excel often rewrites it, so 1-2-2026 and 01/02/2026 also work. */
    fun parseCsvDate(text: String): Long? = try {
        LocalDate.parse(text.trim().replace('/', '-').replace('.', '-'), CSV).toEpochDay()
    } catch (_: DateTimeParseException) {
        null
    }

    /** Writes DD-MM-YYYY, the same as the import file reads. */
    fun formatCsv(day: Long): String = CSV_OUT.format(LocalDate.ofEpochDay(day))

    /** Someone born on 29 Feb celebrates on 28 Feb in non-leap years (SPEC 8.2). */
    fun isBirthday(dob: Long, today: Long): Boolean {
        if (today <= dob) return false
        val born = LocalDate.ofEpochDay(dob)
        val now = LocalDate.ofEpochDay(today)
        val isLeapDayBaby = born.monthValue == 2 && born.dayOfMonth == 29
        return if (isLeapDayBaby && !now.isLeapYear) {
            now.monthValue == 2 && now.dayOfMonth == 28
        } else {
            born.monthValue == now.monthValue && born.dayOfMonth == now.dayOfMonth
        }
    }
}
