package com.swastik.mbfitness.data

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
            testMode = true, packLanguage = PackLanguage.HINGLISH, pinHash = "h", pinSalt = "s", pinLength = 4,
            pinFailures = 2, pinLockedUntil = 9, biometric = true, recoveryCodeHash = "r", recoverySalt = "rs", backupFolderUri = "content://x", backupPasswordSet = true, backupPasswordHint = "hint",
            backupKeyWrapped = "k", backupSalt = "salt", lastBackupDay = 5, lastDailyRunDay = 6, lastDailyRunAt = 7,
            onboardingDone = true, setupDoneAt = 8, lastCopySentDay = 9, settingsReset = true,
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

    private fun rawStore(folder: File) =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { File(folder, "settings.preferences_pb") }

    /** A damaged file would stop the app opening at all. Setup runs again instead (docs/DECISIONS.md). */
    @Test
    fun aDamagedFileStartsAgainFromTheDefaultsAndSaysSo() = runTest {
        val folder = Files.createTempDirectory("mbf").toFile()
        File(folder, "settings.preferences_pb").writeBytes(byteArrayOf(0x0A, 0x7F, 0x01, 0x02, 0x03))
        val store = SettingsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { SettingsStore.afterDamage() },
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            ) { File(folder, "settings.preferences_pb") },
            forceTestMode = false,
        )
        val settings = store.current()
        assertTrue(settings.settingsReset)
        assertFalse(settings.onboardingDone)
        assertEquals("MB Fitness Club", settings.gymName)
        store.update { it.copy(gymName = "G") }
        assertEquals("G", store.current().gymName)
    }

    /** Written by a newer version, or damaged: each falls back to its default instead of crashing. */
    @Test
    fun valuesItCantReadFallBackToTheirDefaults() = runTest {
        val raw = rawStore(Files.createTempDirectory("mbf").toFile())
        raw.edit {
            it[stringPreferencesKey("renewalRule")] = "SOMETHING_NEW"
            it[stringPreferencesKey("packLanguage")] = "TAMIL"
            it[stringSetPreferencesKey("quietWeekdays")] = setOf("SUNDAY", "FUNDAY")
            it[intPreferencesKey("reminderHour")] = 25
            it[intPreferencesKey("reminderMinute")] = -1
            it[intPreferencesKey("dailyCapParts")] = 0
            it[intPreferencesKey("windowCapParts")] = -5
            it[intPreferencesKey("restartAfterDays")] = -3
        }
        // One DataStore per file, as Android requires.
        val settings = SettingsStore(raw, forceTestMode = false).current()
        assertEquals(RenewalRule.CONTINUE_FROM_EXPIRY, settings.renewalRule)
        assertEquals(PackLanguage.ENGLISH, settings.packLanguage)
        assertEquals(setOf(DayOfWeek.SUNDAY), settings.quietWeekdays)
        assertEquals(9, settings.reminderHour)
        assertEquals(0, settings.reminderMinute)
        assertEquals(90, settings.dailyCapParts)
        assertEquals(25, settings.windowCapParts)
        assertEquals(0, settings.restartAfterDays)
    }

    @Test
    fun aReminderTimeThatCouldNeverWorkFallsBackToTheDefault() = runTest {
        val raw = rawStore(Files.createTempDirectory("mbf").toFile())
        raw.edit {
            it[intPreferencesKey("reminderHour")] = 22
            it[intPreferencesKey("reminderMinute")] = 30
        }
        val settings = SettingsStore(raw, forceTestMode = false).current()
        assertEquals(9, settings.reminderHour)
        assertEquals(0, settings.reminderMinute)
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
