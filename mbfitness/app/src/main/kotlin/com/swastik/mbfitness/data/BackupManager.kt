package com.swastik.mbfitness.data

import androidx.room.withTransaction
import com.swastik.mbfitness.domain.BackupRules
import com.swastik.mbfitness.domain.CsvExport
import com.swastik.mbfitness.domain.ReceiptNumbers
import com.swastik.mbfitness.domain.TodayProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.util.Base64

sealed interface BackupOutcome {
    data class Saved(val name: String) : BackupOutcome
    data object NoFolder : BackupOutcome
    data object FolderGone : BackupOutcome

    /** A password is set, but this phone can't reach its stored key any more. */
    data object PasswordNeeded : BackupOutcome
    data class Failed(val reason: String) : BackupOutcome
}

/** A checked backup, ready to be restored. Holding it holds no plaintext on disk. */
class RestorePlan internal constructor(internal val data: BackupData)

sealed interface RestoreOpen {
    data class NeedsPassword(val hint: String?) : RestoreOpen
    data object WrongPassword : RestoreOpen
    data class NotABackup(val reason: String) : RestoreOpen
    data object TooNew : RestoreOpen
    data class Ready(val summary: String, val aadhaarNote: String?, val plan: RestorePlan) : RestoreOpen
}

sealed interface BackupRestoreResult {
    data class Restored(val aadhaarToReenter: Int) : BackupRestoreResult
    data object SafetyCopyFailed : BackupRestoreResult
    data class Failed(val reason: String) : BackupRestoreResult
}

/**
 * Backups, restore and CSV export (SPEC 12). Everything here is checked before anything changes.
 * The full Aadhaar number only leaves its encryption inside a password-protected file (hard rule 11).
 */
