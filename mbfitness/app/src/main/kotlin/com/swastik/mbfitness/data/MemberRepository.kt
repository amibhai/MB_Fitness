package com.swastik.mbfitness.data

import androidx.room.withTransaction
import com.swastik.mbfitness.domain.Aadhaar
import com.swastik.mbfitness.domain.AfterPayment
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.DedupeKeys
import com.swastik.mbfitness.domain.FreezeSpan
import com.swastik.mbfitness.domain.ImportRow
import com.swastik.mbfitness.domain.LedgerPayment
import com.swastik.mbfitness.domain.MemberEventType
import com.swastik.mbfitness.domain.MemberRules
import com.swastik.mbfitness.domain.MemberState
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.PaymentKind
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.ReceiptNumbers
import com.swastik.mbfitness.domain.ReplayResult
import com.swastik.mbfitness.domain.ReportEvent
import com.swastik.mbfitness.domain.ReportPayment
import com.swastik.mbfitness.domain.SmsSegments
import com.swastik.mbfitness.domain.TemplateEngine
import com.swastik.mbfitness.domain.TemplateValues
import com.swastik.mbfitness.domain.TodayProvider
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A previous version of a payment, kept for disputes (SPEC 7.5). */
@Serializable
data class PaymentVersion(
    val charged: Long,
    val paid: Long,
    val paidOn: Long,
    val mode: PaymentMode,
    val planId: Long?,
    val planMonths: Int?,
    val note: String,
    val replacedAt: Long,
)

/** A payment's earlier versions. If they can't be read, the payment can still be shown and corrected. */
fun paymentHistory(json: String): List<PaymentVersion> = try {
    Json.decodeFromString<List<PaymentVersion>>(json)
} catch (_: SerializationException) {
    emptyList()
} catch (_: IllegalArgumentException) {
    emptyList()
}

/** A new member, as entered. [aadhaar] is only accepted with [aadhaarConsent] ticked. */
data class MemberDraft(
    val name: String,
    val phone: String,
    val planId: Long,
    val joinDate: Long,
    val dob: Long? = null,
    val notes: String = "",
    val smsConsent: Boolean = false,
    val isMinor: Boolean = false,
    val guardianName: String? = null,
    val guardianPhone: String? = null,
    val aadhaar: Aadhaar? = null,
    val aadhaarConsent: Boolean = false,
    val firstPayment: FirstPayment? = null,
)

/** Money paid at joining. With no [paidOn], it's dated by MemberRules.joiningPaymentDate. */
data class FirstPayment(val received: Long, val mode: PaymentMode, val note: String = "", val paidOn: Long? = null)

/** Editable details. The Aadhaar has its own calls, and expiry only ever comes from a replay. */
data class MemberEdit(
    val name: String,
    val phone: String,
    val planId: Long,
    val joinDate: Long,
    val dob: Long?,
    val notes: String,
    val isMinor: Boolean,
    val guardianName: String?,
    val guardianPhone: String?,
)

sealed interface MemberSaveResult {
    data class Saved(
        val memberId: Long,
        val payment: PaymentSaved? = null,
        /** False if a number was given but the phone's Keystore refused to encrypt it. */
        val aadhaarStored: Boolean = true,
        val welcomeQueued: Boolean = false,
    ) : MemberSaveResult

    data object NameMissing : MemberSaveResult
    data object PhoneInvalid : MemberSaveResult
    data class PhoneTaken(val existing: MemberBrief) : MemberSaveResult
    data object AadhaarConsentMissing : MemberSaveResult
}

data class RenewalRequest(
    val memberId: Long,
    val planId: Long,
    val received: Long,
    val mode: PaymentMode,
    val paidOn: Long,
    val note: String = "",
)

data class BalanceRequest(
    val memberId: Long,
    val received: Long,
    val mode: PaymentMode,
    val paidOn: Long,
    val note: String = "",
)

data class PaymentEdit(
    val charged: Long,
    val paid: Long,
    val paidOn: Long,
    val mode: PaymentMode,
    val note: String,
    val planId: Long? = null,
    /** OPENING rows only: the expiry date from the import file. */
    val openingExpiry: Long? = null,
)

/** The result of a payment, with what the snackbar and its Undo need (SPEC 7.3). */
data class PaymentSaved(
    val paymentId: Long,
    val memberId: Long,
    val receipt: String?,
    val paid: Long,
    val mode: PaymentMode,
    val newExpiry: Long,
    val balance: Long,
    val thanksQueued: Boolean,
    /** Set when this payment brought back a member who had left, so Undo can reverse it. */
    val rejoinEventId: Long?,
)

data class ExpiryChange(val before: Long, val after: Long)

data class RenewalPreview(
    val newExpiry: Long,
    val periodStart: Long,
    /** True when the period starts from the payment date rather than the old expiry. */
    val startsFresh: Boolean,
    val defaultReceived: Long,
    val after: AfterPayment,
)

sealed interface RestoreResult {
    data object Restored : RestoreResult
    data class PhoneTaken(val existing: MemberBrief) : RestoreResult
}

