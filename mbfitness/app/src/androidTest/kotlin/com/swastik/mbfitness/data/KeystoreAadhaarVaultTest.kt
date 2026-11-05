package com.swastik.mbfitness.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/** Runs on a device or emulator, because only those have a real Android Keystore. */
@RunWith(AndroidJUnit4::class)
class KeystoreAadhaarVaultTest {
    private val alias = "mbfitness_aadhaar_test"
    private val vault = KeystoreAadhaarVault(alias)

    // Made up: the 9999 prefix is the range UIDAI uses for test numbers.
    private val digits = "999900000012"

    private fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun theNumberComesBackExactly() {
        val sealed = vault.encrypt(digits)
        assertNotNull(sealed)
        assertEquals(digits, vault.decrypt(sealed!!))
    }

    @Test
    fun theCiphertextHidesTheNumber() {
        val sealed = vault.encrypt(digits)!!
        assertFalse(digits in sealed)
    }

    @Test
    fun encryptingTwiceGivesDifferentCiphertext() {
        assertNotEquals(vault.encrypt(digits), vault.encrypt(digits))
    }

    @Test
    fun aLostKeyGivesNullNotACrash() {
        val sealed = vault.encrypt(digits)!!
        deleteKey()
        assertNull(vault.decrypt(sealed))
    }

    @Test
    fun garbageGivesNullNotACrash() {
        vault.encrypt(digits)
        assertNull(vault.decrypt("not base64 !!"))
        assertNull(vault.decrypt("AAAA"))
        assertNull(vault.decrypt(""))
    }

    @Test
    fun ciphertextFromAnotherKeyGivesNull() {
        val sealed = vault.encrypt(digits)!!
        deleteKey()
        vault.encrypt(digits)
        assertNull(vault.decrypt(sealed))
    }
}
