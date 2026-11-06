package com.swastik.mbfitness.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.domain.MemberState
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.domain.ReceiptNumbers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * States the app never makes itself, but which would leave bhaiya stuck with nobody to fix them
 * (docs/DECISIONS.md). Each one must put itself right instead.
 */
@RunWith(AndroidJUnit4::class)
class SelfRepairTest {
    private val env = TestEnvironment()
    private val repo = env.repository
    private val db = env.db
    private val today get() = env.clock.today()

    @After
    fun tearDown() = env.close()

    private suspend fun member(phone: String = "9876543210") =
        (repo.addMember(MemberDraft("Rahul Sharma", phone, 1, today, smsConsent = true)) as MemberSaveResult.Saved).memberId

    private suspend fun pay(id: Long) = repo.recordRenewal(RenewalRequest(id, 1, 1200, PaymentMode.CASH, today))

    /** One clash would otherwise stop every payment from saving. */
    @Test
    fun aReceiptCounterSetBackNeverReusesANumber() = runTest {
        val id = member()
        repeat(3) { pay(id) }
        db.counters().set(CounterEntity(AppDatabase.RECEIPT_COUNTER, 2))
        val next = pay(id)
        assertEquals(ReceiptNumbers.format(4), next.receipt)
        assertEquals(ReceiptNumbers.format(5), pay(id).receipt)
    }

    @Test
    fun aMissingReceiptCounterStartsAgainAfterTheHighestNumber() = runTest {
        val id = member()
        repeat(2) { pay(id) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM counters")
        assertEquals(ReceiptNumbers.format(3), pay(id).receipt)
    }

    @Test
    fun aDeletedPaymentsNumberIsStillNeverReused() = runTest {
        val id = member()
        pay(id)
        val second = pay(id)
        repo.deletePayment(second.paymentId)
        db.counters().set(CounterEntity(AppDatabase.RECEIPT_COUNTER, 1))
        assertEquals(ReceiptNumbers.format(3), pay(id).receipt)
    }

    @Test
    fun aFrozenMemberWithNoOpenFreezeCanStillBeUnfrozen() = runTest {
        val id = member()
        pay(id)
        val stored = db.members().get(id)!!
        db.members().update(stored.copy(status = MemberState.FROZEN))
        assertNotNull(repo.unfreeze(id))
        assertEquals(MemberState.ACTIVE, db.members().get(id)!!.status)
    }

    @Test
    fun unfreezingSomeoneWhoIsntFrozenChangesNothing() = runTest {
        val id = member()
        assertEquals(null, repo.unfreeze(id))
        assertEquals(MemberState.ACTIVE, db.members().get(id)!!.status)
    }

    @Test
    fun aPaymentWithAnUnreadableEditHistoryCanStillBeCorrected() = runTest {
        val id = member()
        val saved = pay(id)
        db.payments().update(db.payments().get(saved.paymentId)!!.copy(editHistory = "not json"))
        repo.editPayment(saved.paymentId, PaymentEdit(1200, 1000, today, PaymentMode.UPI, "fixed"))
        val edited = db.payments().get(saved.paymentId)!!
        assertEquals(1000, edited.paid)
        assertEquals(1, kotlinx.serialization.json.Json.decodeFromString<List<PaymentVersion>>(edited.editHistory).size)
    }
}
