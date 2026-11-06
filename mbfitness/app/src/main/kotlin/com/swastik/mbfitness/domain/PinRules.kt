package com.swastik.mbfitness.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The app lock's rules (SPEC 4-G). The PIN is never stored in plain text (SPEC 5.2), only as a
 * salted PBKDF2 hash, and repeated wrong guesses slow down.
 */
object PinRules {
    const val MIN_DIGITS = 4
    const val MAX_DIGITS = 6
    private const val FREE_ATTEMPTS = 5
    private const val FIRST_WAIT_SECONDS = 30L
    private const val MAX_WAIT_SECONDS = 15L * 60

    /** No 0, O, 1, I or L, so it can be copied off paper without mistakes. */
    private const val RECOVERY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val RECOVERY_LENGTH = 12
    private const val RECOVERY_GROUP = 4

    fun isValidPin(pin: String): Boolean = pin.length in MIN_DIGITS..MAX_DIGITS && pin.all { it in '0'..'9' }

    /** Seconds to wait after this many wrong guesses in a row: 0, then 30 s doubling to 15 min. */
    fun waitSeconds(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0
        val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(MAX_DOUBLINGS)
        return (FIRST_WAIT_SECONDS shl doublings).coerceAtMost(MAX_WAIT_SECONDS)
    }

    private const val MAX_DOUBLINGS = 10

    /**
     * The wait still to sit out, if any. One further away than the longest possible wait means
     * the clock moved after it was set, and that can't lock bhaiya out for years.
     */
    fun activeLockout(lockedUntil: Long?, nowMillis: Long): Long? =
        lockedUntil?.takeIf { it > nowMillis && it - nowMillis <= MAX_WAIT_SECONDS * MILLIS_PER_SECOND }

    private const val MILLIS_PER_SECOND = 1000L

    /** "ABCD-EFGH-JKMN": shown once, for the owner to write down (SPEC 11.1). */
    fun newRecoveryCode(random: SecureRandom = SecureRandom()): String {
        val raw = String(CharArray(RECOVERY_LENGTH) { RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)] })
        return raw.chunked(RECOVERY_GROUP).joinToString("-")
    }

    /** Forgiving about case and spacing, since it was copied by hand. */
    fun normalizeRecoveryCode(text: String): String =
        text.uppercase().filter { it in RECOVERY_ALPHABET }
}

/** Salted, slow hashing with built-in crypto only (hard rule 8). */
object SecretHasher {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun newSalt(random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return Base64.getEncoder().encodeToString(salt)
    }

    fun hash(secret: String, salt: String, iterations: Int = ITERATIONS): String {
        val spec = PBEKeySpec(secret.toCharArray(), Base64.getDecoder().decode(salt), iterations, KEY_BITS)
        try {
            val derived = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
            return Base64.getEncoder().encodeToString(derived)
        } finally {
            spec.clearPassword()
        }
    }

    /** Takes the same time wherever the first difference is. */
    fun matches(secret: String, salt: String, expectedHash: String, iterations: Int = ITERATIONS): Boolean =
        MessageDigest.isEqual(
            hash(secret, salt, iterations).toByteArray(Charsets.US_ASCII),
            expectedHash.toByteArray(Charsets.US_ASCII),
        )
}
