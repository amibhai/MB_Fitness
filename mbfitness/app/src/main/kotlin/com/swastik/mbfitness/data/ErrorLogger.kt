package com.swastik.mbfitness.data

import com.swastik.mbfitness.domain.LogText
import com.swastik.mbfitness.domain.TodayProvider
import java.io.File
import java.io.IOException

/**
 * The local error log, newest 200 (SPEC 6). It stays on the phone. Everything written here goes
 * through [LogText.redact], so even a slip can't put an Aadhaar number in it (hard rule 11).
 */
class ErrorLogger(private val db: AppDatabase, private val clock: TodayProvider) {
    suspend fun log(tag: String, message: String) {
        db.errorLog().insert(ErrorLogEntity(at = clock.nowMillis(), tag = tag, message = LogText.redact(message)))
        db.errorLog().trim(KEEP)
    }

    private companion object {
        const val KEEP = 200
    }
}

/**
 * A crash ends the process before the database can be written, so its details wait in a file.
 * The next start moves them into the error log. Only the newest is kept.
 */
class CrashReports(private val dir: File) {
    private val file get() = File(dir, FILE_NAME)
    private val times get() = File(dir, TIMES_FILE_NAME)

    fun write(at: Long, where: String, details: String) {
        file.writeText("$at\n$where\n${LogText.redact(details)}")
    }

    /** The waiting report, removed once read. Null if there isn't one or it can't be read. */
    fun take(): Report? = peek()?.also { file.delete() }

    /** The waiting report, left where it is. */
    fun peek(): Report? = try {
        file.takeIf { it.exists() }?.readText()?.let { text ->
            val lines = text.split('\n', limit = 3)
            Report(lines[0].toLongOrNull() ?: 0, lines.getOrElse(1) { "" }, lines.getOrElse(2) { "" })
        }
    } catch (_: IOException) {
        null
    }

    /** Only real crashes count here, for noticing when the app keeps crashing. */
    fun noteCrash(at: Long) {
        times.writeText((crashTimes() + at).takeLast(TIMES_KEPT).joinToString("\n"))
    }

    fun crashTimes(): List<Long> = try {
        times.takeIf { it.exists() }?.readLines()?.mapNotNull { it.toLongOrNull() }.orEmpty()
    } catch (_: IOException) {
        emptyList()
    }

    fun forgetCrashes() {
        times.delete()
    }

    data class Report(val at: Long, val where: String, val details: String)

    private companion object {
        const val FILE_NAME = "last_crash.txt"
        const val TIMES_FILE_NAME = "crash_times.txt"
        const val TIMES_KEPT = 10
    }
}
