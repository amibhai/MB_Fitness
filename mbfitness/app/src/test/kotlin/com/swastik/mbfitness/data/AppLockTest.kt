package com.swastik.mbfitness.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.domain.PinRules
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
}
