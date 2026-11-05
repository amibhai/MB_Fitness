package com.swastik.mbfitness.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.domain.PaymentMode
import kotlinx.coroutines.flow.Flow

/** A payment with the member's name, for Today's closing and Reports. */
data class PaymentRow(
    val id: Long,
    val memberId: Long,
    val memberName: String,
    val receiptNo: Long?,
    val paid: Long,
    val mode: PaymentMode,
    val paidOn: Long,
    val createdAt: Long,
)

@Dao
interface PaymentDao {
    @Insert
    suspend fun insert(payment: PaymentEntity): Long

    @Update
    suspend fun update(payment: PaymentEntity)

    @Query("SELECT * FROM payments WHERE id = :id")
    suspend fun get(id: Long): PaymentEntity?

    /** Soft-deleted payments are left out of everything to do with money (SPEC 7.5). */
    @Query("SELECT * FROM payments WHERE memberId = :memberId AND deletedAt IS NULL ORDER BY paidOn, id")
    suspend fun activeFor(memberId: Long): List<PaymentEntity>

    @Query("SELECT * FROM payments WHERE memberId = :memberId AND deletedAt IS NULL ORDER BY paidOn DESC, id DESC")
    fun observeFor(memberId: Long): Flow<List<PaymentEntity>>

    @Query("UPDATE payments SET periodStart = :start, periodEnd = :end WHERE id = :id")
    suspend fun setPeriod(id: Long, start: Long, end: Long)

    @Query("UPDATE payments SET deletedAt = :at WHERE id = :id")
    suspend fun softDelete(id: Long, at: Long)

    @Query("UPDATE payments SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM payments WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeDeletedBefore(cutoff: Long): Int

    @Query(
        """
        SELECT pay.id, pay.memberId, m.name AS memberName, pay.receiptNo, pay.paid, pay.mode, pay.paidOn, pay.createdAt
        FROM payments pay JOIN members m ON m.id = pay.memberId
        WHERE pay.deletedAt IS NULL AND m.deletedAt IS NULL AND pay.paidOn = :day AND pay.paid > 0
        ORDER BY pay.createdAt, pay.id
        """,
    )
    fun observeOnDay(day: Long): Flow<List<PaymentRow>>

    @Query(
        """
        SELECT COALESCE(SUM(pay.paid), 0) FROM payments pay JOIN members m ON m.id = pay.memberId
        WHERE pay.deletedAt IS NULL AND m.deletedAt IS NULL AND pay.paidOn BETWEEN :from AND :to
        """,
    )
    fun observeCollectedBetween(from: Long, to: Long): Flow<Long>
}

@Dao
interface MessageDao {
    /** Ignored if its dedupe key is taken: the database itself refuses a duplicate reminder. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE memberId = :memberId ORDER BY createdAt DESC, id DESC")
    fun observeFor(memberId: Long): Flow<List<MessageEntity>>

    @Query(
        """
        UPDATE messages SET status = 'CANCELLED', dedupeKey = NULL, lastError = :reason
        WHERE memberId = :memberId AND status = 'QUEUED' AND kind IN (:kinds)
        """,
    )
    suspend fun cancelQueued(memberId: Long, kinds: List<MessageKind>, reason: String): Int

    @Query(
        """
        UPDATE messages SET status = 'CANCELLED', dedupeKey = NULL, lastError = :reason
        WHERE dedupeKey = :key AND status = 'QUEUED'
        """,
    )
    suspend fun cancelQueuedByKey(key: String, reason: String): Int

    @Query("UPDATE messages SET phone = :phone WHERE memberId = :memberId AND status = 'QUEUED'")
    suspend fun setQueuedPhone(memberId: Long, phone: String)

    @Query("SELECT * FROM messages WHERE memberId = :memberId AND status = :status")
    suspend fun withStatus(memberId: Long, status: MessageStatus): List<MessageEntity>

    @Query("DELETE FROM messages WHERE createdAt < :cutoff AND status IN ('SENT', 'DELIVERED', 'FAILED', 'CANCELLED')")
    suspend fun pruneBefore(cutoff: Long): Int
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates")
    suspend fun all(): List<TemplateEntity>

    @Query("SELECT * FROM templates")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates WHERE type = :type")
    suspend fun get(type: MessageKind): TemplateEntity?

    @Upsert
    suspend fun upsert(template: TemplateEntity)
}

@Dao
interface BroadcastDao {
    @Insert
    suspend fun insert(broadcast: BroadcastEntity): Long

    @Query("SELECT * FROM broadcasts ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BroadcastEntity>>
}

@Dao
interface QuietDateDao {
    @Query("SELECT * FROM quiet_dates ORDER BY date")
    suspend fun all(): List<QuietDateEntity>

    @Query("SELECT * FROM quiet_dates WHERE date >= :from ORDER BY date")
    fun observeFrom(from: Long): Flow<List<QuietDateEntity>>

    @Upsert
    suspend fun upsert(date: QuietDateEntity)

    @Query("DELETE FROM quiet_dates WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM quiet_dates WHERE date < :before")
    suspend fun purgeBefore(before: Long): Int
}

@Dao
interface ErrorLogDao {
    @Insert
    suspend fun insert(entry: ErrorLogEntity): Long

    @Query("DELETE FROM error_log WHERE id NOT IN (SELECT id FROM error_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("SELECT * FROM error_log ORDER BY id DESC")
    fun observeAll(): Flow<List<ErrorLogEntity>>
}

@Dao
interface CounterDao {
    @Query("SELECT value FROM counters WHERE name = :name")
    suspend fun value(name: String): Long?

    @Upsert
    suspend fun set(counter: CounterEntity)
}
