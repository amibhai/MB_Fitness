package com.swastik.mbfitness.ui

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.data.FirstPayment
import com.swastik.mbfitness.data.MemberDraft
import com.swastik.mbfitness.data.MemberSaveResult
import com.swastik.mbfitness.data.TestEnvironment
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.MemberEventType
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.ui.screens.member.MemberFormResult
import com.swastik.mbfitness.ui.screens.member.MemberFormViewModel
import com.swastik.mbfitness.ui.screens.member.PaidToday
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The Add / Edit member form's dates (1.0.2): the payment date follows the join date. */
@RunWith(AndroidJUnit4::class)
class MemberFormDatesTest {
    private val env = TestEnvironment()
    private val today = env.clock.today()

    @After
    fun tearDown() = env.close()

    /** The database answers on its own thread, and the form carries on on the main one. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun form(memberId: Long? = null) =
        MemberFormViewModel(memberId, env.repository, env.settings, env.clock).also { form ->
            waitFor("the form to load") { form.uiState.value.loaded }
        }

    private fun MemberFormViewModel.saveAndWait(): MemberFormResult.Saved {
        save()
        waitFor("it to save") { uiState.value.result != null }
        return uiState.value.result as MemberFormResult.Saved
    }

    @Test
    fun thePaymentDateFollowsTheJoinDateUntilItsChangedByHand() {
        val form = form()
        assertEquals(today, form.uiState.value.paymentDate)
        assertEquals(Dates.addMonths(today, 1), form.uiState.value.validTill)

        form.onJoinDateChange(today - 5)
        assertEquals(today - 5, form.uiState.value.paymentDate)
        assertEquals(Dates.addMonths(today - 5, 1), form.uiState.value.validTill)

        form.onJoinDateChange(today + 4)
        assertEquals("a payment can't be in the future", today, form.uiState.value.paymentDate)

        form.onPaymentDateChange(today - 1)
        form.onJoinDateChange(today - 9)
        assertEquals("changed by hand, so it stays", today - 1, form.uiState.value.paymentDate)
    }

    @Test
    fun anOldJoinDateWithNoRecentPaymentIsCalledOut() {
        val form = form()
        form.onJoinDateChange(today - 100)
        assertTrue(form.uiState.value.validTillPassed)
        form.onPaymentDateChange(today - 3)
        assertFalse(form.uiState.value.validTillPassed)
        assertEquals(Dates.addMonths(today - 3, 1), form.uiState.value.validTill)
        form.onPaidTodayChange(PaidToday.NOT_YET)
        assertNull("nothing paid, so nothing to show", form.uiState.value.validTill)
    }

    @Test
    fun savingDatesThePaymentAsTheFormSays() {
        val form = form()
        form.onNameChange("Rahul Sharma")
        form.onPhoneChange("9876543210")
        form.onJoinDateChange(today - 6)
        val saved = form.saveAndWait()
        val payment = runBlocking { env.db.payments().get(saved.payment!!.paymentId) }!!
        assertEquals(today - 6, payment.paidOn)
    }

    @Test
    fun editingSaysWhenTheFirstPaymentWillMoveAndMovesIt() {
        val id = runBlocking {
            val draft = MemberDraft("Rahul Sharma", "9876543210", 1, today - 5, firstPayment = FirstPayment(1200, PaymentMode.CASH))
            (env.repository.addMember(draft) as MemberSaveResult.Saved).memberId
        }
        val form = form(id)
        assertFalse(form.uiState.value.joiningPaymentMoves)
        assertNull("the payment part is only for new members", form.uiState.value.validTill)

        form.onJoinDateChange(today - 8)
        assertTrue(form.uiState.value.joiningPaymentMoves)
        form.saveAndWait()
        assertEquals(today - 8, runBlocking { env.repository.joiningPayment(id) }!!.paidOn)
        assertEquals(listOf(today - 8), runBlocking { env.db.events().dates(id, MemberEventType.JOINED) })
    }

    @Test
    fun editingSaysNothingWhenThePaymentHadItsOwnDate() {
        val id = runBlocking {
            val draft = MemberDraft("Rahul Sharma", "9876543210", 1, today - 5, firstPayment = FirstPayment(1200, PaymentMode.CASH, paidOn = today - 1))
            (env.repository.addMember(draft) as MemberSaveResult.Saved).memberId
        }
        val form = form(id)
        form.onJoinDateChange(today - 8)
        assertFalse(form.uiState.value.joiningPaymentMoves)
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
