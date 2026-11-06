package com.swastik.mbfitness.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.swastik.mbfitness.domain.MemberEventType
import com.swastik.mbfitness.domain.MemberState
import com.swastik.mbfitness.domain.ReportEvent
import kotlinx.coroutines.flow.Flow

/** A member with the plan name and live balance, for lists. */
data class MemberRow(
    val id: Long,
    val name: String,
    val phone: String,
    val planId: Long,
    val planName: String,
    val expiryDate: Long,
    val status: MemberState,
    val balance: Long,
    val smsConsent: Boolean,
    val remindersEnabled: Boolean,
    val dob: Long?,
    val deletedAt: Long?,
)

/** Who the reminder planner looks at, with only the fields it needs (SPEC 9). */
data class PlannerRow(
    val id: Long,
    val name: String,
    val phone: String,
    val status: MemberState,
    val expiryDate: Long,
    val balance: Long,
    val planPrice: Long,
    val planName: String,
    val dob: Long?,
    val smsConsent: Boolean,
    val remindersEnabled: Boolean,
    val lastPaymentId: Long?,
    val lastPaymentOn: Long?,
)

/** Just enough to name a member when reporting a clash. */
data class MemberBrief(val id: Long, val name: String, val phone: String)

/** A member's Aadhaar ciphertext, for checking duplicates without decrypting everyone. */
data class AadhaarCandidate(val id: Long, val name: String, val phone: String, val aadhaarEnc: String?)

