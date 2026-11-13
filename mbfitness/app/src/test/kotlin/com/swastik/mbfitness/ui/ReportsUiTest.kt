package com.swastik.mbfitness.ui

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.data.MemberDraft
import com.swastik.mbfitness.data.MemberSaveResult
import com.swastik.mbfitness.data.RenewalRequest
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.ui.navigation.AppNavigation
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.widget.MbWidget
import com.swastik.mbfitness.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real Reports screen on an in-memory database. The clock says 1 Jan 2027. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
@RunWith(AndroidJUnit4::class)
class ReportsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val today get() = app.fakeClock.today()

    private fun addMember(name: String, phone: String, joined: Long = today): Long = runBlocking {
        (app.members.addMember(MemberDraft(name, phone, 1, joined, smsConsent = true)) as MemberSaveResult.Saved).memberId
    }

    private fun pay(id: Long, amount: Long, on: Long = today) = runBlocking {
        app.members.recordRenewal(RenewalRequest(id, 1, amount, PaymentMode.CASH, on))
    }

    private fun show() {
        runBlocking { app.settings.update { it.copy(lastBackupDay = app.fakeClock.today(), onboardingDone = true) } }
        compose.setContent { MbTheme { AppNavigation() } }
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodesWithContentDescription("Collected this month", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Reports").performClick()
    }

    private fun waitForText(text: String) = compose.waitUntil(WAIT_MILLIS) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForDescription(text: String) = compose.waitUntil(WAIT_MILLIS) {
        compose.onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun scrollTo(text: String) {
        compose.waitUntil(WAIT_MILLIS) {
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true)) }.isSuccess
        }
    }

    @Test
    fun theMonthsNumbersShow() {
        val one = addMember("Asha Rao", "9876543210")
        addMember("Bala Iyer", "9876543211")
        pay(one, 700)
        show()
        waitForText("January 2027")
        waitForDescription("Collected: 700 rupees")
        waitForDescription("Pending: 500 rupees")
        waitForDescription("Active: 2")
        waitForDescription("Average per member: 350 rupees")
        waitForDescription("New joins: 2")
        waitForDescription("Renewals: 0")
        waitForDescription("Left: 0")
    }

    @Test
    fun theChangeFromLastMonthIsThereWhenThereIsALastMonth() {
        val one = addMember("Asha Rao", "9876543210", joined = today - 40)
        pay(one, 1000, on = today - 20)
        pay(one, 1200, on = today)
        show()
        waitForDescription("↑ 20% vs Dec")
    }

    @Test
    fun theSwitcherMovesBetweenMonthsAndStopsAtTheEnds() {
        val one = addMember("Asha Rao", "9876543210", joined = today - 40)
        pay(one, 1000, on = today - 20)
        show()
        waitForText("January 2027")
        assertTrue("there's no future", compose.onAllNodesWithContentDescription("Next month").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithContentDescription("Previous month").performClick()
        waitForText("December 2026")
        waitForDescription("Collected: 1000 rupees")

        compose.onNodeWithContentDescription("Previous month").performClick()
        waitForText("November 2026")
        assertTrue(
            "nothing before she joined",
            compose.onAllNodesWithContentDescription("Previous month").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun eachMonthOnAChartReadsItsValueAndCanBePicked() {
        val one = addMember("Asha Rao", "9876543210", joined = today - 40)
        pay(one, 1000, on = today - 20)
        show()
        waitForDescription("Dec: 1000 rupees")
        compose.onAllNodesWithContentDescription("Dec: 1000 rupees")[0].performClick()
        waitForText("December 2026")
        waitForText("Dec: ₹1,000")
    }

    @Test
    fun theBalanceListShowsWhoOwesBiggestFirst() {
        val small = addMember("Small Debt", "9876543210")
        val big = addMember("Big Debt", "9876543211")
        pay(small, 1000)
        pay(big, 200)
        show()
        scrollTo("BALANCES NOW (2)")
        scrollTo("Big Debt")
        waitForDescription("1000 rupees due")
        scrollTo("Small Debt")
        waitForDescription("200 rupees due")
    }

    @Test
    fun someoneWhoLeftIsntInTheBalanceListOrPending() {
        val gone = addMember("Long Gone", "9876543210")
        pay(gone, 200)
        runBlocking { app.members.markLeft(gone) }
        show()
        waitForDescription("Pending: 0 rupees")
        scrollTo("Nobody owes anything")
    }

    @Test
    fun anEmptyGymDoesntBreakTheCharts() {
        show()
        waitForText("January 2027")
        waitForText("Collected, last 6 months")
        scrollTo("Nobody owes anything")
    }

    @Test
    fun homeAndReportsAgreeOnPending() {
        val gone = addMember("Long Gone", "9876543210")
        pay(gone, 200)
        runBlocking { app.members.markLeft(gone) }
        val here = addMember("Still Here", "9876543211")
        pay(here, 700)
        runBlocking { app.settings.update { it.copy(lastBackupDay = app.fakeClock.today()) } }
        compose.setContent { MbTheme { AppNavigation() } }
        waitForDescription("Pending: 500 rupees")
        compose.onNodeWithText("Reports").performClick()
        waitForDescription("Pending: 500 rupees")
    }

    // ---- SPEC 17: "widget updates after a payment" ----

    @Test
    fun theWidgetSeesThePaymentStraightAway() {
        val id = addMember("Asha Rao", "9876543210", joined = today - 10)
        runBlocking {
            val before = MbWidget.summaryFor(app)
            assertEquals("0 due · 1 overdue", before.headline)
            assertEquals(listOf("Asha"), before.overdueNames)
            app.members.recordRenewal(RenewalRequest(id, 1, 1200, PaymentMode.CASH, today))
            val after = MbWidget.summaryFor(app)
            assertEquals("0 due · 0 overdue", after.headline)
            assertTrue(after.overdueNames.isEmpty())
        }
    }

    /**
     * Found on the phone: while a widget is showing, Glance's update only recomposes it, so the
     * widget has to follow the data itself. This is the data it follows.
     */
    @Test
    fun whatTheWidgetShowsFollowsAPaymentOnItsOwn() {
        val id = addMember("Asha Rao", "9876543210", joined = today - 10)
        runBlocking {
            val summaries = MbWidget.summaries(app)
            assertEquals("0 due · 1 overdue", summaries.first().headline)
            val updated = async(Dispatchers.Default) {
                withTimeout(5_000) { summaries.first { it.headline == "0 due · 0 overdue" } }
            }
            app.members.recordRenewal(RenewalRequest(id, 1, 1200, PaymentMode.CASH, today))
            assertTrue(updated.await().overdueNames.isEmpty())
        }
    }

    @Test
    fun settingAPinHidesNamesFromTheWidgetStraightAway() {
        addMember("Asha Rao", "9876543210", joined = today - 10)
        runBlocking {
            val summaries = MbWidget.summaries(app)
            assertEquals(listOf("Asha"), summaries.first().overdueNames)
            val hidden = async(Dispatchers.Default) { withTimeout(5_000) { summaries.first { it.pending == null } } }
            app.appLock.setPin("1234", newRecoveryCode = false)
            assertTrue(hidden.await().overdueNames.isEmpty())
        }
    }

    @Test
    fun aPaymentMakesTheWidgetRefresh() {
        val id = addMember("Asha Rao", "9876543210")
        var refreshes = 0
        val scope = CoroutineScope(Dispatchers.Default)
        val watching = scope.launch { WidgetRefresher(app.members.observeMembers()) { refreshes++ }.watch() }
        runBlocking {
            delay(SETTLE_MILLIS)
            assertEquals("nothing has changed yet", 0, refreshes)
            app.members.recordRenewal(RenewalRequest(id, 1, 1200, PaymentMode.CASH, today))
            waitUntilTrue { refreshes >= 1 }
        }
        watching.cancel()
    }

    @Test
    fun manyChangesTogetherMakeFewRefreshes() {
        var refreshes = 0
        val scope = CoroutineScope(Dispatchers.Default)
        val watching = scope.launch { WidgetRefresher(app.members.observeMembers()) { refreshes++ }.watch() }
        runBlocking {
            delay(SETTLE_MILLIS)
            repeat(10) { addMember("Member $it", "90000${10000 + it}") }
            waitUntilTrue { refreshes >= 1 }
            assertTrue("was $refreshes", refreshes < 10)
        }
        watching.cancel()
    }

    @Test
    fun theDailyRunRefreshesTheWidget() {
        runBlocking { app.dailyRunner.run() }
        assertEquals(1, app.widgetRefreshes)
    }

    @Test
    fun aPinHidesNamesAndMoneyFromTheWidget() {
        addMember("Asha Rao", "9876543210", joined = today - 10)
        runBlocking {
            app.appLock.setPin("1234", newRecoveryCode = true)
            val summary = MbWidget.summaryFor(app)
            assertEquals("0 due · 1 overdue", summary.headline)
            assertTrue(summary.overdueNames.isEmpty())
            assertEquals(null, summary.pending)
        }
    }

    @Test
    fun birthdaysGoOutOnTheDay() {
        val dob = Dates.of(1995, 1, 1)
        runBlocking {
            app.members.addMember(MemberDraft("Asha Rao", "9876543210", 1, today + 30, smsConsent = true, dob = dob))
            app.settings.update { it.copy(trainerPhone = "+919000000099") }
            app.dailyRunner.run()
            val queued = app.database.messages().withStatusOnly(com.swastik.mbfitness.domain.MessageStatus.QUEUED)
            // The welcome from joining isn't a planned reminder, so it doesn't stop the birthday.
            assertEquals(
                setOf(com.swastik.mbfitness.domain.MessageKind.WELCOME, com.swastik.mbfitness.domain.MessageKind.BIRTHDAY),
                queued.map { it.kind }.toSet(),
            )
            val birthday = queued.single { it.kind == com.swastik.mbfitness.domain.MessageKind.BIRTHDAY }
            assertTrue(birthday.body, "Happy Birthday Asha" in birthday.body)
            assertTrue(app.fakeNotices.summaries.single().detail, "Birthday: Asha" in app.fakeNotices.summaries.single().detail)
        }
    }

    private suspend fun waitUntilTrue(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            delay(50)
        }
        assertTrue("timed out", condition())
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val SETTLE_MILLIS = 300L
    }
}
