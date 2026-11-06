package com.swastik.mbfitness.data

import androidx.room.Dao
import androidx.room.Embedded
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

    @Query("SELECT * FROM payments WHERE id = :id")
    fun observe(id: Long): Flow<PaymentEntity?>

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

/** A message with the member's name, for the Messages screen. */
data class MessageWithMember(
    @Embedded val message: MessageEntity,
    val memberName: String?,
)

data class LastPlanned(val memberId: Long, val createdAt: Long)

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

    @Query(
        """
        SELECT msg.*, m.name AS memberName FROM messages msg LEFT JOIN members m ON m.id = msg.memberId
        WHERE msg.status IN (:statuses)
        ORDER BY COALESCE(msg.sentAt, msg.createdAt) DESC, msg.id DESC LIMIT 300
        """,
    )
    fun observeWithStatus(statuses: List<MessageStatus>): Flow<List<MessageWithMember>>

    @Query("SELECT COUNT(*) FROM messages WHERE status IN (:statuses)")
    fun observeCountWithStatus(statuses: List<MessageStatus>): Flow<Int>

    @Query("SELECT * FROM messages WHERE status = :status")
    suspend fun withStatusOnly(status: MessageStatus): List<MessageEntity>

    @Query("SELECT dedupeKey FROM messages WHERE dedupeKey IS NOT NULL AND createdAt >= :since")
    suspend fun usedKeys(since: Long): List<String>

    @Query(
        """
        SELECT memberId, MAX(createdAt) AS createdAt FROM messages
        WHERE memberId IS NOT NULL AND kind IN (:kinds) AND status != 'CANCELLED' AND createdAt >= :since
        GROUP BY memberId
        """,
    )
    suspend fun lastPlannedPerMember(kinds: List<MessageKind>, since: Long): List<LastPlanned>

    @Query("SELECT * FROM messages WHERE status = 'QUEUED' AND kind IN (:kinds) AND createdAt < :before")
    suspend fun queuedPlannedBefore(kinds: List<MessageKind>, before: Long): List<MessageEntity>

    /** For a message being replaced: clearing the key lets its replacement be planned. */
    @Query("UPDATE messages SET status = 'CANCELLED', dedupeKey = NULL, lastError = :reason WHERE id = :id AND status IN ('QUEUED', 'FAILED')")
    suspend fun cancel(id: Long, reason: String)

    /** For a person saying no: keeping the key stops the planner sending the same one again. */
    @Query("UPDATE messages SET status = 'CANCELLED', lastError = :reason WHERE id = :id AND status IN ('QUEUED', 'FAILED')")
    suspend fun cancelForGood(id: Long, reason: String)

    @Query("UPDATE messages SET status = 'SENDING', attempts = :attempt, attemptStartedAt = :startedAt, submittedAt = NULL, resultParts = 0, failedParts = 0, deliveredParts = 0 WHERE id = :id")
    suspend fun markSendingAt(id: Long, attempt: Int, startedAt: Long)

    @Query("UPDATE messages SET parts = :parts, submittedAt = :at WHERE id = :id")
    suspend fun markSubmitted(id: Long, parts: Int, at: Long)

    @Query("UPDATE messages SET status = 'QUEUED', attempts = attempts - 1, lastError = :reason WHERE id = :id")
    suspend fun putBack(id: Long, reason: String)

    @Query("SELECT COUNT(*) FROM messages WHERE status = 'FAILED' AND createdAt >= :since")
    suspend fun countFailedSince(since: Long): Int

    @Query("UPDATE messages SET status = 'CANCELLED', lastError = :reason WHERE broadcastId = :broadcastId AND status = 'QUEUED'")
    suspend fun cancelQueuedBroadcast(broadcastId: Long, reason: String): Int

    @Query("SELECT id FROM messages WHERE broadcastId = :broadcastId AND status = 'FAILED'")
    suspend fun failedInBroadcast(broadcastId: Long): List<Long>

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

    @Query("SELECT * FROM quiet_dates WHERE date = :date")
    suspend fun onDate(date: Long): QuietDateEntity?

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

@Dao
interface SendAttemptDao {
    @Insert
    suspend fun insert(attempt: SendAttemptEntity): Long

    @Query("SELECT * FROM send_attempts WHERE at >= :since")
    suspend fun since(since: Long): List<SendAttemptEntity>

    @Query("UPDATE send_attempts SET parts = :parts WHERE messageId = :messageId AND attempt = :attempt")
    suspend fun setParts(messageId: Long, attempt: Int, parts: Int)

    @Query("DELETE FROM send_attempts WHERE messageId = :messageId AND attempt = :attempt")
    suspend fun remove(messageId: Long, attempt: Int)

    @Query("DELETE FROM send_attempts WHERE at < :before")
    suspend fun purgeBefore(before: Long): Int
}
