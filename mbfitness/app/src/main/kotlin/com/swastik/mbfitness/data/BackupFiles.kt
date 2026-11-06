package com.swastik.mbfitness.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.swastik.mbfitness.domain.PackLanguage
import com.swastik.mbfitness.domain.RenewalRule
import kotlinx.serialization.Serializable
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** What's safe to carry to another phone. PIN, SIM and folder settings stay with this one. */
@Serializable
data class BackupSettings(
    val gymName: String,
    val ownerName: String,
    val greeting: String,
    val trainerPhone: String,
    val upiId: String,
    val upiPayeeName: String,
    val reminderHour: Int,
    val reminderMinute: Int,
    val dailyCapParts: Int,
    val windowCapParts: Int,
    val renewalRule: RenewalRule,
    val restartAfterDays: Int,
    val quietWeekdays: List<String>,
    val morningSummary: Boolean,
    val allClearNotice: Boolean,
    val packLanguage: PackLanguage,
)

/** A full number. Only ever in memory or inside a password-encrypted file (hard rule 11). */
@Serializable
data class AadhaarEntry(val memberId: Long, val digits: String) {
    override fun toString(): String = "AadhaarEntry(memberId=$memberId)"
}

/** The contents of a backup. The error log and send log stay behind: they're about this phone. */
@Serializable
data class BackupData(
    val exportedOn: Long,
    val settings: BackupSettings,
    val logoBase64: String? = null,
    val plans: List<PlanEntity>,
    val members: List<MemberEntity>,
    val aadhaar: List<AadhaarEntry> = emptyList(),
    val payments: List<PaymentEntity>,
    val freezes: List<FreezeEntity>,
    val events: List<MemberEventEntity>,
    val messages: List<MessageEntity>,
    val templates: List<TemplateEntity>,
    val broadcasts: List<BroadcastEntity>,
    val quietDates: List<QuietDateEntity>,
    val counters: List<CounterEntity>,
)

/** Read first, so a file from a newer app is refused before anything else is decoded. */
@Serializable
data class BackupHeader(val format: String = "", val schemaVersion: Int = 0)

/** The file on disk. With a password, [data] is null and [cipherText] holds it instead. */
@Serializable
data class BackupFile(
    val format: String,
    val schemaVersion: Int,
    val appVersion: String,
    val encrypted: Boolean = false,
    val data: BackupData? = null,
    val salt: String? = null,
    val iv: String? = null,
    val iterations: Int? = null,
    val hint: String? = null,
    val cipherText: String? = null,
)

