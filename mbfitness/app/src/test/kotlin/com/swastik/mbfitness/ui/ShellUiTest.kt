package com.swastik.mbfitness.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.data.AppLock
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.data.TestEnvironment
import com.swastik.mbfitness.ui.components.DueBadge
import com.swastik.mbfitness.ui.components.EmptyState
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.screens.home.HomeHeader
import com.swastik.mbfitness.ui.screens.home.StatTiles
import com.swastik.mbfitness.ui.screens.lock.LockScreen
import com.swastik.mbfitness.ui.screens.lock.LockViewModel
import com.swastik.mbfitness.ui.screens.lock.SetPinScreen
import com.swastik.mbfitness.ui.screens.lock.SetPinStage
import com.swastik.mbfitness.ui.screens.lock.SetPinViewModel
import com.swastik.mbfitness.ui.screens.splash.BrandSplash
import com.swastik.mbfitness.ui.theme.MbTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A phone-sized window, because the default one is too short to show the whole PIN pad. */
@Config(qualifiers = "w411dp-h891dp")
@RunWith(AndroidJUnit4::class)
class ShellUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val env = TestEnvironment()

    @After
    fun tearDown() = env.close()

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun press(digits: String) = digits.forEach { compose.onNodeWithText(it.toString()).performClick() }

    @Test
    fun theSplashHasEverythingTheSpecAsksFor() {
        compose.setContent { MbTheme { BrandSplash(AppSettings()) } }
        listOf("JAI SHREE RAM", "MB Fitness Club", "Jeetu Bhaiya", "App by Swastik").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("MB Fitness Club logo").assertExists()
    }

    @Test
    fun theHeaderShowsTheGreetingOwnerGymAndDate() {
        compose.setContent { MbTheme { HomeHeader(AppSettings(), today = 20761, testMode = false) } }
        compose.onNodeWithText("JAI SHREE RAM").assertIsDisplayed()
        compose.onNodeWithText("Jeetu Bhaiya").assertIsDisplayed()
        compose.onAllNodesWithText("TEST MODE").assertCountEquals(0)
    }

    @Test
    fun theRibbonShowsInTestMode() {
        compose.setContent { MbTheme { HomeHeader(AppSettings(), today = 20761, testMode = true) } }
        compose.onNodeWithText("TEST MODE").assertIsDisplayed()
    }

    @Test
    fun moneyIsReadAsRupeesByTalkBack() {
        compose.setContent {
            MbTheme {
                StatTiles(collected = 1200, pending = 700, active = 3)
                DueBadge(700)
            }
        }
        compose.onNodeWithContentDescription("Collected this month: 1200 rupees").assertExists()
        compose.onNodeWithContentDescription("Pending: 700 rupees").assertExists()
        compose.onNodeWithContentDescription("Active: 3").assertExists()
        compose.onNodeWithContentDescription("700 rupees due").assertExists()
    }

    @Test
    fun theBackButtonHasALabel() {
        var clicked = false
        compose.setContent { MbTheme { MbTopBar("Title", onBack = { clicked = true }) } }
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(clicked)
    }

    @Test
    fun anEmptyStateOffersItsAction() {
        var clicked = false
        compose.setContent { MbTheme { EmptyState("Nothing here.", actionLabel = "Add", onAction = { clicked = true }) } }
        compose.onNodeWithText("Nothing here.").assertIsDisplayed()
        compose.onNodeWithText("Add").performClick()
        assertTrue(clicked)
    }

    private fun showLock(): AppLock {
        val lock = AppLock(env.settings, env.clock)
        runBlocking { lock.setPin("1234", newRecoveryCode = true) }
        val viewModel = LockViewModel(lock, env.settings, env.clock)
        compose.setContent {
            MbTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LockScreen(state, viewModel)
            }
        }
        waitForText("Forgot PIN")
        return lock
    }

    @Test
    fun theLockScreenUnlocksWithTheRightPin() {
        val lock = showLock()
        compose.onNodeWithContentDescription("Delete last digit").assertExists()
        press("1234")
        compose.waitUntil(10_000) {
            // Fetching nodes also runs the main thread, which the unlock needs to finish.
            compose.onAllNodesWithText("Enter your PIN").fetchSemanticsNodes()
            lock.unlocked.value
        }
    }

    @Test
    fun theLockScreenRefusesTheWrongPin() {
        val lock = showLock()
        press("0000")
        waitForText("Wrong PIN. Try again.")
        assertFalse(lock.unlocked.value)
    }

    @Test
    fun theDeleteKeyRemovesADigit() {
        val lock = showLock()
        press("123")
        compose.onNodeWithContentDescription("3 of 4 digits entered").assertExists()
        compose.onNodeWithContentDescription("Delete last digit").performClick()
        compose.onNodeWithContentDescription("2 of 4 digits entered").assertExists()
        assertFalse(lock.unlocked.value)
    }

    @Test
    fun forgotPinOffersTheRecoveryCodeAndAWayBack() {
        showLock()
        compose.onNodeWithText("Forgot PIN").performClick()
        compose.onNodeWithText("Enter your recovery code").assertIsDisplayed()
        compose.onNodeWithText("Back to PIN").performClick()
        compose.onNodeWithText("Enter your PIN").assertIsDisplayed()
    }

    private fun showSetPin(): SetPinViewModel {
        val viewModel = SetPinViewModel(AppLock(env.settings, env.clock), env.settings)
        compose.setContent {
            MbTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                SetPinScreen(state, viewModel, onBack = {})
            }
        }
        return viewModel
    }

    @Test
    fun choosingAPinShowsTheRecoveryCodeOnce() {
        val viewModel = showSetPin()
        press("1234")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Enter it once more").assertIsDisplayed()
        press("1234")
        waitForText("Write this down")
        compose.onNodeWithText("I wrote it down").performClick()
        compose.waitUntil(5_000) { viewModel.uiState.value.stage == SetPinStage.DONE }
        assertEquals(null, viewModel.uiState.value.recoveryCode)
        assertTrue(runBlocking { env.settings.current().pinHash != null })
    }

    @Test
    fun aMismatchStartsOver() {
        showSetPin()
        press("1234")
        compose.onNodeWithText("Next").performClick()
        press("1235")
        waitForText("Those didn't match. Try again.")
        compose.onNodeWithText("Choose a PIN").assertIsDisplayed()
        assertFalse(runBlocking { env.settings.current().pinHash != null })
    }

    @Test
    fun aTooShortPinIsRefused() {
        showSetPin()
        press("12")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("A PIN must be 4 to 6 digits").assertIsDisplayed()
    }
}
