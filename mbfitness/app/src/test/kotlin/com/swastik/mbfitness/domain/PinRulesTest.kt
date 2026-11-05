package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinRulesTest {
    private val fast = 1_000

    @Test
    fun aPinIsFourToSixDigits() {
        assertTrue(PinRules.isValidPin("1234"))
        assertTrue(PinRules.isValidPin("123456"))
        assertFalse(PinRules.isValidPin("123"))
        assertFalse(PinRules.isValidPin("1234567"))
        assertFalse(PinRules.isValidPin("12a4"))
        assertFalse(PinRules.isValidPin(""))
    }

    @Test
    fun wrongGuessesSlowDownAfterTheFirstFew() {
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), (0..4).map(PinRules::waitSeconds))
        assertEquals(listOf(30L, 60L, 120L, 240L, 480L), (5..9).map(PinRules::waitSeconds))
        assertEquals(900L, PinRules.waitSeconds(10))
        assertEquals(900L, PinRules.waitSeconds(1_000))
    }

    @Test
    fun aRecoveryCodeIsEasyToCopyByHand() {
        repeat(200) {
            val code = PinRules.newRecoveryCode()
            assertTrue(code, Regex("""^[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}$""").matches(code))
        }
    }

    @Test
    fun recoveryCodesDiffer() {
        assertEquals(50, List(50) { PinRules.newRecoveryCode() }.toSet().size)
    }

    @Test
    fun aCopiedCodeMatchesWhateverItsCaseAndSpacing() {
        assertEquals("ABCDEFGH2345", PinRules.normalizeRecoveryCode(" abcd-efgh 2345 "))
    }

    @Test
    fun hashingIsRepeatableAndSalted() {
        val salt = SecretHasher.newSalt()
        val hash = SecretHasher.hash("1234", salt, fast)
        assertEquals(hash, SecretHasher.hash("1234", salt, fast))
        assertNotEquals(hash, SecretHasher.hash("1234", SecretHasher.newSalt(), fast))
        assertNotEquals(hash, SecretHasher.hash("1235", salt, fast))
        assertFalse("1234" in hash)
    }

    @Test
    fun matchingChecksTheSecret() {
        val salt = SecretHasher.newSalt()
        val hash = SecretHasher.hash("1234", salt, fast)
        assertTrue(SecretHasher.matches("1234", salt, hash, fast))
        assertFalse(SecretHasher.matches("0000", salt, hash, fast))
    }
}
