package com.swastik.mbfitness.data

import com.swastik.mbfitness.domain.PinRules
import com.swastik.mbfitness.domain.SecretHasher
import com.swastik.mbfitness.domain.TodayProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface PinCheck {
    data object Correct : PinCheck
    data class Wrong(val failures: Int, val lockedUntil: Long?) : PinCheck
    data class LockedOut(val until: Long) : PinCheck
    data object NoPinSet : PinCheck
}

/**
 * The PIN, the recovery code and whether the app is currently unlocked. Hashing is slow on
 * purpose, so it runs off the main thread. Wrong guesses are stored, so closing the app can't
 * skip the wait.
 */
class AppLock(
    private val settings: SettingsStore,
    private val clock: TodayProvider,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
) {
    private val unlockedFlow = MutableStateFlow(false)

    /** True once unlocked. Reads as locked at first, even with no PIN, until checked. */
    val unlocked: StateFlow<Boolean> = unlockedFlow.asStateFlow()

    private var backgroundedAt: Long? = null

    fun markUnlocked() {
        unlockedFlow.value = true
    }

    fun onBackgrounded() {
        backgroundedAt = clock.nowMillis()
    }

    /** A short trip to the contact picker or file picker shouldn't ask for the PIN again. */
    fun onForegrounded() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (clock.nowMillis() - since > graceMillis) unlockedFlow.value = false
    }

    suspend fun isPinSet(): Boolean = settings.current().pinHash != null

    /** Sets or changes the PIN. A new recovery code is only made if asked, and shown once. */
    suspend fun setPin(pin: String, newRecoveryCode: Boolean): String? {
        require(PinRules.isValidPin(pin)) { "A PIN must be ${PinRules.MIN_DIGITS} to ${PinRules.MAX_DIGITS} digits" }
        return withContext(Dispatchers.Default) {
            val salt = SecretHasher.newSalt()
            val hash = SecretHasher.hash(pin, salt)
            val code = if (newRecoveryCode) PinRules.newRecoveryCode() else null
            val codeHash = code?.let { SecretHasher.hash(PinRules.normalizeRecoveryCode(it), salt) }
            settings.update { current ->
                val keepsOldCode = code == null && current.recoveryCodeHash != null && current.pinSalt != null
                current.copy(
                    pinHash = hash,
                    pinSalt = salt,
                    pinLength = pin.length,
                    pinFailures = 0,
                    pinLockedUntil = null,
                    recoveryCodeHash = codeHash ?: current.recoveryCodeHash,
                    recoverySalt = if (keepsOldCode) current.recoverySalt ?: current.pinSalt else salt,
                )
            }
            code
        }
    }

    /** The old code stops working. The new one is shown once, and only a hash is kept. */
    suspend fun replaceRecoveryCode(): String = withContext(Dispatchers.Default) {
        val salt = SecretHasher.newSalt()
        val code = PinRules.newRecoveryCode()
        val hash = SecretHasher.hash(PinRules.normalizeRecoveryCode(code), salt)
        settings.update { it.copy(recoveryCodeHash = hash, recoverySalt = salt) }
        code
    }

    suspend fun removePin() {
        settings.update {
            it.copy(
                pinHash = null,
                pinSalt = null,
                pinLength = null,
                pinFailures = 0,
                pinLockedUntil = null,
                recoveryCodeHash = null,
                recoverySalt = null,
                biometric = false,
            )
        }
    }

    suspend fun checkPin(pin: String): PinCheck {
        val current = settings.current()
        val hash = current.pinHash ?: return PinCheck.NoPinSet
        val salt = current.pinSalt ?: return PinCheck.NoPinSet
        val now = clock.nowMillis()
        PinRules.activeLockout(current.pinLockedUntil, now)?.let { return PinCheck.LockedOut(it) }

        val correct = withContext(Dispatchers.Default) { SecretHasher.matches(pin, salt, hash) }
        return if (correct) {
            // Unlocked first: a full phone can't keep out someone who knows the PIN.
            unlockedFlow.value = true
            try {
                settings.update { it.copy(pinFailures = 0, pinLockedUntil = null) }
            } catch (_: IOException) {
                // Only the count of wrong guesses is out of date, and the next right PIN clears it.
            }
            PinCheck.Correct
        } else {
            val failures = current.pinFailures + 1
            val wait = PinRules.waitSeconds(failures)
            val until = if (wait > 0) now + wait * MILLIS_PER_SECOND else null
            settings.update { it.copy(pinFailures = failures, pinLockedUntil = until) }
            PinCheck.Wrong(failures, until)
        }
    }

    suspend fun checkRecoveryCode(code: String): Boolean {
        val current = settings.current()
        val hash = current.recoveryCodeHash ?: return false
        val salt = current.recoverySalt ?: current.pinSalt ?: return false
        return withContext(Dispatchers.Default) {
            SecretHasher.matches(PinRules.normalizeRecoveryCode(code), salt, hash)
        }
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 60_000L
        private const val MILLIS_PER_SECOND = 1000L
    }
}
