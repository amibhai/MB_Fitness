package com.swastik.mbfitness.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Turns an Aadhaar number into ciphertext for storage and back (SPEC 7.10). Both directions
 * return null rather than throw: a Keystore problem must never crash the app or stop a
 * member being saved. Implementations must never log the value (hard rule 11).
 */
interface AadhaarVault {
    fun encrypt(digits: String): String?
    fun decrypt(cipherText: String): String?
}

/**
 * AES-256-GCM under a key held by the Android Keystore, which can't be copied off the phone.
 * That means an Android auto-backup only carries ciphertext that nothing else can open, and
 * also that restoring on a new phone leaves the last 4 digits only (SPEC 12).
 */
class KeystoreAadhaarVault(private val alias: String = DEFAULT_ALIAS) : AadhaarVault {
    override fun encrypt(digits: String): String? = guarded {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyForEncrypting())
        val sealed = cipher.iv + cipher.doFinal(digits.toByteArray(Charsets.US_ASCII))
        Base64.getEncoder().encodeToString(sealed)
    }

    override fun decrypt(cipherText: String): String? = guarded {
        val key = existingKey() ?: return null
        val sealed = Base64.getDecoder().decode(cipherText)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.US_ASCII)
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return keyStore.getKey(alias, null) as? SecretKey
    }

    private fun keyForEncrypting(): SecretKey = existingKey() ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        generator.generateKey()
    }

    /** Catches what the Keystore throws, including its unchecked ProviderException. */
    private inline fun guarded(block: () -> String?): String? = try {
        block()
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: ProviderException) {
        null
    } catch (_: java.io.IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val DEFAULT_ALIAS = "mbfitness_aadhaar_v1"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
