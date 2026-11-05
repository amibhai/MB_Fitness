package com.swastik.mbfitness.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.domain.PackLanguage
import com.swastik.mbfitness.domain.RenewalRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private fun store(force: Boolean = false, folder: File = Files.createTempDirectory("mbf").toFile()) = SettingsStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            File(folder, "settings.preferences_pb")
        },
        forceTestMode = force,
    )

    @Test
    fun aFreshInstallStartsWithTheSpecsDefaults() = runTest {
        val settings = store().current()
        assertEquals("MB Fitness Club", settings.gymName)
        assertEquals("Jeetu Bhaiya", settings.ownerName)
        assertEquals("JAI SHREE RAM", settings.greeting)
        assertEquals(9, settings.reminderHour)
        assertEquals(0, settings.reminderMinute)
        assertEquals(90, settings.dailyCapParts)
        assertEquals(25, settings.windowCapParts)
        assertEquals(RenewalRule.CONTINUE_FROM_EXPIRY, settings.renewalRule)
        assertEquals(60, settings.restartAfterDays)
        assertEquals(setOf(DayOfWeek.SUNDAY), settings.quietWeekdays)
        assertTrue(settings.morningSummary)
        assertFalse(settings.testMode)
        assertFalse(settings.onboardingDone)
    }

    @Test
    fun everyFieldSurvivesARoundTrip() = runTest {
        val store = store()
        val changed = AppSettings(
            gymName = "G", ownerName = "O", greeting = "H", trainerPhone = "+919876543210", upiId = "u@b",
            upiPayeeName = "P", logoFile = "logo.png", reminderHour = 8, reminderMinute = 30, dailyCapParts = 50,
            windowCapParts = 20, simSubscriptionId = 3, renewalRule = RenewalRule.START_FROM_PAYMENT, restartAfterDays = 45,
            quietWeekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), morningSummary = false, allClearNotice = false,
            testMode = true, packLanguage = PackLanguage.HINGLISH, pinHash = "h", pinSalt = "s", biometric = true,
            recoveryCodeHash = "r", backupFolderUri = "content://x", backupPasswordSet = true, backupPasswordHint = "hint",
            backupKeyWrapped = "k", backupSalt = "salt", lastBackupDay = 5, lastDailyRunDay = 6, lastDailyRunAt = 7,
            onboardingDone = true,
        )
        store.update { changed }
        assertEquals(changed, store.current())
    }

    @Test
    fun clearingAnOptionalFieldRemovesIt() = runTest {
        val store = store()
        store.update { it.copy(pinHash = "h", simSubscriptionId = 3) }
        store.update { it.copy(pinHash = null, simSubscriptionId = null) }
        assertNull(store.current().pinHash)
        assertNull(store.current().simSubscriptionId)
    }

    @Test
    fun anUpdateOnlyChangesWhatItTouches() = runTest {
        val store = store()
        store.update { it.copy(gymName = "G") }
        store.update { it.copy(upiId = "u@b") }
        assertEquals("G", store.current().gymName)
        assertEquals("u@b", store.current().upiId)
    }

    @Test
    fun debugBuildsAreAlwaysInTestMode() = runTest {
        val forced = store(force = true)
        assertTrue(forced.isTestMode(forced.current()))
        val normal = store(force = false)
        assertFalse(normal.isTestMode(normal.current()))
        assertTrue(normal.isTestMode(normal.current().copy(testMode = true)))
    }
}
