package com.swastik.mbfitness.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.data.MemberDraft
import com.swastik.mbfitness.data.MemberSaveResult
import com.swastik.mbfitness.data.RenewalRequest
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.ui.navigation.AppNavigation
import com.swastik.mbfitness.ui.theme.MbTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** SPEC 14's "no crash on" list: landscape, large text, a small screen and a full phone. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
@RunWith(AndroidJUnit4::class)
class HardeningUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val today get() = app.fakeClock.today()

    private fun waitForText(text: String) = compose.waitUntil(WAIT_MILLIS) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun tap(label: String) {
        waitForText(label)
        runCatching { compose.onNodeWithText(label).performScrollTo() }
        compose.onNodeWithText(label).performClick()
    }

    private fun seed() = runBlocking {
        val id = (app.members.addMember(MemberDraft("Rahul Sharma", "9876543210", 1, today, smsConsent = true)) as MemberSaveResult.Saved).memberId
        app.members.recordRenewal(RenewalRequest(id, 1, 700, PaymentMode.CASH, today))
        app.settings.update { it.copy(lastBackupDay = app.fakeClock.today()) }
    }

    /** Every top-level screen, and the main ones off Settings, on whatever size this test set. */
    private fun visitEverything() {
        seed()
        compose.setContent { MbTheme { AppNavigation() } }
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodesWithContentDescription("Collected this month", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Today").performClick()
        waitForText("Share summary")
        compose.onNodeWithText("SMS").performClick()
        waitForText("Run reminders now")
        tap("Broadcast")
        waitForText("Who gets it".uppercase())
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Run reminders now")
        compose.onNodeWithText("Reports").performClick()
        waitForText("Collected, last 6 months")
        compose.onNodeWithText("Settings").performClick()
        waitForText("Plans and prices")
        listOf("Templates", "Quiet days", "Import members", "Backup and restore", "Health check", "Help", "About").forEach { row ->
            tap(row)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Back").performClick()
            waitForText("Plans and prices")
        }
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp")
    fun everyScreenWorksInLandscape() = visitEverything()

    @Test
    @Config(qualifiers = "w320dp-h568dp")
    fun everyScreenWorksOnASmallPhone() = visitEverything()

    @Test
    fun everyScreenWorksWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        visitEverything()
    }

    @Test
    @Config(qualifiers = "w673dp-h841dp")
    fun everyScreenWorksOnAFoldable() = visitEverything()

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