private const val ROW_COLUMNS = """
    m.id, m.name, m.phone, m.planId, p.name AS planName, m.expiryDate, m.status,
    COALESCE((SELECT SUM(pay.charged - pay.paid) FROM payments pay
              WHERE pay.memberId = m.id AND pay.deletedAt IS NULL), 0) AS balance,
    m.smsConsent, m.remindersEnabled, m.dob, m.deletedAt
"""

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans ORDER BY isActive DESC, sortOrder, id")
    fun observeAll(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE isActive = 1 ORDER BY sortOrder, id")
    fun observeActive(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans ORDER BY sortOrder, id")
    suspend fun all(): List<PlanEntity>

    @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun get(id: Long): PlanEntity?

    @Query("SELECT * FROM plans WHERE months = :months AND isActive = 1 ORDER BY sortOrder, id LIMIT 1")
    suspend fun activeWithMonths(months: Int): PlanEntity?

    @Query("SELECT months FROM plans WHERE isActive = 1")
    suspend fun activeMonths(): List<Int>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM plans")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(plan: PlanEntity): Long

    @Update
    suspend fun update(plan: PlanEntity)
}

@Dao
interface MemberDao {
    @Insert
    suspend fun insert(member: MemberEntity): Long

    @Update
    suspend fun update(member: MemberEntity)

    @Query("SELECT * FROM members WHERE id = :id")
    suspend fun get(id: Long): MemberEntity?

    @Query("SELECT * FROM members WHERE id = :id")
    fun observe(id: Long): Flow<MemberEntity?>

    @Query("SELECT $ROW_COLUMNS FROM members m JOIN plans p ON p.id = m.planId WHERE m.deletedAt IS NULL ORDER BY m.name COLLATE NOCASE, m.id")
    fun observeRows(): Flow<List<MemberRow>>

    @Query("SELECT $ROW_COLUMNS FROM members m JOIN plans p ON p.id = m.planId WHERE m.deletedAt IS NULL ORDER BY m.id")
    suspend fun rows(): List<MemberRow>

    @Query("SELECT $ROW_COLUMNS FROM members m JOIN plans p ON p.id = m.planId WHERE m.deletedAt IS NOT NULL ORDER BY m.deletedAt DESC")
    fun observeDeletedRows(): Flow<List<MemberRow>>

    @Query(
        """
        SELECT m.id, m.name, m.phone, m.status, m.expiryDate, m.dob, m.smsConsent, m.remindersEnabled,
            p.price AS planPrice, p.name AS planName,
            COALESCE((SELECT SUM(pay.charged - pay.paid) FROM payments pay
                      WHERE pay.memberId = m.id AND pay.deletedAt IS NULL), 0) AS balance,
            (SELECT pay.id FROM payments pay WHERE pay.memberId = m.id AND pay.deletedAt IS NULL
             ORDER BY pay.paidOn DESC, pay.id DESC LIMIT 1) AS lastPaymentId,
            (SELECT pay.paidOn FROM payments pay WHERE pay.memberId = m.id AND pay.deletedAt IS NULL
             ORDER BY pay.paidOn DESC, pay.id DESC LIMIT 1) AS lastPaymentOn
        FROM members m JOIN plans p ON p.id = m.planId
        WHERE m.deletedAt IS NULL AND m.status = 'ACTIVE' AND m.smsConsent = 1 AND m.remindersEnabled = 1
        """,
    )
    suspend fun plannerRows(): List<PlannerRow>

    @Query("SELECT id, name, phone FROM members WHERE activePhone = :phone")
    suspend fun briefByActivePhone(phone: String): MemberBrief?

    @Query("SELECT id, name, phone FROM members WHERE deletedAt IS NULL")
    suspend fun activeBriefs(): List<MemberBrief>

    /** Members who left before [before], going by the latest time they left. */
    @Query(
        """
        SELECT m.id FROM members m
        WHERE m.status = 'LEFT' AND m.deletedAt IS NULL
        AND (SELECT MAX(e.date) FROM member_events e WHERE e.memberId = m.id AND e.type = 'LEFT') < :before
        """,
    )
    fun observeLeftBefore(before: Long): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM members WHERE aadhaarLast4 IS NOT NULL AND deletedAt IS NULL")
    suspend fun countWithAadhaar(): Int

    /** Everyone, the recycle bin included. */
    @Query("SELECT COUNT(*) FROM members")
    suspend fun countAll(): Int

    @Query("SELECT id, name, phone, aadhaarEnc FROM members WHERE deletedAt IS NULL AND aadhaarLast4 = :last4 AND id != :exceptId")
    suspend fun aadhaarCandidates(last4: String, exceptId: Long): List<AadhaarCandidate>

    @Query("SELECT COUNT(*) FROM members WHERE deletedAt IS NULL AND aadhaarLast4 IS NOT NULL")
    suspend fun aadhaarCount(): Int

    /** Only recalculation may call this (hard rule 6). */
    @Query("UPDATE members SET expiryDate = :expiry, updatedAt = :now WHERE id = :id")
    suspend fun setExpiry(id: Long, expiry: Long, now: Long)

    @Query("UPDATE members SET planId = :planId, updatedAt = :now WHERE id = :id")
    suspend fun setPlan(id: Long, planId: Long, now: Long)

    @Query("SELECT id FROM members WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun idsDeletedBefore(cutoff: Long): List<Long>

    @Query("DELETE FROM members WHERE id = :id")
    suspend fun deleteForever(id: Long)
}

@Dao
interface FreezeDao {
    @Insert
    suspend fun insert(freeze: FreezeEntity): Long

    @Query("SELECT * FROM freezes WHERE memberId = :memberId ORDER BY fromDate, id")
    suspend fun forMember(memberId: Long): List<FreezeEntity>

    @Query("SELECT * FROM freezes WHERE memberId = :memberId AND toDate IS NULL LIMIT 1")
    suspend fun openFor(memberId: Long): FreezeEntity?

    @Query("UPDATE freezes SET toDate = :to WHERE id = :id")
    suspend fun close(id: Long, to: Long)
}

@Dao
interface MemberEventDao {
    @Insert
    suspend fun insert(event: MemberEventEntity): Long

    @Query("DELETE FROM member_events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT date FROM member_events WHERE memberId = :memberId AND type = :type ORDER BY date, id")
    suspend fun dates(memberId: Long, type: MemberEventType): List<Long>

    @Query("SELECT * FROM member_events WHERE memberId = :memberId ORDER BY date, id")
    suspend fun forMember(memberId: Long): List<MemberEventEntity>

    @Query("SELECT COUNT(*) FROM member_events WHERE type = :type AND date BETWEEN :from AND :to")
    suspend fun countBetween(type: MemberEventType, from: Long, to: Long): Int

    /** A member in the recycle bin leaves the reports with them, and comes back if restored. */
    @Query(
        """
        SELECT e.memberId, e.type, e.date FROM member_events e JOIN members m ON m.id = e.memberId
        WHERE m.deletedAt IS NULL ORDER BY e.date, e.id
        """,
    )
    fun observeForReports(): Flow<List<ReportEvent>>
}
