package com.swastik.mbfitness.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.domain.PinRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class AppLockTest {
    private val env = TestEnvironment()
    private val lock = AppLock(env.settings, env.clock)

    @After
    fun tearDown() = env.close()

    @Test
    fun withNoPinThereIsNothingToCheck() = runTest {
        assertFalse(lock.isPinSet())
        assertEquals(PinCheck.NoPinSet, lock.checkPin("1234"))
    }

    @Test
    fun thePinIsNeverStoredInPlainText() = runTest {
        lock.setPin("739182", newRecoveryCode = true)
        val settings = env.settings.current()
        assertNotNull(settings.pinHash)
        assertFalse("739182" in settings.toString())
        assertEquals(6, settings.pinLength)
    }

    @Test
    fun theRightPinUnlocks() = runTest {
        lock.setPin("1234", newRecoveryCode = true)
        assertFalse(lock.unlocked.value)
        assertEquals(PinCheck.Correct, lock.checkPin("1234"))
        assertTrue(lock.unlocked.value)
    }

    @Test
    fun aWrongPinCountsAndDoesNotUnlock() = runTest {
        lock.setPin("1234", newRecoveryCode = true)
        assertEquals(PinCheck.Wrong(1, null), lock.checkPin("0000"))
        assertFalse(lock.unlocked.value)
        assertEquals(1, env.settings.current().pinFailures)
    }

    @Test
    fun theRightPinClearsTheCount() = runTest {
        lock.setPin("1234", newRecoveryCode = true)
        lock.checkPin("0000")
        lock.checkPin("1234")
        assertEquals(0, env.settings.current().pinFailures)
    }

    @Test
    fun fiveWrongGuessesLockEvenTheRightPinOutForAWhile() = runTest {
        lock.setPin("1234", newRecoveryCode = true)
        repeat(4) { lock.checkPin("0000") }
        val fifth = lock.checkPin("0000") as PinCheck.Wrong
        assertNotNull(fifth.lockedUntil)
        assertTrue(lock.checkPin("1234") is PinCheck.LockedOut)
        assertFalse(lock.unlocked.value)
        env.clock.advance(31_000)
        assertEquals(PinCheck.Correct, lock.checkPin("1234"))
    }

    @Test
    fun closingTheAppDoesNotSkipTheWait() = runTest {
        lock.setPin("1234", newRecoveryCode = true)
        repeat(5) { lock.checkPin("0000") }
        val reopened = AppLock(env.settings, env.clock)
        assertTrue(reopened.checkPin("1234") is PinCheck.LockedOut)
    }

    @Test
    fun aShortTripAwayKeepsTheAppUnlocked() = runTest {
        lock.setPin("1234", newRecoveryCode = true)
        lock.checkPin("1234")
        lock.onBackgrounded()
        env.clock.advance(AppLock.DEFAULT_GRACE_MILLIS - 1_000)
        lock.onForegrounded()
        assertTrue(lock.unlocked.value)
    }

    @Test
    fun aLongerTimeAwayLocksTheAppAgain() = runTest {
        lock.setPin("1234", newRecoveryCode = true)
        lock.checkPin("1234")
        lock.onBackgrounded()
        env.clock.advance(AppLock.DEFAULT_GRACE_MILLIS + 1_000)
        lock.onForegrounded()
        assertFalse(lock.unlocked.value)
    }

    @Test
    fun theRecoveryCodeWorksOnlyForItself() = runTest {
        val code = lock.setPin("1234", newRecoveryCode = true)!!
        assertTrue(lock.checkRecoveryCode(code))
        assertTrue(lock.checkRecoveryCode(code.lowercase().replace("-", " ")))
        assertFalse(lock.checkRecoveryCode(PinRules.newRecoveryCode()))
    }

    @Test
    fun changingThePinKeepsTheCodeThatWasWrittenDown() = runTest {
        val code = lock.setPin("1234", newRecoveryCode = true)!!
        assertNull(lock.setPin("5678", newRecoveryCode = false))
        assertTrue(lock.checkRecoveryCode(code))
        assertEquals(PinCheck.Correct, lock.checkPin("5678"))
        assertNotEquals(PinCheck.Correct, AppLock(env.settings, env.clock).checkPin("1234"))
    }

    @Test
    fun removingThePinClearsEverything() = runTest {
        val code = lock.setPin("1234", newRecoveryCode = true)!!
        env.settings.update { it.copy(biometric = true) }
        lock.removePin()
        val settings = env.settings.current()
        assertNull(settings.pinHash)
        assertNull(settings.recoveryCodeHash)
        assertFalse(settings.biometric)
        assertFalse(lock.checkRecoveryCode(code))
    }

    @Test
    fun aNewRecoveryCodeReplacesTheOldOne() = runTest {
        val old = lock.setPin("1234", newRecoveryCode = true)!!
        val fresh = lock.replaceRecoveryCode()
        assertFalse(lock.checkRecoveryCode(old))
        assertTrue(lock.checkRecoveryCode(fresh))
    }

    /** The count of wrong guesses can't be saved on a full phone, and that can't keep him out. */
    @Test
    fun aFullPhoneCantKeepOutSomeoneWhoKnowsThePin() = runTest {
        val real = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            File(Files.createTempDirectory("mbf").toFile(), "settings.preferences_pb")
        }
        var full = false
        val disk = object : DataStore<Preferences> {
            override val data = real.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (full) throw IOException("write failed: ENOSPC (No space left on device)")
                return real.updateData(transform)
            }
        }
        val fullLock = AppLock(SettingsStore(disk, forceTestMode = false), env.clock)
        fullLock.setPin("1234", newRecoveryCode = true)
        full = true
        assertEquals(PinCheck.Correct, fullLock.checkPin("1234"))
        assertTrue(fullLock.unlocked.value)
    }

    /** A clock that was wrong when the wait was set can't lock bhaiya out for years. */
    @Test
    fun aWaitLongerThanAnyRealOneIsIgnored() = runTest {
        lock.setPin("1234", newRecoveryCode = true)
        val aYear = 365L * 24 * 60 * 60 * 1000
        env.settings.update { it.copy(pinFailures = 7, pinLockedUntil = env.clock.nowMillis() + aYear) }
        assertEquals(PinCheck.Correct, lock.checkPin("1234"))
    }

    @Test
    fun aRealWaitStillHolds() = runTest {
        lock.setPin("1234", newRecoveryCode = true)
        env.settings.update { it.copy(pinFailures = 5, pinLockedUntil = env.clock.nowMillis() + 30_000) }
        assertTrue(lock.checkPin("1234") is PinCheck.LockedOut)
    }
}
