package com.swastik.mbfitness.domain

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** When backups happen, what they're called and how long they're kept (SPEC 12). */
object BackupRules {
    const val FORMAT = "mbfitness-backup"
    const val KEEP = 8
    const val MIN_PASSWORD_LENGTH = 8

    /** At or above the 200,000 SPEC 12 asks for. */
    const val KEY_ITERATIONS = 210_000
    private const val OVERDUE_AFTER_DAYS = 8L
    private const val DAYS_IN_WEEK = 7
    private val NAME = Regex("""^mbfitness_backup_(\d{4}-\d{2}-\d{2})\.json$""")

    fun fileName(day: Long): String = "mbfitness_backup_${LocalDate.ofEpochDay(day)}.json"

    fun isBackupFile(name: String): Boolean = NAME.matches(name)

    /** Everything past the newest [keep]. Files the app didn't name are never touched. */
    fun filesToDelete(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter(::isBackupFile).sortedDescending().drop(keep)

    /** The newest backup in a folder, for "Restore latest backup". */
    fun newest(names: List<String>, today: Long): String? = newestFirst(names, today).firstOrNull()

    /**
     * Backups, newest first, so a damaged newest file can give way to the one before it. A file
     * dated in the future was named by a wrong clock, so it can't be trusted as the newest.
     */
    fun newestFirst(names: List<String>, today: Long): List<String> {
        val (real, future) = names.filter(::isBackupFile).partition { (dayOf(it) ?: 0) <= today }
        return real.sortedDescending() + future.sortedDescending()
    }

    /** The day in a backup's name. Null if it isn't a real date. */
    fun dayOf(name: String): Long? = NAME.matchEntire(name)?.groupValues?.get(1)?.let {
        try {
            LocalDate.parse(it).toEpochDay()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** The app's own daily copies, kept whatever else is set up (docs/DECISIONS.md). */
    const val OWN_COPIES_KEPT = 7

    /**
     * Sunday's run, or the next run after a missed Sunday. A last backup dated in the future means
     * the clock was wrong, and it can't be allowed to stop backups until that date comes round.
     */
    fun isWeeklyDue(today: Long, lastBackupDay: Long?): Boolean {
        val lastSunday = today - Dates.dayOfWeek(today).value % DAYS_IN_WEEK
        return lastBackupDay == null || lastBackupDay < lastSunday || lastBackupDay > today
    }

    /** How often a copy should be sent off the phone (WhatsApp, Drive). */
    const val COPY_EVERY_DAYS = 30L

    fun isCopyDue(today: Long, lastSentDay: Long?): Boolean =
        lastSentDay == null || today - lastSentDay >= COPY_EVERY_DAYS || lastSentDay > today

    /** A weekly job a day late is fine. More than that, and something is wrong. */
    fun isOverdue(today: Long, lastBackupDay: Long?): Boolean =
        lastBackupDay == null || today - lastBackupDay > OVERDUE_AFTER_DAYS || lastBackupDay > today

    /** "120 members, 840 payments, last payment 3 Nov" (SPEC 12). */
    fun describe(members: Int, payments: Int, lastPaymentDay: Long?): String = buildString {
        append(if (members == 1) "1 member" else "$members members")
        append(", ")
        append(if (payments == 1) "1 payment" else "$payments payments")
        if (lastPaymentDay != null) append(", last payment ${Dates.formatShort(lastPaymentDay)}")
    }

    fun describeLost(aadhaarToReenter: Int): String? = when (aadhaarToReenter) {
        0 -> null
        1 -> "1 Aadhaar number will need re-entering. Only its last 4 digits come back."
        else -> "$aadhaarToReenter Aadhaar numbers will need re-entering. Only their last 4 digits come back."
    }

    fun passwordProblem(password: String, again: String): String? = when {
        password.length < MIN_PASSWORD_LENGTH -> "Use at least $MIN_PASSWORD_LENGTH characters"
        password != again -> "The two passwords don't match"
        else -> null
    }

    fun lastBackupText(today: Long, lastBackupDay: Long?): String = when (lastBackupDay) {
        null -> "No backup yet"
        today -> "Last backup: today"
        today - 1 -> "Last backup: yesterday"
        else -> "Last backup: ${Dates.formatShort(lastBackupDay)}"
    }
}

/** Plain-text exports. They never include Aadhaar (SPEC 12, hard rule 11). */
object CsvExport {
    private const val LINE_END = "\r\n"
    private val STARTS_LIKE_A_FORMULA = setOf('=', '+', '-', '@', '\t', '\r')

    data class MemberRow(
        val name: String,
        val phone: String,
        val planMonths: Int,
        val planName: String,
        val status: String,
        val expiry: Long,
        val balance: Long,
        val joined: Long,
        val dob: Long?,
        val notes: String,
        val guardianName: String?,
        val guardianPhone: String?,
    )

    data class PaymentRow(
        val receipt: String?,
        val memberName: String,
        val phone: String,
        val kind: String,
        val paidOn: Long,
        val charged: Long,
        val paid: Long,
        val mode: String,
        val note: String,
    )

    fun fileName(kind: String, day: Long): String = "mbfitness_${kind}_${LocalDate.ofEpochDay(day)}.csv"

    /** The first columns match the import file, so an export can go straight back in. */
    fun members(rows: List<MemberRow>): String = table(
        listOf(
            "name", "phone", "plan_months", "expiry_date", "balance_due", "dob", "notes",
            "advance", "plan", "status", "joined", "guardian_name", "guardian_phone",
        ),
        rows.map { row ->
            listOf(
                row.name,
                localNumber(row.phone),
                row.planMonths.toString(),
                Dates.formatCsv(row.expiry),
                MemberRules.due(row.balance).toString(),
                row.dob?.let(Dates::formatCsv).orEmpty(),
                row.notes,
                MemberRules.advance(row.balance).toString(),
                row.planName,
                row.status,
                Dates.formatCsv(row.joined),
                row.guardianName.orEmpty(),
                row.guardianPhone?.let(::localNumber).orEmpty(),
            )
        },
    )

    fun payments(rows: List<PaymentRow>): String = table(
        listOf("receipt", "name", "phone", "kind", "date", "charged", "paid", "mode", "note"),
        rows.map { row ->
            listOf(
                row.receipt.orEmpty(),
                row.memberName,
                localNumber(row.phone),
                row.kind,
                Dates.formatCsv(row.paidOn),
                row.charged.toString(),
                row.paid.toString(),
                row.mode,
                row.note,
            )
        },
    )

    private fun localNumber(phone: String) = phone.removePrefix("+91")

    private fun table(header: List<String>, rows: List<List<String>>): String = buildString {
        (listOf(header) + rows).forEach { row ->
            append(row.joinToString(",", transform = ::cell))
            append(LINE_END)
        }
    }

    /**
     * RFC 4180 quoting. A name starting with "=" would otherwise run as a formula when the file
     * is opened in Excel, so those get an apostrophe, which Excel treats as "this is text".
     */
    private fun cell(value: String): String {
        val safe = if (value.firstOrNull() in STARTS_LIKE_A_FORMULA) "'$value" else value
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"${safe.replace("\"", "\"\"")}\"" else safe
    }
}