/** Everything a backup reads and writes, in a stable order so the same data gives the same file. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM plans ORDER BY id")
    suspend fun plans(): List<PlanEntity>

    @Query("SELECT * FROM members ORDER BY id")
    suspend fun members(): List<MemberEntity>

    @Query("SELECT * FROM payments ORDER BY id")
    suspend fun payments(): List<PaymentEntity>

    @Query("SELECT * FROM freezes ORDER BY id")
    suspend fun freezes(): List<FreezeEntity>

    @Query("SELECT * FROM member_events ORDER BY id")
    suspend fun events(): List<MemberEventEntity>

    @Query("SELECT * FROM messages ORDER BY id")
    suspend fun messages(): List<MessageEntity>

    @Query("SELECT * FROM templates ORDER BY type")
    suspend fun templates(): List<TemplateEntity>

    @Query("SELECT * FROM broadcasts ORDER BY id")
    suspend fun broadcasts(): List<BroadcastEntity>

    @Query("SELECT * FROM quiet_dates ORDER BY id")
    suspend fun quietDates(): List<QuietDateEntity>

    @Query("SELECT * FROM counters ORDER BY name")
    suspend fun counters(): List<CounterEntity>

    @Insert suspend fun insertPlans(rows: List<PlanEntity>)

    @Insert suspend fun insertMembers(rows: List<MemberEntity>)

    @Insert suspend fun insertPayments(rows: List<PaymentEntity>)

    @Insert suspend fun insertFreezes(rows: List<FreezeEntity>)

    @Insert suspend fun insertEvents(rows: List<MemberEventEntity>)

    @Insert suspend fun insertMessages(rows: List<MessageEntity>)

    @Insert suspend fun insertTemplates(rows: List<TemplateEntity>)

    @Insert suspend fun insertBroadcasts(rows: List<BroadcastEntity>)

    @Insert suspend fun insertQuietDates(rows: List<QuietDateEntity>)

    @Insert suspend fun insertCounters(rows: List<CounterEntity>)

    @Query("DELETE FROM messages") suspend fun clearMessages()

    @Query("DELETE FROM member_events") suspend fun clearEvents()

    @Query("DELETE FROM freezes") suspend fun clearFreezes()

    @Query("DELETE FROM payments") suspend fun clearPayments()

    @Query("DELETE FROM members") suspend fun clearMembers()

    @Query("DELETE FROM plans") suspend fun clearPlans()

    @Query("DELETE FROM templates") suspend fun clearTemplates()

    @Query("DELETE FROM broadcasts") suspend fun clearBroadcasts()

    @Query("DELETE FROM quiet_dates") suspend fun clearQuietDates()

    @Query("DELETE FROM counters") suspend fun clearCounters()

    /**
     * After a restore. These may have gone from this phone after the backup was taken, so
     * sending them again could double-send. Their keys stay, so they aren't planned again either.
     */
    @Query("UPDATE messages SET status = 'CANCELLED', lastError = :reason WHERE status IN ('QUEUED', 'SENDING')")
    suspend fun stopUnsent(reason: String)
}

/** AES-256-GCM with a key from PBKDF2, using built-in crypto only (SPEC 12). */
object BackupCrypto {
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** A fresh IV every time: reusing one with the same key would break GCM. */
    fun encrypt(key: ByteArray, plain: ByteArray, label: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(label)
        return iv to cipher.doFinal(plain)
    }

    /** Null for a wrong password or a file that's been changed. GCM can tell. */
    fun decrypt(key: ByteArray, iv: ByteArray, cipherText: ByteArray, label: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(label)
        cipher.doFinal(cipherText)
    } catch (_: GeneralSecurityException) {
        null
    }
}

/** A folder the backups live in. An interface so tests don't need Android's file picker. */
interface BackupFolder {
    fun isUsable(): Boolean
    fun names(): List<String>
    fun read(name: String): ByteArray?

    /** Replaces a file of the same name. */
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
}

/** Why a backup can't be restored. Checked before anything is touched (SPEC 12). */
object BackupValidator {
    fun problem(data: BackupData): String? {
        val memberIds = data.members.map { it.id }.toSet()
        val planIds = data.plans.map { it.id }.toSet()
        val phones = data.members.mapNotNull { it.activePhone }
        val receipts = data.payments.mapNotNull { it.receiptNo }
        val keys = data.messages.mapNotNull { it.dedupeKey }
        return when {
            memberIds.size != data.members.size -> "Two members share an ID"
            phones.size != phones.toSet().size -> "Two members share a phone number"
            receipts.size != receipts.toSet().size -> "Two payments share a receipt number"
            keys.size != keys.toSet().size -> "A reminder is in the file twice"
            data.members.any { it.planId !in planIds } -> "A member's plan is missing"
            data.payments.any { it.memberId !in memberIds } -> "A payment's member is missing"
            data.payments.any { plan -> plan.planId != null && plan.planId !in planIds } -> "A payment's plan is missing"
            data.freezes.any { it.memberId !in memberIds } -> "A freeze's member is missing"
            data.events.any { it.memberId !in memberIds } -> "A member's history is broken"
            data.messages.any { it.memberId != null && it.memberId !in memberIds } -> "A message's member is missing"
            data.aadhaar.any { it.memberId !in memberIds } -> "An Aadhaar number's member is missing"
            data.templates.isEmpty() -> "The message templates are missing"
            data.counters.isEmpty() -> "The receipt counter is missing"
            else -> null
        }
    }
}
