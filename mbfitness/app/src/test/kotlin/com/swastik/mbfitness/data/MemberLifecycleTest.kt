package com.swastik.mbfitness.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.ImportRow
import com.swastik.mbfitness.domain.MemberEventType
import com.swastik.mbfitness.domain.MemberRules
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.domain.PaymentKind
import com.swastik.mbfitness.domain.PaymentMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Adding, deleting, restoring and importing members, and everything about Aadhaar. */
@RunWith(AndroidJUnit4::class)
class MemberLifecycleTest {
    private val env = TestEnvironment()
    private val repo = env.repository
    private val db = env.db
    private val today get() = env.clock.today()
    private val digits = fakeAadhaarDigits()
    private val aadhaar = fakeAadhaar()

    @After
    fun tearDown() = env.close()

    private fun draft(phone: String = "98765 43210", consent: Boolean = false) = MemberDraft(
        name = "  Rahul Sharma ",
        phone = phone,
        planId = 1,
        joinDate = today,
        smsConsent = consent,
    )

    private suspend fun add(phone: String = "9876543210"): Long =
        (repo.addMember(draft(phone)) as MemberSaveResult.Saved).memberId

    private suspend fun member(id: Long) = db.members().get(id)!!

    /** Reads every text column of every row, to prove the number is nowhere in plain form. */
    private fun allStoredText(): String {
        val out = StringBuilder()
        val tables = listOf("members", "payments", "messages", "member_events", "error_log", "broadcasts", "templates", "counters", "plans", "freezes", "quiet_dates")
        for (table in tables) {
            db.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                while (cursor.moveToNext()) {
                    for (column in 0 until cursor.columnCount) {
                        if (cursor.getType(column) != android.database.Cursor.FIELD_TYPE_BLOB) out.append(cursor.getString(column)).append('|')
                    }
                }
            }
        }
        return out.toString()
    }

    @Test
    fun addingAMemberCleansUpWhatWasTyped() = runTest {
        val id = add("(98765) 43210")
        val member = member(id)
        assertEquals("+919876543210", member.phone)
        assertEquals("+919876543210", member.activePhone)
        assertEquals("Rahul Sharma", member.name)
        assertEquals(today, member.expiryDate)
        assertEquals(listOf(today), db.events().dates(id, MemberEventType.JOINED))
    }

    @Test
    fun smsConsentIsOffUnlessTickedAndItsDateIsRecorded() = runTest {
        val noConsent = member(add())
        assertFalse(noConsent.smsConsent)
        assertNull(noConsent.consentDate)
        val id = (repo.addMember(draft("9123456780", consent = true)) as MemberSaveResult.Saved).memberId
        assertTrue(member(id).smsConsent)
        assertEquals(today, member(id).consentDate)
    }

    @Test
    fun aNameAndAValidNumberAreRequired() = runTest {
        assertEquals(MemberSaveResult.NameMissing, repo.addMember(draft().copy(name = "  ")))
        assertEquals(MemberSaveResult.PhoneInvalid, repo.addMember(draft("12345")))
    }

    @Test
    fun aPhoneNumberInUseNamesTheExistingMember() = runTest {
        val id = add()
        val result = repo.addMember(draft("09876543210")) as MemberSaveResult.PhoneTaken
        assertEquals(id, result.existing.id)
        assertEquals("Rahul Sharma", result.existing.name)
    }

    @Test
    fun theDatabaseItselfRefusesADuplicatePhone() = runTest {
        add()
        val now = env.clock.nowMillis()
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking {
                db.members().insert(
                    MemberEntity(name = "X", phone = "+919876543210", activePhone = "+919876543210", joinDate = 0, planId = 1, expiryDate = 0, createdAt = now, updatedAt = now),
                )
            }
        }
    }

    @Test
    fun editingCanNotTakeAnotherMembersNumber() = runTest {
        val first = add("9876543210")
        add("9123456780")
        val edit = MemberEdit("New", "9123456780", 1, today, null, "", false, null, null)
        assertTrue(repo.updateMember(first, edit) is MemberSaveResult.PhoneTaken)
    }

    @Test
    fun editingAMemberKeepsQueuedMessagesPointedAtTheRightNumber() = runTest {
        val id = add()
        val queued = db.messages().insert(
            MessageEntity(memberId = id, phone = "+919876543210", kind = MessageKind.MANUAL, body = "b", parts = 1, priority = 1, createdAt = 0, notBefore = 0),
        )
        repo.updateMember(id, MemberEdit("Rahul Sharma", "9123456780", 1, today, null, "", false, null, null))
        assertEquals("+919123456780", db.messages().get(queued)!!.phone)
        assertEquals("+919123456780", member(id).activePhone)
    }

    @Test
    fun guardianDetailsOnlyStayForMinors() = runTest {
        val id = (repo.addMember(draft().copy(isMinor = true, guardianName = " Mum ", guardianPhone = "9123456780")) as MemberSaveResult.Saved).memberId
        assertEquals("Mum", member(id).guardianName)
        assertEquals("+919123456780", member(id).guardianPhone)
        val adult = (repo.addMember(draft("9000000001").copy(isMinor = false, guardianName = "Mum")) as MemberSaveResult.Saved).memberId
        assertNull(member(adult).guardianName)
    }

    @Test
    fun stopSmsStopsEverythingIncludingWhatIsQueued() = runTest {
        val id = (repo.addMember(draft(consent = true)) as MemberSaveResult.Saved).memberId
        val queued = db.messages().insert(
            MessageEntity(memberId = id, phone = "+919876543210", kind = MessageKind.BROADCAST, body = "b", parts = 1, priority = 5, createdAt = 0, notBefore = 0),
        )
        repo.stopSms(id)
        assertFalse(member(id).smsConsent)
        assertFalse(member(id).remindersEnabled)
        assertNull(member(id).consentDate)
        assertEquals(MessageStatus.CANCELLED, db.messages().get(queued)!!.status)
    }

    @Test
    fun aDuplicateReminderIsRefusedAndCancellingFreesItsKey() = runTest {
        val id = add()
        fun message() = MessageEntity(memberId = id, phone = "+919876543210", kind = MessageKind.ON_DUE, body = "b", parts = 1, priority = 3, dedupeKey = "k", createdAt = 0, notBefore = 0)
        val first = db.messages().insert(message())
        assertTrue(first > 0)
        assertEquals(-1L, db.messages().insert(message()))
        db.messages().cancelQueuedByKey("k", "test")
        assertTrue(db.messages().insert(message()) > 0)
    }

    @Test
    fun aDeletedMemberIsHiddenAndItsPhoneIsFree() = runTest {
        val id = add()
        repo.deleteMember(id)
        assertTrue(repo.observeMembers().first().isEmpty())
        assertEquals(listOf(id), repo.observeRecycleBin().first().map { it.id })
        assertNull(member(id).activePhone)
        assertTrue(repo.addMember(draft()) is MemberSaveResult.Saved)
    }

    @Test
    fun restoringBringsAMemberBackWithTheirBalance() = runTest {
        val id = (repo.addMember(draft().copy(firstPayment = FirstPayment(500, PaymentMode.CASH))) as MemberSaveResult.Saved).memberId
        repo.deleteMember(id)
        assertEquals(RestoreResult.Restored, repo.restoreMember(id))
        val row = repo.observeMembers().first().single()
        assertEquals(700L, row.balance)
        assertEquals(Dates.addMonths(today, 1), row.expiryDate)
        assertEquals("+919876543210", member(id).activePhone)
    }

    @Test
    fun restoringIsRefusedIfTheNumberWasTakenInTheMeantime() = runTest {
        val old = add()
        repo.deleteMember(old)
        val replacement = add()
        val result = repo.restoreMember(old) as RestoreResult.PhoneTaken
        assertEquals(replacement, result.existing.id)
        assertNotNull(member(old).deletedAt)
    }

    @Test
    fun theRecycleBinEmptiesAfter30DaysTakingEverythingWithIt() = runTest {
        val id = (repo.addMember(draft().copy(aadhaar = aadhaar, aadhaarConsent = true, firstPayment = FirstPayment(500, PaymentMode.CASH))) as MemberSaveResult.Saved).memberId
        db.messages().insert(MessageEntity(memberId = id, phone = "p", kind = MessageKind.MANUAL, body = "b", parts = 1, priority = 1, status = MessageStatus.SENT, createdAt = 0, notBefore = 0))
        repo.deleteMember(id)

        env.clock.day = today + 29
        repo.housekeeping()
        assertNotNull(db.members().get(id))

        env.clock.day = today + 2
        repo.housekeeping()
        assertNull(db.members().get(id))
        assertTrue(db.payments().activeFor(id).isEmpty())
        assertTrue(db.events().forMember(id).isEmpty())
        assertTrue(db.messages().withStatus(id, MessageStatus.SENT).isEmpty())
    }

    @Test
    fun housekeepingPurgesOldDataOnSchedule() = runTest {
        val id = (repo.addMember(draft().copy(firstPayment = FirstPayment(500, PaymentMode.CASH))) as MemberSaveResult.Saved).memberId
        val paymentId = db.payments().activeFor(id).single().id
        repo.deletePayment(paymentId)
        val old = env.clock.nowMillis()
        val oldMessage = db.messages().insert(MessageEntity(memberId = id, phone = "p", kind = MessageKind.MANUAL, body = "b", parts = 1, priority = 1, status = MessageStatus.SENT, createdAt = old, notBefore = 0))
        val waiting = db.messages().insert(MessageEntity(memberId = id, phone = "p", kind = MessageKind.MANUAL, body = "b", parts = 1, priority = 1, createdAt = old, notBefore = 0))
        db.quietDates().upsert(QuietDateEntity(date = today, label = "Old"))

        env.clock.day = today + 31
        repo.housekeeping()
        assertNull("soft-deleted payments go after 30 days", db.payments().get(paymentId))
        assertTrue("past holidays go after 30 days", db.quietDates().all().isEmpty())
        assertNotNull("messages stay for 18 months", db.messages().get(oldMessage))

        env.clock.day = Dates.addMonths(today, 18) + 1
        repo.housekeeping()
        assertNull(db.messages().get(oldMessage))
        assertNotNull("unsent messages are never pruned", db.messages().get(waiting))
    }

    @Test
    fun aadhaarIsOnlyEverStoredEncrypted() = runTest {
        val id = (repo.addMember(draft().copy(aadhaar = aadhaar, aadhaarConsent = true)) as MemberSaveResult.Saved).memberId
        val member = member(id)
        assertEquals(digits.takeLast(4), member.aadhaarLast4)
        assertEquals(today, member.aadhaarConsentDate)
        assertNotEquals(digits, member.aadhaarEnc)
        assertFalse("found the number in plain form", digits in allStoredText())
        assertFalse("found the number in a printed entity", digits in member.toString())
    }

    @Test
    fun aadhaarIsRefusedWithoutItsOwnConsent() = runTest {
        assertEquals(MemberSaveResult.AadhaarConsentMissing, repo.addMember(draft().copy(aadhaar = aadhaar, aadhaarConsent = false)))
        assertTrue(repo.observeMembers().first().isEmpty())
    }

    @Test
    fun aadhaarIsOptional() = runTest {
        val member = member(add())
        assertNull(member.aadhaarEnc)
        assertNull(member.aadhaarLast4)
        assertNull(member.aadhaarConsentDate)
    }

    @Test
    fun aadhaarCanBeRevealedOnThisPhone() = runTest {
        val id = (repo.addMember(draft().copy(aadhaar = aadhaar, aadhaarConsent = true)) as MemberSaveResult.Saved).memberId
        assertEquals(digits, repo.revealAadhaar(id)!!.revealDigits())
        assertNull(repo.revealAadhaar(add("9123456780")))
    }

    @Test
    fun aKeystoreFailureNeverStopsAMemberBeingSaved() = runTest {
        env.vault.refusesToEncrypt = true
        val result = repo.addMember(draft().copy(aadhaar = aadhaar, aadhaarConsent = true)) as MemberSaveResult.Saved
        assertFalse(result.aadhaarStored)
        val member = member(result.memberId)
        assertNull(member.aadhaarEnc)
        assertNull(member.aadhaarLast4)
        assertNull(member.aadhaarConsentDate)
        assertFalse(repo.setAadhaar(result.memberId, aadhaar))
    }

    @Test
    fun aLostKeyLeavesTheLast4DigitsAndNoCrash() = runTest {
        val id = (repo.addMember(draft().copy(aadhaar = aadhaar, aadhaarConsent = true)) as MemberSaveResult.Saved).memberId
        env.vault.keyLost = true
        assertNull(repo.revealAadhaar(id))
        assertEquals(digits.takeLast(4), member(id).aadhaarLast4)
    }

    @Test
    fun aadhaarCanBeAddedLaterAndRemoved() = runTest {
        val id = add()
        assertTrue(repo.setAadhaar(id, aadhaar))
        assertEquals(digits, repo.revealAadhaar(id)!!.revealDigits())
        repo.removeAadhaar(id)
        val member = member(id)
        assertNull(member.aadhaarEnc)
        assertNull(member.aadhaarLast4)
        assertNull(member.aadhaarConsentDate)
    }

    @Test
    fun leavingCanRemoveTheAadhaarButDoesNotByDefault() = runTest {
        val keep = (repo.addMember(draft().copy(aadhaar = aadhaar, aadhaarConsent = true)) as MemberSaveResult.Saved).memberId
        repo.markLeft(keep)
        assertNotNull(member(keep).aadhaarEnc)
        repo.markLeft(keep, removeAadhaar = true)
        assertNull(member(keep).aadhaarEnc)
    }

    @Test
    fun theSameNumberOnAnotherMemberIsFound() = runTest {
        val owner = (repo.addMember(draft().copy(aadhaar = aadhaar, aadhaarConsent = true)) as MemberSaveResult.Saved).memberId
        assertEquals(owner, repo.findAadhaarOwner(aadhaar)!!.id)
        assertNull("a member doesn't clash with themselves", repo.findAadhaarOwner(aadhaar, exceptMemberId = owner))
        assertNull(repo.findAadhaarOwner(fakeAadhaar("99990000002")))
    }

    @Test
    fun aDeletedMembersNumberIsNotAClash() = runTest {
        val owner = (repo.addMember(draft().copy(aadhaar = aadhaar, aadhaarConsent = true)) as MemberSaveResult.Saved).memberId
        repo.deleteMember(owner)
        assertNull(repo.findAadhaarOwner(aadhaar))
    }

    private fun row(line: Int, phone: String, balance: Long = 0, withAadhaar: Boolean = false) = ImportRow(
        line, "Imported $line", phone, 1, Dates.of(2027, 3, 1), balance, null, "note", if (withAadhaar) aadhaar else null,
    )

    @Test
    fun importCreatesAnOpeningPaymentWithNoReceipt() = runTest {
        val result = repo.importMembers(listOf(row(2, "+919000000001", balance = 700), row(3, "+919000000002")), smsConsent = false)
        assertEquals(2, result.imported)
        val rows = repo.observeMembers().first()
        assertEquals(listOf(Dates.of(2027, 3, 1), Dates.of(2027, 3, 1)), rows.map { it.expiryDate })
        assertEquals(setOf(700L, 0L), rows.map { it.balance }.toSet())
        val opening = db.payments().activeFor(rows[0].id).single()
        assertEquals(PaymentKind.OPENING, opening.kind)
        assertNull(opening.receiptNo)
        assertEquals(1L, db.counters().value(AppDatabase.RECEIPT_COUNTER))
    }

    @Test
    fun importedMembersAreNotCountedAsNewJoins() = runTest {
        repo.importMembers(listOf(row(2, "+919000000001")), smsConsent = false)
        assertEquals(0, db.events().countBetween(MemberEventType.JOINED, 0, Long.MAX_VALUE))
        assertEquals(1, db.events().countBetween(MemberEventType.IMPORTED, 0, Long.MAX_VALUE))
    }

    @Test
    fun aRenewalAfterImportContinuesFromTheFileExpiry() = runTest {
        repo.importMembers(listOf(row(2, "+919000000001")), smsConsent = false)
        val id = repo.observeMembers().first().single().id
        val saved = repo.recordRenewal(RenewalRequest(id, 1, 1200, PaymentMode.CASH, today))
        assertEquals(Dates.of(2027, 4, 1), saved.newExpiry)
    }

    @Test
    fun importConsentFollowsTheTick() = runTest {
        repo.importMembers(listOf(row(2, "+919000000001")), smsConsent = false)
        assertFalse(repo.observeMembers().first().single().smsConsent)
        repo.importMembers(listOf(row(2, "+919000000002")), smsConsent = true)
        assertEquals(1, repo.observeMembers().first().count { it.smsConsent })
    }

    @Test
    fun importEncryptsAadhaarToo() = runTest {
        val result = repo.importMembers(listOf(row(2, "+919000000001", withAadhaar = true)), smsConsent = false)
        assertEquals(1, result.aadhaarStored)
        assertEquals(0, result.aadhaarFailed)
        assertFalse(digits in allStoredText())
        val id = repo.observeMembers().first().single().id
        assertEquals(digits, repo.revealAadhaar(id)!!.revealDigits())
    }

    @Test
    fun importReportsNumbersAlreadyOnAnotherMember() = runTest {
        repo.addMember(draft().copy(aadhaar = aadhaar, aadhaarConsent = true))
        val result = repo.importMembers(listOf(row(2, "+919000000001", withAadhaar = true)), smsConsent = false)
        assertEquals(listOf("Imported 2"), result.aadhaarDuplicates)
    }

    @Test
    fun importCarriesOnIfTheKeystoreRefuses() = runTest {
        env.vault.refusesToEncrypt = true
        val result = repo.importMembers(listOf(row(2, "+919000000001", withAadhaar = true)), smsConsent = false)
        assertEquals(1, result.imported)
        assertEquals(1, result.aadhaarFailed)
        assertNull(member(repo.observeMembers().first().single().id).aadhaarLast4)
    }

    @Test
    fun aFailedImportLeavesNothingBehind() = runTest {
        val duplicatePhone = "+919000000001"
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking {
                repo.importMembers(listOf(row(2, duplicatePhone), row(3, duplicatePhone)), smsConsent = false)
            }
        }
        assertTrue(repo.observeMembers().first().isEmpty())
    }

    @Test
    fun existingPhonesAreOfferedToThePreview() = runTest {
        add()
        assertEquals(mapOf("+919876543210" to "Rahul Sharma"), repo.existingPhones())
    }

    @Test
    fun theBalanceOnAListRowMatchesTheLedger() = runTest {
        val id = (repo.addMember(draft().copy(firstPayment = FirstPayment(500, PaymentMode.CASH))) as MemberSaveResult.Saved).memberId
        val row = repo.observeMembers().first().single()
        assertEquals(700L, row.balance)
        assertEquals(700L, MemberRules.due(row.balance))
        assertEquals("1 month", row.planName)
        assertEquals(id, row.id)
    }
}
