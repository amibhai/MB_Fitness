package com.swastik.mbfitness.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.swastik.mbfitness.domain.MemberEventType
import com.swastik.mbfitness.domain.MemberState
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.domain.PaymentKind
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.domain.RenewalRule

/*
 * The tables from SPEC 6. Dates are epoch days and money is whole rupees (hard rule 5), except
 * the ...At audit fields, which are epoch millis. Additions to SPEC 6 are in docs/DECISIONS.md.
 */

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val months: Int,
    val price: Long,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "members",
    foreignKeys = [ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"])],
    indices = [
        Index("status"),
        Index("expiryDate"),
        Index("planId"),
        Index("aadhaarLast4"),
        Index(value = ["activePhone"], unique = true),
    ],
)
data class MemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,
    /**
     * Equal to [phone] until the member is deleted, then null. SQLite lets many rows hold NULL
     * in a UNIQUE column, so this makes the phone unique among non-deleted members (SPEC 6).
     */
    val activePhone: String?,
    val joinDate: Long,
    val planId: Long,
    /** A cache of MemberRules.replay. Only recalculation may write this (hard rule 6). */
    val expiryDate: Long,
    val status: MemberState = MemberState.ACTIVE,
    val dob: Long? = null,
    val notes: String = "",
    val smsConsent: Boolean = false,
    val consentDate: Long? = null,
    val remindersEnabled: Boolean = true,
    val isMinor: Boolean = false,
    val guardianName: String? = null,
    val guardianPhone: String? = null,
    /** AES-256-GCM ciphertext from AadhaarVault. There is no plaintext column (hard rule 11). */
    val aadhaarEnc: String? = null,
    val aadhaarLast4: String? = null,
    val aadhaarConsentDate: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "payments",
    foreignKeys = [
        ForeignKey(
            entity = MemberEntity::class,
            parentColumns = ["id"],
            childColumns = ["memberId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"]),
    ],
    indices = [
        Index("memberId", "paidOn"),
        Index("paidOn"),
        Index("planId"),
        Index(value = ["receiptNo"], unique = true),
    ],
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null only for OPENING rows, which record a starting balance rather than money received. */
    val receiptNo: Long?,
    val memberId: Long,
    val kind: PaymentKind,
    val charged: Long,
    val paid: Long,
    val paidOn: Long,
    val mode: PaymentMode,
    val planId: Long? = null,
    /** Saved when recorded, so editing the plan or settings can't rewrite this payment. */
    val planMonths: Int? = null,
    val ruleApplied: RenewalRule? = null,
    val restartAfterDays: Int? = null,
    /** Written by recalculation. For OPENING, [periodEnd] is the date from the import file. */
    val periodStart: Long? = null,
    val periodEnd: Long? = null,
    val note: String = "",
    val createdAt: Long,
    val editedAt: Long? = null,
    /** A JSON list of [PaymentVersion], oldest first. */
    val editHistory: String = "[]",
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "freezes",
    foreignKeys = [
        ForeignKey(
            entity = MemberEntity::class,
            parentColumns = ["id"],
            childColumns = ["memberId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("memberId")],
)
data class FreezeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memberId: Long,
    val fromDate: Long,
    val toDate: Long? = null,
)

@Entity(
    tableName = "member_events",
    foreignKeys = [
        ForeignKey(
            entity = MemberEntity::class,
            parentColumns = ["id"],
            childColumns = ["memberId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("memberId"), Index("type", "date")],
)
data class MemberEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memberId: Long,
    val type: MemberEventType,
    val date: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = MemberEntity::class,
            parentColumns = ["id"],
            childColumns = ["memberId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("status", "notBefore"),
        Index("memberId", "kind", "forExpiry"),
        Index("sentAt"),
        Index("broadcastId"),
        Index(value = ["dedupeKey"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memberId: Long?,
    val phone: String,
    val kind: MessageKind,
    val body: String,
    val parts: Int,
    val priority: Int,
    val status: MessageStatus = MessageStatus.QUEUED,
    val forExpiry: Long? = null,
    /** See DedupeKeys. Cleared on cancel, so a cancelled message can be planned again. */
    val dedupeKey: String? = null,
    val broadcastId: Long? = null,
    val createdAt: Long,
    val notBefore: Long,
    /** Set right after the radio accepts it, to tell "never sent" from "result unknown". */
    val submittedAt: Long? = null,
    val sentAt: Long? = null,
    val deliveredAt: Long? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val type: MessageKind,
    val body: String,
    val enabled: Boolean = true,
    val offsetDays: Int = 0,
    val repeatEveryDays: Int? = null,
    val maxRepeats: Int? = null,
)

@Entity(tableName = "broadcasts")
data class BroadcastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val body: String,
    val audience: String,
    val createdAt: Long,
    val recipientCount: Int,
)

@Entity(tableName = "quiet_dates", indices = [Index(value = ["date"], unique = true)])
data class QuietDateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val label: String,
)

@Entity(tableName = "error_log", indices = [Index("at")])
data class ErrorLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val tag: String,
    val message: String,
)

/**
 * Named counters, updated inside the same transaction as the row that uses them. The next
 * receipt number lives here rather than in DataStore (docs/DECISIONS.md).
 */
@Entity(tableName = "counters")
data class CounterEntity(
    @PrimaryKey val name: String,
    val value: Long,
)