class BackupManager(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val vault: AadhaarVault,
    private val keyVault: AadhaarVault,
    private val clock: TodayProvider,
    private val errors: ErrorLogger,
    private val appVersion: String,
    private val filesDir: File,
    private val folderFor: (String) -> BackupFolder,
) {
    private val dao get() = db.backup()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    // ---- Making a backup ----

    /** The file's bytes, or null if a password is set and its key can't be reached. */
    suspend fun buildFile(): ByteArray? = withContext(Dispatchers.IO) {
        val current = settings.current()
        if (!current.backupPasswordSet) {
            encodeFile(BackupFile(BackupRules.FORMAT, AppDatabase.VERSION, appVersion, data = collect(current, fullAadhaar = false)))
        } else {
            val key = storedKey(current) ?: return@withContext null
            val plain = json.encodeToString(collect(current, fullAadhaar = true)).toByteArray()
            val (iv, sealed) = BackupCrypto.encrypt(key, plain, LABEL)
            encodeFile(
                BackupFile(
                    BackupRules.FORMAT,
                    AppDatabase.VERSION,
                    appVersion,
                    encrypted = true,
                    salt = current.backupSalt,
                    iv = base64(iv),
                    iterations = BackupRules.KEY_ITERATIONS,
                    hint = current.backupPasswordHint,
                    cipherText = base64(sealed),
                ),
            )
        }
    }

    private fun encodeFile(file: BackupFile): ByteArray = json.encodeToString(file).toByteArray()

    private suspend fun collect(current: AppSettings, fullAadhaar: Boolean): BackupData {
        val members = dao.members()
        // With a password, the real numbers go inside. A number this phone can't open stays sealed.
        val numbers = if (fullAadhaar) {
            members.mapNotNull { member ->
                member.aadhaarEnc?.let(vault::decrypt)?.let { AadhaarEntry(member.id, it) }
            }
        } else {
            emptyList()
        }
        val opened = numbers.map { it.memberId }.toSet()
        return BackupData(
            exportedOn = clock.today(),
            settings = current.toBackup(),
            logoBase64 = current.logoFile?.let { File(filesDir, it) }?.takeIf { it.exists() }?.let { base64(it.readBytes()) },
            plans = dao.plans(),
            members = members.map { if (it.id in opened) it.copy(aadhaarEnc = null) else it },
            aadhaar = numbers,
            payments = dao.payments(),
            freezes = dao.freezes(),
            events = dao.events(),
            messages = dao.messages(),
            templates = dao.templates(),
            broadcasts = dao.broadcasts(),
            quietDates = dao.quietDates(),
            counters = dao.counters(),
        )
    }

    /** Saves today's backup to the chosen folder, then keeps only the newest 8. */
    suspend fun backupNow(): BackupOutcome {
        val current = settings.current()
        val uri = current.backupFolderUri ?: return BackupOutcome.NoFolder
        val folder = folderFor(uri)
        return try {
            withContext(Dispatchers.IO) {
                if (!folder.isUsable()) return@withContext BackupOutcome.FolderGone
                val bytes = buildFile() ?: return@withContext BackupOutcome.PasswordNeeded
                val today = clock.today()
                val name = BackupRules.fileName(today)
                folder.write(name, bytes)
                BackupRules.filesToDelete(folder.names()).forEach(folder::delete)
                settings.update { it.copy(lastBackupDay = today) }
                BackupOutcome.Saved(name)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Only the kind of failure. A message could hold a file's contents (hard rule 11).
            errors.log("Backup", "Failed: ${error.javaClass.simpleName}")
            BackupOutcome.Failed(error.javaClass.simpleName)
        }
    }

    /** Part of the daily run. False means it was due and didn't happen. */
    suspend fun runWeeklyIfDue(): Boolean {
        val current = settings.current()
        if (current.backupFolderUri == null) return true
        if (!BackupRules.isWeeklyDue(clock.today(), current.lastBackupDay)) return true
        return backupNow() is BackupOutcome.Saved
    }

    // ---- Backup password ----

    /** False if the phone's Keystore refused to hold the key, in which case nothing changed. */
    suspend fun setPassword(password: String, hint: String?): Boolean = withContext(Dispatchers.Default) {
        val salt = BackupCrypto.newSalt()
        val key = BackupCrypto.deriveKey(password, salt, BackupRules.KEY_ITERATIONS)
        val wrapped = keyVault.encrypt(base64(key)) ?: return@withContext false
        settings.update {
            it.copy(
                backupPasswordSet = true,
                backupPasswordHint = hint?.trim()?.takeIf(String::isNotEmpty),
                backupSalt = base64(salt),
                backupKeyWrapped = wrapped,
            )
        }
        true
    }

    suspend fun removePassword() {
        settings.update {
            it.copy(backupPasswordSet = false, backupPasswordHint = null, backupSalt = null, backupKeyWrapped = null)
        }
    }

    private fun storedKey(current: AppSettings): ByteArray? =
        current.backupKeyWrapped?.let(keyVault::decrypt)?.let(::fromBase64)

    // ---- Restoring ----

    /** Reads and fully checks a file. Nothing in the app changes (SPEC 12). */
    suspend fun open(bytes: ByteArray, password: String? = null): RestoreOpen = withContext(Dispatchers.Default) {
        val text = bytes.toString(Charsets.UTF_8)
        val header = decodeOrNull { json.decodeFromString<BackupHeader>(text) }
        if (header == null || header.format != BackupRules.FORMAT) return@withContext RestoreOpen.NotABackup(NOT_A_BACKUP)
        if (header.schemaVersion > AppDatabase.VERSION) return@withContext RestoreOpen.TooNew
        val file = decodeOrNull { json.decodeFromString<BackupFile>(text) } ?: return@withContext RestoreOpen.NotABackup(NOT_A_BACKUP)

        val data = if (file.encrypted) {
            if (password == null) return@withContext RestoreOpen.NeedsPassword(file.hint)
            when (val opened = decrypt(file, password)) {
                null -> return@withContext RestoreOpen.WrongPassword
                else -> decodeOrNull { json.decodeFromString<BackupData>(opened.toString(Charsets.UTF_8)) }
            }
        } else {
            file.data
        } ?: return@withContext RestoreOpen.NotABackup(NOT_A_BACKUP)

        BackupValidator.problem(data)?.let { return@withContext RestoreOpen.NotABackup("This backup is damaged. $it.") }
        val active = data.members.filter { it.deletedAt == null }
        val lastPayment = data.payments.filter { it.deletedAt == null && it.paid > 0 }.maxOfOrNull { it.paidOn }
        val paymentCount = data.payments.count { it.deletedAt == null && it.paid > 0 }
        RestoreOpen.Ready(
            BackupRules.describe(active.size, paymentCount, lastPayment),
            BackupRules.describeLost(toReenter(data)),
            RestorePlan(data),
        )
    }

    private fun decrypt(file: BackupFile, password: String): ByteArray? {
        val salt = file.salt?.let(::fromBase64) ?: return null
        val iv = file.iv?.let(::fromBase64) ?: return null
        val sealed = file.cipherText?.let(::fromBase64) ?: return null
        val iterations = file.iterations?.takeIf { it in 1..MAX_ITERATIONS } ?: return null
        return BackupCrypto.decrypt(BackupCrypto.deriveKey(password, salt, iterations), iv, sealed, LABEL)
    }

    /** How many will come back as the last 4 digits only. */
    private fun toReenter(data: BackupData): Int {
        val inFile = data.aadhaar.map { it.memberId }.toSet()
        return data.members.count { member ->
            member.aadhaarLast4 != null && member.id !in inFile && member.aadhaarEnc?.let(vault::decrypt) == null
        }
    }

    /** Saves a safety copy first, then replaces everything in one transaction (SPEC 12). */
    suspend fun restore(plan: RestorePlan): BackupRestoreResult {
        if (!saveSafetyCopy()) return BackupRestoreResult.SafetyCopyFailed
        val data = plan.data
        return try {
            // Keystore work is slow, so it happens before the transaction opens.
            val members = withContext(Dispatchers.IO) { resealForThisPhone(data) }
            db.withTransaction {
                clearEverything()
                dao.insertPlans(data.plans)
                dao.insertMembers(members)
                dao.insertPayments(data.payments)
                dao.insertFreezes(data.freezes)
                dao.insertEvents(data.events)
                dao.insertMessages(data.messages)
                dao.insertTemplates(data.templates)
                dao.insertBroadcasts(data.broadcasts)
                dao.insertQuietDates(data.quietDates)
                dao.insertCounters(data.counters)
                dao.stopUnsent(STOPPED_BY_RESTORE)
            }
            val logo = withContext(Dispatchers.IO) { restoreLogo(data.logoBase64) }
            settings.update { it.fromBackup(data.settings, logo, clock.today()) }
            BackupRestoreResult.Restored(members.count { it.aadhaarLast4 != null && it.aadhaarEnc?.let(vault::decrypt) == null })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            errors.log("Restore", "Failed: ${error.javaClass.simpleName}")
            BackupRestoreResult.Failed(error.javaClass.simpleName)
        }
    }

    private fun resealForThisPhone(data: BackupData): List<MemberEntity> {
        val numbers = data.aadhaar.associate { it.memberId to it.digits }
        return data.members.map { member ->
            numbers[member.id]?.let { digits -> member.copy(aadhaarEnc = vault.encrypt(digits)) } ?: member
        }
    }

    private suspend fun clearEverything() {
        dao.clearMessages()
        dao.clearEvents()
        dao.clearFreezes()
        dao.clearPayments()
        dao.clearMembers()
        dao.clearPlans()
        dao.clearTemplates()
        dao.clearBroadcasts()
        dao.clearQuietDates()
        dao.clearCounters()
    }

    private fun restoreLogo(encoded: String?): String? {
        val folder = File(filesDir, LOGO_FOLDER)
        folder.listFiles()?.forEach(File::delete)
        if (encoded == null) return null
        folder.mkdirs()
        val name = "logo_${clock.nowMillis()}.png"
        File(folder, name).writeBytes(fromBase64(encoded))
        return "$LOGO_FOLDER/$name"
    }

    /**
     * Before a restore replaces everything. Always kept privately, with Aadhaar numbers sealed,
     * and also put in the backup folder, if there is one, protected the way backups normally are.
     */
    private suspend fun saveSafetyCopy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val current = settings.current()
            val plain = encodeFile(BackupFile(BackupRules.FORMAT, AppDatabase.VERSION, appVersion, data = collect(current, fullAadhaar = false)))
            safetyFile().apply { parentFile?.mkdirs() }.writeBytes(plain)
            current.backupFolderUri?.let { uri ->
                try {
                    buildFile()?.let { folderFor(uri).write("mbfitness_safety_${java.time.LocalDate.ofEpochDay(clock.today())}.json", it) }
                } catch (_: IOException) {
                    // The private copy is what matters. A missing folder mustn't block a restore.
                } catch (_: SecurityException) {
                    // Same: the folder's permission can be gone.
                }
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun safetyFile() = File(filesDir, "safety/before_last_restore.json")

    /** The data from before the last restore, for putting it back. Null if there isn't any. */
    fun safetyCopy(): ByteArray? = safetyFile().takeIf { it.exists() }?.readBytes()

    fun hasSafetyCopy(): Boolean = safetyFile().exists()

    // ---- CSV (never includes Aadhaar) ----

    suspend fun membersCsv(): String {
        val plans = dao.plans().associateBy { it.id }
        val balances = dao.payments().filter { it.deletedAt == null }.groupBy { it.memberId }
            .mapValues { (_, rows) -> rows.sumOf { it.charged - it.paid } }
        return CsvExport.members(
            dao.members().filter { it.deletedAt == null }.map { member ->
                val plan = plans[member.planId]
                CsvExport.MemberRow(
                    name = member.name,
                    phone = member.phone,
                    planMonths = plan?.months ?: 0,
                    planName = plan?.name.orEmpty(),
                    status = member.status.name.lowercase(),
                    expiry = member.expiryDate,
                    balance = balances[member.id] ?: 0,
                    joined = member.joinDate,
                    dob = member.dob,
                    notes = member.notes,
                    guardianName = member.guardianName,
                    guardianPhone = member.guardianPhone,
                )
            },
        )
    }

    suspend fun paymentsCsv(): String {
        val members = dao.members().associateBy { it.id }
        return CsvExport.payments(
            dao.payments().filter { it.deletedAt == null }.map { payment ->
                val member = members[payment.memberId]
                CsvExport.PaymentRow(
                    receipt = payment.receiptNo?.let(ReceiptNumbers::format),
                    memberName = member?.name.orEmpty(),
                    phone = member?.phone.orEmpty(),
                    kind = payment.kind.name.lowercase(),
                    paidOn = payment.paidOn,
                    charged = payment.charged,
                    paid = payment.paid,
                    mode = payment.mode.name.lowercase(),
                    note = payment.note,
                )
            },
        )
    }

    private inline fun <T> decodeOrNull(block: () -> T): T? = try {
        block()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun fromBase64(text: String): ByteArray = Base64.getDecoder().decode(text)

    private fun AppSettings.toBackup() = BackupSettings(
        gymName = gymName,
        ownerName = ownerName,
        greeting = greeting,
        trainerPhone = trainerPhone,
        upiId = upiId,
        upiPayeeName = upiPayeeName,
        reminderHour = reminderHour,
        reminderMinute = reminderMinute,
        dailyCapParts = dailyCapParts,
        windowCapParts = windowCapParts,
        renewalRule = renewalRule,
        restartAfterDays = restartAfterDays,
        quietWeekdays = quietWeekdays.map { it.name }.sorted(),
        morningSummary = morningSummary,
        allClearNotice = allClearNotice,
        packLanguage = packLanguage,
    )

    /**
     * Test mode, the PIN, the SIM and the backup folder stay as they are on this phone. Nothing
     * runs until tomorrow, because the backup can't show what went out after it was made, and that
     * gives time to look things over first. Tomorrow's run catches up, one truthful SMS each.
     */
    private fun AppSettings.fromBackup(from: BackupSettings, logo: String?, today: Long) = copy(
        gymName = from.gymName,
        ownerName = from.ownerName,
        greeting = from.greeting,
        trainerPhone = from.trainerPhone,
        upiId = from.upiId,
        upiPayeeName = from.upiPayeeName,
        logoFile = logo,
        reminderHour = from.reminderHour,
        reminderMinute = from.reminderMinute,
        dailyCapParts = from.dailyCapParts,
        windowCapParts = from.windowCapParts,
        renewalRule = from.renewalRule,
        restartAfterDays = from.restartAfterDays,
        quietWeekdays = from.quietWeekdays.mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }.toSet(),
        morningSummary = from.morningSummary,
        allClearNotice = from.allClearNotice,
        packLanguage = from.packLanguage,
        lastDailyRunDay = today,
        onboardingDone = true,
    )

    companion object {
        const val LOGO_FOLDER = "logo"
        private const val NOT_A_BACKUP = "This isn't an MB Fitness backup file."
        private const val STOPPED_BY_RESTORE = "Not sent: the data was restored from a backup"
        private const val MAX_ITERATIONS = 10_000_000
        private val LABEL = BackupRules.FORMAT.toByteArray()
    }
}
