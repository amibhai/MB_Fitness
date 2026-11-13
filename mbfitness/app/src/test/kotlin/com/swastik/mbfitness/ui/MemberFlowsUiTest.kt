package com.swastik.mbfitness.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.data.fakeAadhaarDigits
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.ui.navigation.AppNavigation
import com.swastik.mbfitness.ui.theme.MbTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real screens and navigation, on an in-memory database. Includes the SPEC 15 UI test. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
@RunWith(AndroidJUnit4::class)
class MemberFlowsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val digits = fakeAadhaarDigits()
    private val typoDigits = digits.dropLast(1) + ((digits.last() - '0' + 1) % 10)

    @Before
    fun show() {
        compose.setContent { MbTheme { AppNavigation() } }
    }

    private fun waitForText(text: String) = compose.waitUntil(WAIT_MILLIS) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForDescription(text: String) = compose.waitUntil(WAIT_MILLIS) {
        compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun text(label: String): SemanticsNodeInteraction = compose.onNodeWithText(label)

    private fun waitForHome() = compose.waitUntil(WAIT_MILLIS) {
        compose.onAllNodes(hasContentDescription("Collected this month", substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun tap(label: String) {
        waitForText(label)
        // Dialog buttons aren't inside anything scrollable, so there's nothing to scroll.
        runCatching { text(label).performScrollTo() }
        text(label).performClick()
    }

    private fun gone(label: String) = compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isEmpty()

    private fun fillBasics(name: String = "Rahul Sharma", phone: String = "9876543210") {
        waitForDescription("Add member")
        compose.onNodeWithContentDescription("Add member").performClick()
        waitForText("Phone number")
        text("Name").performTextInput(name)
        text("Phone number").performTextInput(phone)
    }

    private fun addMemberNotYetPaid(name: String = "Rahul Sharma", phone: String = "9876543210") {
        fillBasics(name, phone)
        tap("Not yet")
        tap("Save")
        waitForHome()
    }

    /** Joined today with nothing paid, so their expiry is today: "Due soon". */
    private fun openMemberDueToday(name: String = "Rahul Sharma") {
        tap("Due soon")
        tap(name)
        waitForText("Record payment")
    }

    @Test
    fun spec15AddMemberThenPartPaymentThenBalanceThenBadgeGone() {
        addMemberNotYetPaid()
        openMemberDueToday()

        // Record a part payment: the balance badge appears.
        tap("Record payment")
        waitForText("Fully paid")
        text("Amount received (₹)").performTextReplacement("500")
        waitForText("₹700 will remain due")
        tap("Save")
        waitForDescription("700 rupees due")
        waitForText("₹500 saved · Receipt MBF-0001")

        // Pay the balance: the badge goes.
        tap("Record payment")
        tap("Pay balance")
        waitForText("Fully paid")
        tap("Save")
        waitForText("Nothing due")
        assertTrue(compose.onAllNodesWithContentDescription("700 rupees due").fetchSemanticsNodes().isEmpty())
        waitForText("₹700 saved · Receipt MBF-0002")
    }

    @Test
    fun undoReversesAPayment() {
        addMemberNotYetPaid()
        openMemberDueToday()
        tap("Record payment")
        waitForText("Fully paid")
        tap("Save")
        waitForText("saved · Receipt MBF-0001")
        tap("Undo")
        waitForText("Payment undone")
        waitForText("No payments yet.")
    }

    @Test
    fun aDuplicatePhoneNamesTheOtherMember() {
        addMemberNotYetPaid()
        fillBasics("Anil Kumar", "09876543210")
        tap("Not yet")
        tap("Save")
        waitForText("This number belongs to Rahul Sharma")
    }

    @Test
    fun aMissingNameAndBadNumberAreExplained() {
        waitForDescription("Add member")
        compose.onNodeWithContentDescription("Add member").performClick()
        waitForText("Phone number")
        tap("Save")
        waitForText("Enter the member's name")
        waitForText("Number must be 10 digits")
    }

    @Test
    fun smsConsentIsNotPreTickedAndSaysWhatThatMeans() {
        fillBasics()
        waitForText("No reminders will be sent to this member")
        tap("Member agrees to get fee reminders by SMS")
        assertTrue(gone("No reminders will be sent to this member"))
    }

    @Test
    fun theFormDatesThePaymentAndSaysWhatItGives() {
        fillBasics()
        waitForText("PAYMENT AT JOINING")
        waitForText("Payment date")
        waitForText("Valid till ${Dates.formatFull(Dates.addMonths(Dates.of(2027, 1, 1), 1))}")
        tap("Not yet")
        assertTrue(gone("Payment date"))
        assertTrue(gone("Valid till"))
    }

    @Test
    fun theMemberPageShowsTheJoinDate() {
        addMemberNotYetPaid()
        openMemberDueToday()
        waitForText("Joined")
    }

    @Test
    fun aadhaarIsOptional() {
        addMemberNotYetPaid()
        val member = runBlocking { app.members.observeMembers().first().single() }
        assertNull(runBlocking { app.members.member(member.id) }!!.aadhaarEnc)
    }

    @Test
    fun aMistypedAadhaarIsRefused() {
        fillBasics()
        tap("Not yet")
        text("Aadhaar number (optional)").performScrollTo().performTextInput(typoDigits)
        waitForText("Aadhaar number is not valid")
        tap("Save")
        compose.waitForIdle()
        assertTrue("nothing should be saved", runBlocking { app.members.observeMembers().first() }.isEmpty())
    }

    @Test
    fun aadhaarNeedsItsOwnConsentTick() {
        fillBasics()
        tap("Not yet")
        assertTrue("the tick only appears with a number", gone("agreed to share Aadhaar"))
        text("Aadhaar number (optional)").performScrollTo().performTextInput(digits)
        waitForText("Member agreed to share Aadhaar for gym records")
        tap("Save")
        waitForText("Tick this to save the Aadhaar number")
        assertTrue(runBlocking { app.members.observeMembers().first() }.isEmpty())
    }

    @Test
    fun aMinorsAadhaarConsentComesFromTheGuardian() {
        fillBasics()
        tap("Not yet")
        tap("Under 18")
        text("Aadhaar number (optional)").performScrollTo().performTextInput(digits)
        waitForText("Guardian agreed to share Aadhaar for gym records")
    }

    private fun addMemberWithAadhaar() {
        fillBasics()
        tap("Not yet")
        text("Aadhaar number (optional)").performScrollTo().performTextInput(digits)
        tap("Member agreed to share Aadhaar for gym records. It is optional.")
        tap("Save")
    }

    @Test
    fun savedAadhaarIsEncryptedAndShownMasked() {
        addMemberWithAadhaar()
        waitForText("Protect Aadhaar numbers")
        tap("Not now")
        val row = runBlocking { app.members.observeMembers().first().single() }
        val member = runBlocking { app.members.member(row.id) }!!
        assertEquals(digits.takeLast(4), member.aadhaarLast4)
        assertNotNull(member.aadhaarEnc)
        assertFalse(digits in member.aadhaarEnc!!)
        assertEquals(Dates.of(2027, 1, 1), member.aadhaarConsentDate)

        openMemberDueToday()
        waitForText("XXXX XXXX ${digits.takeLast(4)}")
        assertTrue("the full number must not be on screen", gone(digits))
        assertTrue(gone(digits.chunked(4).joinToString(" ")))
    }

    @Test
    fun showingAadhaarAsksForAPinFirstWhenNoneIsSet() {
        addMemberWithAadhaar()
        waitForText("Protect Aadhaar numbers")
        tap("Not now")
        openMemberDueToday()
        tap("Show")
        waitForText("Set an app PIN first")
        assertTrue(gone(digits.chunked(4).joinToString(" ")))
    }

    @Test
    fun showingAadhaarRevealsItOnlyAfterTheRightPin() {
        runBlocking { app.appLock.setPin("1234", newRecoveryCode = true) }
        addMemberWithAadhaar()
        openMemberDueToday()
        tap("Show")
        waitForText("Show Aadhaar number")
        val grouped = digits.chunked(4).joinToString(" ")

        "0000".forEach { text(it.toString()).performClick() }
        waitForText("Wrong PIN")
        assertTrue(gone(grouped))

        "1234".forEach { text(it.toString()).performClick() }
        waitForText(grouped)
        waitForText("Hides in")
        tap("Hide")
        waitForText("XXXX XXXX ${digits.takeLast(4)}")
        assertTrue(gone(grouped))
    }

    @Test
    fun aLostKeyLeavesTheLast4DigitsAndSaysWhy() {
        runBlocking { app.appLock.setPin("1234", newRecoveryCode = true) }
        addMemberWithAadhaar()
        app.fakeVault.keyLost = true
        openMemberDueToday()
        tap("Show")
        waitForText("Show Aadhaar number")
        "1234".forEach { text(it.toString()).performClick() }
        waitForText("Full number not available on this phone. Re-enter it.")
        waitForText("XXXX XXXX ${digits.takeLast(4)}")
    }

    @Test
    fun aadhaarCanBeRemoved() {
        runBlocking { app.appLock.setPin("1234", newRecoveryCode = true) }
        addMemberWithAadhaar()
        openMemberDueToday()
        tap("Remove")
        waitForText("Remove Aadhaar?")
        compose.onAllNodesWithText("Remove")[1].performClick()
        waitForText("Not added")
        val row = runBlocking { app.members.observeMembers().first().single() }
        assertNull(runBlocking { app.members.member(row.id) }!!.aadhaarEnc)
    }

    @Test
    fun theSameAadhaarOnAnotherMemberWarnsThenAllowsSaveAnyway() {
        addMemberWithAadhaar()
        waitForText("Protect Aadhaar numbers")
        tap("Not now")
        fillBasics("Anil Kumar", "9123456780")
        tap("Not yet")
        text("Aadhaar number (optional)").performScrollTo().performTextInput(digits)
        tap("Member agreed to share Aadhaar for gym records. It is optional.")
        tap("Save")
        waitForText("This number is already on Rahul Sharma")
        tap("Save anyway")
        waitForHome()
        assertEquals(2, runBlocking { app.members.observeMembers().first() }.size)
    }

    @Test
    fun deleteNeedsTheNameTyped() {
        addMemberNotYetPaid()
        openMemberDueToday()
        compose.onNodeWithContentDescription("More options").performClick()
        tap("Delete permanently")
        waitForText("Delete Rahul Sharma?")
        text("Delete").assertIsNotEnabled()
        text("Member's name").performTextInput("rahul sharma")
        text("Delete").performClick()
        waitForText("Moved to the recycle bin for 30 days")
        assertTrue(runBlocking { app.members.observeMembers().first() }.isEmpty())
    }

    @Test
    fun todaysClosingAddsUpTheDaysPayments() {
        addMemberNotYetPaid()
        openMemberDueToday()
        tap("Record payment")
        waitForText("Fully paid")
        tap("Save")
        waitForText("saved · Receipt MBF-0001")
        compose.onNodeWithContentDescription("Back").performClick()
        waitForHome()
        tap("Today")
        waitForText("Today's closing")
        waitForDescription("UPI: 1200 rupees")
        waitForDescription("Total: 1200 rupees")
        waitForText("MBF-0001 · UPI")
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