data class ImportResult(
    val imported: Int,
    val aadhaarStored: Int,
    val aadhaarFailed: Int,
    /** Names of imported members whose number is already on another member. */
    val aadhaarDuplicates: List<String>,
)

/**
 * Members and their money. Every change that touches money is one Room transaction and ends
 * with [recalculate], so expiry can never drift from the payment records (hard rule 6).
 * Business decisions are left to domain/ (hard rule 4).
 */
class MemberRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val vault: AadhaarVault,
    private val clock: TodayProvider,
    private val onMessagesQueued: () -> Unit = {},
) {
    private val members get() = db.members()
    private val plans get() = db.plans()
    private val payments get() = db.payments()
    private val freezes get() = db.freezes()
    private val events get() = db.events()
    private val messages get() = db.messages()

    fun observeMembers(): Flow<List<MemberRow>> = members.observeRows()
    fun observeMember(id: Long): Flow<MemberEntity?> = members.observe(id)
    fun observePayments(memberId: Long): Flow<List<PaymentEntity>> = payments.observeFor(memberId)
    fun observeMessages(memberId: Long): Flow<List<MessageEntity>> = messages.observeFor(memberId)
    fun observeRecycleBin(): Flow<List<MemberRow>> = members.observeDeletedRows()
    fun observePlans(): Flow<List<PlanEntity>> = plans.observeAll()
    fun observeActivePlans(): Flow<List<PlanEntity>> = plans.observeActive()
    fun observePaymentsOn(day: Long): Flow<List<PaymentRow>> = payments.observeOnDay(day)
    fun observePayment(id: Long): Flow<PaymentEntity?> = payments.observe(id)

    suspend fun member(id: Long): MemberEntity? = members.get(id)
    suspend fun plan(id: Long): PlanEntity? = plans.get(id)
    suspend fun balanceOf(memberId: Long): Long =
        MemberRules.balance(payments.activeFor(memberId).map { it.toLedger() })

    /** A phone number already on another member, to warn before saving. */
    suspend fun ownerOfPhone(phone: String, exceptMemberId: Long = 0): MemberBrief? =
        PhoneNumbers.normalize(phone)?.let { members.briefByActivePhone(it) }?.takeIf { it.id != exceptMemberId }

    fun observeCollectedThisMonth(): Flow<Long> {
        val date = LocalDate.ofEpochDay(clock.today())
        return payments.observeCollectedBetween(date.withDayOfMonth(1).toEpochDay(), date.toEpochDay())
    }

    // ---- Members ----

    suspend fun addMember(draft: MemberDraft): MemberSaveResult {
        val name = draft.name.trim()
        if (name.isEmpty()) return MemberSaveResult.NameMissing
        val phone = PhoneNumbers.normalize(draft.phone) ?: return MemberSaveResult.PhoneInvalid
        if (draft.aadhaar != null && !draft.aadhaarConsent) return MemberSaveResult.AadhaarConsentMissing

        // Keystore work is slow, so it happens before the transaction opens.
        val cipherText = draft.aadhaar?.let { seal(it) }
        val appSettings = settings.current()
        val now = clock.nowMillis()
        val today = clock.today()

        val result = db.withTransaction {
            members.briefByActivePhone(phone)?.let { return@withTransaction MemberSaveResult.PhoneTaken(it) }
            val memberId = members.insert(
                MemberEntity(
                    name = name,
                    phone = phone,
                    activePhone = phone,
                    joinDate = draft.joinDate,
                    planId = draft.planId,
                    expiryDate = draft.joinDate,
                    dob = draft.dob,
                    notes = draft.notes.trim(),
                    smsConsent = draft.smsConsent,
                    consentDate = today.takeIf { draft.smsConsent },
                    isMinor = draft.isMinor,
                    guardianName = draft.guardianName?.trim()?.takeIf { draft.isMinor },
                    guardianPhone = draft.guardianPhone?.takeIf { draft.isMinor }?.let(PhoneNumbers::normalize),
                    aadhaarEnc = cipherText,
                    aadhaarLast4 = draft.aadhaar?.last4?.takeIf { cipherText != null },
                    aadhaarConsentDate = today.takeIf { cipherText != null },
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            events.insert(MemberEventEntity(memberId = memberId, type = MemberEventType.JOINED, date = draft.joinDate))
            // Queued before the payment, so it goes just ahead of the thank-you.
            val welcomeQueued = queueWelcome(memberId, appSettings, now)
            val first = draft.firstPayment?.let { payment ->
                val plan = requirePlan(draft.planId)
                // Paid at joining, so dated the join date, not the day it was typed in (1.0.2).
                val paidOn = payment.paidOn ?: MemberRules.joiningPaymentDate(draft.joinDate, today)
                insertRenewal(memberId, plan, payment.received, payment.mode, paidOn, payment.note, appSettings, now, rejoinEventId = null)
            }
            if (first == null) recalculate(memberId)
            MemberSaveResult.Saved(memberId, first, aadhaarStored = draft.aadhaar == null || cipherText != null, welcomeQueued = welcomeQueued)
        }
        if (result is MemberSaveResult.Saved && (result.payment?.thanksQueued == true || result.welcomeQueued)) onMessagesQueued()
        return result
    }

    suspend fun updateMember(id: Long, edit: MemberEdit): MemberSaveResult {
        val name = edit.name.trim()
        if (name.isEmpty()) return MemberSaveResult.NameMissing
        val phone = PhoneNumbers.normalize(edit.phone) ?: return MemberSaveResult.PhoneInvalid
        val now = clock.nowMillis()
        val today = clock.today()
        return db.withTransaction {
            val member = requireMember(id)
            members.briefByActivePhone(phone)?.takeIf { it.id != id }?.let {
                return@withTransaction MemberSaveResult.PhoneTaken(it)
            }
            val joinDateChanged = edit.joinDate != member.joinDate
            members.update(
                member.copy(
                    name = name,
                    phone = phone,
                    activePhone = phone.takeIf { member.deletedAt == null },
                    planId = edit.planId,
                    joinDate = edit.joinDate,
                    dob = edit.dob,
                    notes = edit.notes.trim(),
                    isMinor = edit.isMinor,
                    guardianName = edit.guardianName?.trim()?.takeIf { edit.isMinor },
                    guardianPhone = edit.guardianPhone?.takeIf { edit.isMinor }?.let(PhoneNumbers::normalize),
                    updatedAt = now,
                ),
            )
            if (joinDateChanged) moveJoinDate(member, edit.joinDate, today, now)
            if (phone != member.phone) messages.setQueuedPhone(id, phone)
            // A queued reminder may quote the old name or expiry, so let the planner write it again.
            if (name != member.name || joinDateChanged) messages.cancelQueued(id, plannedKinds, "Member details changed")
            recalculate(id)
            MemberSaveResult.Saved(id)
        }
    }

    /**
     * Reports count joins by the JOINED event, so it moves with the join date. So does the payment
     * made on Add member, if it was dated on the old join date: one given its own date stays put.
     */
    private suspend fun moveJoinDate(member: MemberEntity, newJoinDate: Long, today: Long, now: Long) {
        events.moveJoined(member.id, newJoinDate)
        val joining = payments.joiningPayment(member.id, member.createdAt) ?: return
        val paidOn = MemberRules.movedJoiningPaymentDate(joining.paidOn, member.joinDate, newJoinDate, today) ?: return
        payments.update(joining.copy(paidOn = paidOn, editedAt = now, editHistory = historyWith(joining, now)))
    }

    /** The payment made on Add member, while it's still there. The edit form says if it will move. */
    suspend fun joiningPayment(memberId: Long): PaymentEntity? =
        members.get(memberId)?.let { payments.joiningPayment(memberId, it.createdAt) }

    /**
     * Run on every app open. The first time, it notes when, and only members created before then
     * are ever looked at. The time is kept in the database rather than the settings file, so it
     * travels with the members in a backup and a damaged settings file can't move it.
     */
    suspend fun fixOldJoiningPaymentDates(): Int {
        val counters = db.counters()
        val cutoff = db.withTransaction {
            counters.value(AppDatabase.JOINING_FIX_CUTOFF)
                ?: clock.nowMillis().also { counters.set(CounterEntity(AppDatabase.JOINING_FIX_CUTOFF, it)) }
        }
        return fixOldJoiningPaymentDates(createdBefore = cutoff)
    }

    /**
     * Version 1.0.1 dated a new member's first payment the day they were entered, not their join
     * date. This moves it to the join date, for members created before [createdBefore] (when 1.0.2
     * first opened), but only where nothing the reminders look at changes: the same expiry, and no
     * balance reminder timed from it. The rest stay exactly as they are. A moved payment keeps
     * its old date in its edit history, which also stops it being picked again. Returns how many.
     */
    suspend fun fixOldJoiningPaymentDates(createdBefore: Long): Int =
        payments.joiningPaymentsDatedAfterJoining(createdBefore).count { candidate ->
            val now = clock.nowMillis()
            db.withTransaction {
                val payment = payments.get(candidate.id)?.takeIf { it.editedAt == null && it.deletedAt == null }
                    ?: return@withTransaction false
                val member = requireMember(payment.memberId)
                // 1.0.1 dated it the day the member was entered. Anything else was chosen on purpose.
                if (payment.paidOn != Dates.dayOf(member.createdAt) || payment.paidOn <= member.joinDate) return@withTransaction false
                val ledger = payments.activeFor(member.id)
                val moved = ledger.map { if (it.id == payment.id) it.copy(paidOn = member.joinDate) else it }
                val safe = MemberRules.movingJoiningPaymentIsSafe(
                    expiryNow = replayFor(member, ledger.map { it.toLedger() }).expiry,
                    expiryAfter = replayFor(member, moved.map { it.toLedger() }).expiry,
                    isLatestPayment = ledger.lastOrNull()?.id == payment.id,
                    balance = MemberRules.balance(ledger.map { it.toLedger() }),
                )
                if (!safe) return@withTransaction false
                payments.update(payment.copy(paidOn = member.joinDate, editedAt = now, editHistory = historyWith(payment, now)))
                recalculate(member.id)
                true
            }
        }

    suspend fun setSmsConsent(id: Long, agreed: Boolean) {
        val today = clock.today()
        val now = clock.nowMillis()
        db.withTransaction {
            val member = requireMember(id)
            members.update(member.copy(smsConsent = agreed, consentDate = today.takeIf { agreed }, updatedAt = now))
            if (!agreed) messages.cancelQueued(id, MessageKind.entries, "SMS consent removed")
        }
    }

    suspend fun setRemindersEnabled(id: Long, enabled: Boolean) {
        val now = clock.nowMillis()
        db.withTransaction {
            val member = requireMember(id)
            members.update(member.copy(remindersEnabled = enabled, updatedAt = now))
            if (!enabled) messages.cancelQueued(id, plannedKinds, "Reminders turned off")
        }
    }

    /** "Don't message me": one tap stops everything, including what is already queued (SPEC 8.9). */
    suspend fun stopSms(id: Long) {
        val now = clock.nowMillis()
        db.withTransaction {
            val member = requireMember(id)
            members.update(member.copy(smsConsent = false, consentDate = null, remindersEnabled = false, updatedAt = now))
            messages.cancelQueued(id, MessageKind.entries, "Member asked to stop SMS")
        }
    }

    suspend fun freeze(id: Long) {
        val today = clock.today()
        val now = clock.nowMillis()
        db.withTransaction {
            val member = requireMember(id)
            if (member.status != MemberState.ACTIVE) return@withTransaction
            freezes.insert(FreezeEntity(memberId = id, fromDate = today))
            members.update(member.copy(status = MemberState.FROZEN, updatedAt = now))
            events.insert(MemberEventEntity(memberId = id, type = MemberEventType.FROZEN, date = today))
            messages.cancelQueued(id, plannedKinds, "Member frozen")
            recalculate(id)
        }
    }

    suspend fun unfreeze(id: Long): ExpiryChange? {
        val today = clock.today()
        val now = clock.nowMillis()
        return db.withTransaction {
            val member = requireMember(id)
            val open = freezes.openFor(id)
            if (open == null && member.status != MemberState.FROZEN) return@withTransaction null
            open?.let { freezes.close(it.id, today) }
            members.update(member.copy(status = MemberState.ACTIVE, updatedAt = now))
            events.insert(MemberEventEntity(memberId = id, type = MemberEventType.UNFROZEN, date = today))
            ExpiryChange(member.expiryDate, recalculate(id).expiry)
        }
    }

    suspend fun markLeft(id: Long, removeAadhaar: Boolean = false) {
        val today = clock.today()
        val now = clock.nowMillis()
        db.withTransaction {
            val member = requireMember(id)
            freezes.openFor(id)?.let { freezes.close(it.id, today) }
            val updated = member.copy(status = MemberState.LEFT, updatedAt = now)
            members.update(if (removeAadhaar) updated.withoutAadhaar() else updated)
            events.insert(MemberEventEntity(memberId = id, type = MemberEventType.LEFT, date = today))
            messages.cancelQueued(id, plannedKinds, "Member left")
            recalculate(id)
        }
    }

    /** Back to ACTIVE. The next payment starts its period from the payment date (SPEC 7.7). */
    suspend fun rejoin(id: Long) {
        val today = clock.today()
        val now = clock.nowMillis()
        db.withTransaction {
            val member = requireMember(id)
            if (member.status != MemberState.LEFT) return@withTransaction
            members.update(member.copy(status = MemberState.ACTIVE, updatedAt = now))
            events.insert(MemberEventEntity(memberId = id, type = MemberEventType.REJOINED, date = today))
            recalculate(id)
        }
    }

    /** Hidden everywhere, kept in the recycle bin for 30 days (SPEC 7.7). */
    suspend fun deleteMember(id: Long) {
        val today = clock.today()
        val now = clock.nowMillis()
        db.withTransaction {
            val member = requireMember(id)
            members.update(member.copy(activePhone = null, deletedAt = now, updatedAt = now))
            events.insert(MemberEventEntity(memberId = id, type = MemberEventType.DELETED, date = today))
            messages.cancelQueued(id, MessageKind.entries, "Member deleted")
        }
    }

    suspend fun restoreMember(id: Long): RestoreResult {
        val today = clock.today()
        val now = clock.nowMillis()
        return db.withTransaction {
            val member = requireMember(id)
            members.briefByActivePhone(member.phone)?.let { return@withTransaction RestoreResult.PhoneTaken(it) }
            members.update(member.copy(activePhone = member.phone, deletedAt = null, updatedAt = now))
            events.insert(MemberEventEntity(memberId = id, type = MemberEventType.RESTORED, date = today))
            recalculate(id)
            RestoreResult.Restored
        }
    }

    // ---- Aadhaar (SPEC 7.10). Never log any of this (hard rule 11). ----

    /** Returns false if the Keystore refused, in which case nothing changed. */
    suspend fun setAadhaar(id: Long, aadhaar: Aadhaar): Boolean {
        val cipherText = seal(aadhaar) ?: return false
        val today = clock.today()
        val now = clock.nowMillis()
        db.withTransaction {
            val member = requireMember(id)
            members.update(
                member.copy(aadhaarEnc = cipherText, aadhaarLast4 = aadhaar.last4, aadhaarConsentDate = today, updatedAt = now),
            )
        }
        return true
    }

    suspend fun removeAadhaar(id: Long) {
        val now = clock.nowMillis()
        db.withTransaction {
            members.update(requireMember(id).withoutAadhaar().copy(updatedAt = now))
        }
    }

    /** Null when the number can't be read on this phone (SPEC 7.10). */
    suspend fun revealAadhaar(id: Long): Aadhaar? {
        val cipherText = members.get(id)?.aadhaarEnc ?: return null
        val digits = unseal(cipherText) ?: return null
        return (Aadhaar.parse(digits) as? Aadhaar.ParseResult.Valid)?.aadhaar
    }

    /** Candidates are narrowed by last 4 digits first, so only a few are ever decrypted. */
    suspend fun findAadhaarOwner(aadhaar: Aadhaar, exceptMemberId: Long = 0): MemberBrief? =
        members.aadhaarCandidates(aadhaar.last4, exceptMemberId)
            .firstOrNull { candidate ->
                candidate.aadhaarEnc?.let { unseal(it) } == aadhaar.revealDigits()
            }
            ?.let { MemberBrief(it.id, it.name, it.phone) }

    /** Keystore work is slow and a big import does hundreds of them, so it stays off the main thread. */
    private suspend fun seal(aadhaar: Aadhaar): String? = withContext(Dispatchers.IO) { vault.encrypt(aadhaar.revealDigits()) }

    private suspend fun unseal(cipherText: String): String? = withContext(Dispatchers.IO) { vault.decrypt(cipherText) }

    private fun MemberEntity.withoutAadhaar() =
        copy(aadhaarEnc = null, aadhaarLast4 = null, aadhaarConsentDate = null)

    // ---- Payments ----

    suspend fun recordRenewal(request: RenewalRequest): PaymentSaved {
        val appSettings = settings.current()
        val now = clock.nowMillis()
        val saved = db.withTransaction {
            val member = requireMember(request.memberId)
            val plan = requirePlan(request.planId)
            // Paying for someone who left brings them back (SPEC 7.3).
            val rejoinEventId = if (member.status == MemberState.LEFT) {
                members.update(member.copy(status = MemberState.ACTIVE, updatedAt = now))
                events.insert(MemberEventEntity(memberId = member.id, type = MemberEventType.REJOINED, date = request.paidOn))
            } else {
                null
            }
            if (plan.id != member.planId) members.setPlan(member.id, plan.id, now)
            insertRenewal(member.id, plan, request.received, request.mode, request.paidOn, request.note, appSettings, now, rejoinEventId)
        }
        if (saved.thanksQueued) onMessagesQueued()
        return saved
    }

    suspend fun recordBalance(request: BalanceRequest): PaymentSaved {
        val appSettings = settings.current()
        val now = clock.nowMillis()
        val saved = db.withTransaction {
            requireMember(request.memberId)
            val paymentId = payments.insert(
                PaymentEntity(
                    receiptNo = nextReceiptNumber(),
                    memberId = request.memberId,
                    kind = PaymentKind.BALANCE,
                    charged = 0,
                    paid = request.received,
                    paidOn = request.paidOn,
                    mode = request.mode,
                    note = request.note,
                    createdAt = now,
                ),
            )
            finishPayment(request.memberId, paymentId, request.received, request.mode, appSettings, now, rejoinEventId = null)
        }
        if (saved.thanksQueued) onMessagesQueued()
        return saved
    }

    suspend fun previewRenewal(memberId: Long, planId: Long, paidOn: Long, received: Long? = null): RenewalPreview {
        val appSettings = settings.current()
        val member = requireMember(memberId)
        val plan = requirePlan(planId)
        val ledger = payments.activeFor(memberId).map { it.toLedger() }
        val balance = MemberRules.balance(ledger)
        val defaultReceived = MemberRules.defaultReceived(plan.price, balance)
        val amount = received ?: defaultReceived
        val candidate = LedgerPayment(
            id = Long.MAX_VALUE,
            kind = PaymentKind.RENEWAL,
            charged = plan.price,
            paid = amount,
            paidOn = paidOn,
            planMonths = plan.months,
            rule = appSettings.renewalRule,
            restartAfterDays = appSettings.restartAfterDays,
        )
        val leftNow = member.status == MemberState.LEFT
        val result = replayFor(member, ledger + candidate, extraRejoin = paidOn.takeIf { leftNow })
        val period = result.periods.last()
        return RenewalPreview(
            newExpiry = result.expiry,
            periodStart = period.start,
            startsFresh = period.start == paidOn && period.start != member.expiryDate,
            defaultReceived = defaultReceived,
            after = MemberRules.afterPayment(balance, plan.price, amount),
        )
    }

    /** Keeps the old values in the edit history, then works expiry out again (SPEC 7.5). */
    suspend fun editPayment(paymentId: Long, edit: PaymentEdit): ExpiryChange {
        val now = clock.nowMillis()
        return db.withTransaction {
            val old = requirePayment(paymentId)
            val member = requireMember(old.memberId)
            val newPlan = edit.planId?.takeIf { it != old.planId }?.let { requirePlan(it) }
            payments.update(
                old.copy(
                    charged = edit.charged,
                    paid = edit.paid,
                    paidOn = edit.paidOn,
                    mode = edit.mode,
                    note = edit.note,
                    planId = newPlan?.id ?: old.planId,
                    planMonths = newPlan?.months ?: old.planMonths,
                    periodEnd = if (old.kind == PaymentKind.OPENING) edit.openingExpiry ?: old.periodEnd else old.periodEnd,
                    editedAt = now,
                    editHistory = historyWith(old, now),
                ),
            )
            ExpiryChange(member.expiryDate, recalculate(member.id).expiry)
        }
    }

    /** The payment's edit history with its current values added at the end (SPEC 7.5). */
    private fun historyWith(old: PaymentEntity, now: Long): String = Json.encodeToString(
        paymentHistory(old.editHistory) + PaymentVersion(
            old.charged, old.paid, old.paidOn, old.mode, old.planId, old.planMonths, old.note, now,
        ),
    )

    /** What deleting would do, for the confirmation: "expiry changes from 12 Dec to 12 Nov". */
    suspend fun previewDeletePayment(paymentId: Long): ExpiryChange {
        val payment = requirePayment(paymentId)
        val member = requireMember(payment.memberId)
        val remaining = payments.activeFor(member.id).filter { it.id != paymentId }.map { it.toLedger() }
        return ExpiryChange(member.expiryDate, replayFor(member, remaining).expiry)
    }

    suspend fun deletePayment(paymentId: Long): ExpiryChange {
        val now = clock.nowMillis()
        return db.withTransaction {
            val payment = requirePayment(paymentId)
            val member = requireMember(payment.memberId)
            payments.softDelete(paymentId, now)
            messages.cancelQueuedByKey(DedupeKeys.thanks(member.id, paymentId), "Payment deleted")
            ExpiryChange(member.expiryDate, recalculate(member.id).expiry)
        }
    }

    suspend fun restorePayment(paymentId: Long): ExpiryChange {
        return db.withTransaction {
            val payment = requirePayment(paymentId)
            val member = requireMember(payment.memberId)
            payments.restore(paymentId)
            messages.cancelQueued(member.id, feeReminderKinds, "Payment restored")
            ExpiryChange(member.expiryDate, recalculate(member.id).expiry)
        }
    }

    /**
     * The snackbar's Undo. The receipt number stays used, because numbers are never reused.
     * If the payment had brought a member back, they go back to having left.
     */
    suspend fun undoPayment(saved: PaymentSaved) {
        val now = clock.nowMillis()
        db.withTransaction {
            payments.softDelete(saved.paymentId, now)
            messages.cancelQueuedByKey(DedupeKeys.thanks(saved.memberId, saved.paymentId), "Payment undone")
            saved.rejoinEventId?.let { eventId ->
                events.delete(eventId)
                members.update(requireMember(saved.memberId).copy(status = MemberState.LEFT, updatedAt = now))
            }
            recalculate(saved.memberId)
        }
    }

    // ---- Plans ----

    suspend fun addPlan(name: String, months: Int, price: Long): Long = plans.insert(
        PlanEntity(name = name.trim(), months = months, price = price, sortOrder = plans.maxSortOrder() + 1),
    )

    /** Payments saved their own length, so this can't change anyone's expiry. */
    suspend fun updatePlan(plan: PlanEntity) = plans.update(plan)

    /** Retired plans stay on old payments, so a plan is never deleted (SPEC 10.2 screen 15). */
    suspend fun setPlanActive(id: Long, active: Boolean) {
        plans.update(requirePlan(id).copy(isActive = active))
    }

    // ---- Import (SPEC 7.9) ----

    suspend fun activePlanMonths(): List<Int> = plans.activeMonths()

    fun observeReportPayments(): Flow<List<ReportPayment>> = payments.observeForReports()

    fun observeReportEvents(): Flow<List<ReportEvent>> = events.observeForReports()

    /** Members who left over 3 years ago, for the "remove old records?" suggestion (SPEC 12). */
    fun observeLongGone(): Flow<List<Long>> = members.observeLeftBefore(Dates.addMonths(clock.today(), -OLD_RECORDS_MONTHS))

    suspend fun existingPhones(): Map<String, String> =
        members.activeBriefs().associate { it.phone to it.name }

    /** All or nothing: one transaction, so a failure can't leave half a file imported. */
    suspend fun importMembers(rows: List<ImportRow>, smsConsent: Boolean): ImportResult {
        val encrypted = rows.associate { row -> row.line to row.aadhaar?.let { seal(it) } }
        val owners = rows.mapNotNull { row ->
            row.aadhaar?.let { aadhaar -> findAadhaarOwner(aadhaar)?.let { row.name } }
        }
        val now = clock.nowMillis()
        val today = clock.today()
        db.withTransaction {
            for (row in rows) {
                val plan = requireNotNull(plans.activeWithMonths(row.planMonths)) { "No active ${row.planMonths}-month plan" }
                val cipherText = encrypted[row.line]
                val memberId = members.insert(
                    MemberEntity(
                        name = row.name,
                        phone = row.phone,
                        activePhone = row.phone,
                        joinDate = today,
                        planId = plan.id,
                        expiryDate = row.expiry,
                        dob = row.dob,
                        notes = row.notes,
                        smsConsent = smsConsent,
                        consentDate = today.takeIf { smsConsent },
                        aadhaarEnc = cipherText,
                        aadhaarLast4 = row.aadhaar?.last4?.takeIf { cipherText != null },
                        aadhaarConsentDate = today.takeIf { cipherText != null },
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                events.insert(MemberEventEntity(memberId = memberId, type = MemberEventType.IMPORTED, date = today))
                payments.insert(
                    PaymentEntity(
                        receiptNo = null,
                        memberId = memberId,
                        kind = PaymentKind.OPENING,
                        charged = row.balanceDue,
                        paid = 0,
                        paidOn = today,
                        mode = PaymentMode.OTHER,
                        planId = plan.id,
                        planMonths = plan.months,
                        periodEnd = row.expiry,
                        createdAt = now,
                    ),
                )
                recalculate(memberId)
            }
        }
        val given = rows.count { it.aadhaar != null }
        val stored = encrypted.values.count { it != null }
        return ImportResult(rows.size, stored, given - stored, owners)
    }

    // ---- Housekeeping ----

    /** Old data leaves on a schedule (SPEC 7.5, 7.7, 12). Called once a day. */
    suspend fun housekeeping() {
        val now = clock.nowMillis()
        val today = clock.today()
        db.withTransaction {
            val cutoff = now - RECYCLE_BIN_MILLIS
            // Cascades take the payments, freezes and messages, and the Aadhaar goes with the row.
            members.idsDeletedBefore(cutoff).forEach { members.deleteForever(it) }
            payments.purgeDeletedBefore(cutoff)
            messages.pruneBefore(Dates.millisAt(Dates.addMonths(today, -MESSAGE_RETENTION_MONTHS), 0))
            db.quietDates().purgeBefore(today - QUIET_DATE_RETENTION_DAYS)
            db.sendAttempts().purgeBefore(Dates.millisAt(today - SEND_LOG_RETENTION_DAYS, 0))
        }
    }

    // ---- Internals ----

    /** The single source of truth for expiry (SPEC 7.4). Replays from scratch, every time. */
    private suspend fun recalculate(memberId: Long): ReplayResult {
        val member = requireMember(memberId)
        val ledger = payments.activeFor(memberId).map { it.toLedger() }
        val result = replayFor(member, ledger)
        members.setExpiry(memberId, result.expiry, clock.nowMillis())
        result.periods.forEach { payments.setPeriod(it.paymentId, it.start, it.end) }
        return result
    }

    private suspend fun replayFor(
        member: MemberEntity,
        ledger: List<LedgerPayment>,
        extraRejoin: Long? = null,
    ): ReplayResult {
        val freezeSpans = freezes.forMember(member.id).map { FreezeSpan(it.fromDate, it.toDate) }
        val rejoins = events.dates(member.id, MemberEventType.REJOINED) + listOfNotNull(extraRejoin)
        return MemberRules.replay(member.joinDate, ledger, freezeSpans, rejoins)
    }

    private suspend fun insertRenewal(
        memberId: Long,
        plan: PlanEntity,
        received: Long,
        mode: PaymentMode,
        paidOn: Long,
        note: String,
        appSettings: AppSettings,
        now: Long,
        rejoinEventId: Long?,
    ): PaymentSaved {
        val paymentId = payments.insert(
            PaymentEntity(
                receiptNo = nextReceiptNumber(),
                memberId = memberId,
                kind = PaymentKind.RENEWAL,
                charged = plan.price,
                paid = received,
                paidOn = paidOn,
                mode = mode,
                planId = plan.id,
                planMonths = plan.months,
                ruleApplied = appSettings.renewalRule,
                restartAfterDays = appSettings.restartAfterDays,
                note = note,
                createdAt = now,
            ),
        )
        return finishPayment(memberId, paymentId, received, mode, appSettings, now, rejoinEventId)
    }

    /** Everything after the insert: recalculate, drop stale reminders, queue the thank-you. */
    private suspend fun finishPayment(
        memberId: Long,
        paymentId: Long,
        received: Long,
        mode: PaymentMode,
        appSettings: AppSettings,
        now: Long,
        rejoinEventId: Long?,
    ): PaymentSaved {
        val result = recalculate(memberId)
        // Otherwise a member who just paid could still get "your fee is overdue".
        messages.cancelQueued(memberId, feeReminderKinds, "Member paid")
        val balance = MemberRules.balance(payments.activeFor(memberId).map { it.toLedger() })
        val receipt = requirePayment(paymentId).receiptNo?.let(ReceiptNumbers::format)
        val queued = queueThanks(memberId, paymentId, received, result.expiry, balance, receipt, appSettings, now)
        return PaymentSaved(paymentId, memberId, receipt, received, mode, result.expiry, balance, queued, rejoinEventId)
    }

    private suspend fun queueThanks(
        memberId: Long,
        paymentId: Long,
        received: Long,
        expiry: Long,
        balance: Long,
        receipt: String?,
        appSettings: AppSettings,
        now: Long,
    ): Boolean {
        val member = requireMember(memberId)
        if (received <= 0 || !member.smsConsent || !member.remindersEnabled) return false
        val template = db.templates().get(MessageKind.PAYMENT_THANKS)?.takeIf { it.enabled } ?: return false
        val rendered = TemplateEngine.render(
            template.body,
            TemplateValues(
                fullName = member.name,
                amount = received,
                balance = balance,
                dueDate = Dates.formatShort(expiry),
                gymName = appSettings.gymName,
                upiId = appSettings.upiId,
                trainerPhone = PhoneNumbers.display(appSettings.trainerPhone),
                receiptNo = receipt.orEmpty(),
            ),
            appSettings.packLanguage,
        )
        if (rendered.missing.isNotEmpty()) return false
        messages.insert(
            MessageEntity(
                memberId = memberId,
                phone = member.phone,
                kind = MessageKind.PAYMENT_THANKS,
                body = rendered.text,
                parts = SmsSegments.count(rendered.text).parts,
                priority = MessageKind.PAYMENT_THANKS.priority,
                dedupeKey = DedupeKeys.thanks(memberId, paymentId),
                createdAt = now,
                notBefore = now + THANKS_DELAY_MILLIS,
            ),
        )
        return true
    }

    /**
     * Once, when a member who agreed to SMS is added: says the reminders will come from this
     * number (docs/DECISIONS.md, 1.0.2). It waits as long as the thank-you, so it goes first.
     */
    private suspend fun queueWelcome(memberId: Long, appSettings: AppSettings, now: Long): Boolean {
        val member = requireMember(memberId)
        if (!member.smsConsent || !member.remindersEnabled) return false
        val template = db.templates().getOrPreset(MessageKind.WELCOME, appSettings.packLanguage)?.takeIf { it.enabled }
            ?: return false
        val rendered = TemplateEngine.render(
            template.body,
            TemplateValues(
                fullName = member.name,
                gymName = appSettings.gymName,
                trainerPhone = PhoneNumbers.display(appSettings.trainerPhone),
            ),
            appSettings.packLanguage,
        )
        if (rendered.missing.isNotEmpty()) return false
        val id = messages.insert(
            MessageEntity(
                memberId = memberId,
                phone = member.phone,
                kind = MessageKind.WELCOME,
                body = rendered.text,
                parts = SmsSegments.count(rendered.text).parts,
                priority = MessageKind.WELCOME.priority,
                dedupeKey = DedupeKeys.welcome(memberId),
                createdAt = now,
                notBefore = now + THANKS_DELAY_MILLIS,
            ),
        )
        return id > 0
    }

    /**
     * Taken inside the payment's transaction, so a crash can't reuse or skip a number. It never
     * hands out a number already used, even if the counter were lost or set back: one clash would
     * otherwise stop every payment from saving, with nobody around to fix it.
     */
    private suspend fun nextReceiptNumber(): Long {
        val counters = db.counters()
        val next = maxOf(counters.value(AppDatabase.RECEIPT_COUNTER) ?: 1, (counters.highestReceipt() ?: 0) + 1)
        counters.set(CounterEntity(AppDatabase.RECEIPT_COUNTER, next + 1))
        return next
    }

    private suspend fun requireMember(id: Long): MemberEntity =
        requireNotNull(members.get(id)) { "No member $id" }

    private suspend fun requirePlan(id: Long): PlanEntity =
        requireNotNull(plans.get(id)) { "No plan $id" }

    private suspend fun requirePayment(id: Long): PaymentEntity =
        requireNotNull(payments.get(id)) { "No payment $id" }

    private fun PaymentEntity.toLedger() = LedgerPayment(
        id = id,
        kind = kind,
        charged = charged,
        paid = paid,
        paidOn = paidOn,
        planMonths = planMonths,
        rule = ruleApplied,
        restartAfterDays = restartAfterDays,
        openingExpiry = periodEnd.takeIf { kind == PaymentKind.OPENING },
    )

    companion object {
        /** Longer than the snackbar's 5-second Undo, so Undo can always cancel it (SPEC 7.3). */
        const val THANKS_DELAY_MILLIS = 10_000L
        const val RECYCLE_BIN_DAYS = 30L
        private const val RECYCLE_BIN_MILLIS = RECYCLE_BIN_DAYS * 24 * 60 * 60 * 1000
        private const val MESSAGE_RETENTION_MONTHS = 18
        private const val QUIET_DATE_RETENTION_DAYS = 30L
        private const val SEND_LOG_RETENTION_DAYS = 2L
        private const val OLD_RECORDS_MONTHS = 36

        private val plannedKinds = MessageKind.entries.filter { it.isPlanned }
        private val feeReminderKinds = MessageKind.entries.filter { it.isFeeReminder }
    }
}
