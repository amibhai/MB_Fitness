package com.swastik.mbfitness

import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineExceptionHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.ServiceLoader
import kotlin.coroutines.EmptyCoroutineContext

/**
 * SPEC 14: no crash on a full phone. The handler is called directly here, so no test ever ends a
 * real thread or leaves a failed coroutine behind for the next test to trip over.
 */
@RunWith(AndroidJUnit4::class)
class StorageFullGuardTest {
    private val full = SQLiteFullException("database or disk is full")
    private val bug = IllegalStateException("A real bug")
    private val passedOn = mutableListOf<Throwable>()
    private var messages = 0
    private val handler = StorageFullGuard.handler({ _, error -> passedOn += error }) { messages++ }
    private val thread = Thread.currentThread()

    /** What kotlinx.coroutines does just before it hands a failed coroutine to its thread. */
    private fun failedInACoroutine(error: Throwable) = CoroutineFailureMarker().handleException(EmptyCoroutineContext, error)

    @Test
    fun aFullPhoneIsRecognised() {
        assertTrue(StorageFullGuard.isStorageFull(full))
        assertTrue(StorageFullGuard.isStorageFull(RuntimeException("wrapped", full)))
        assertTrue(StorageFullGuard.isStorageFull(IOException("write failed: ENOSPC (No space left on device)")))
        assertFalse(StorageFullGuard.isStorageFull(IOException("Broken pipe")))
        assertFalse(StorageFullGuard.isStorageFull(bug))
    }

    @Test
    fun aSaveThatFailsOnAFullPhoneSaysSoAndTheAppCarriesOn() {
        failedInACoroutine(full)
        handler.uncaughtException(thread, full)
        assertEquals(1, messages)
        assertTrue(passedOn.isEmpty())
    }

    @Test
    fun aRealBugStillCrashesSoItGetsNoticed() {
        failedInACoroutine(bug)
        handler.uncaughtException(thread, bug)
        assertEquals(listOf<Throwable>(bug), passedOn)
        assertEquals(0, messages)
    }

    /** Its thread has already ended, and a main thread can't come back. */
    @Test
    fun aFullPhoneOutsideACoroutineStillCrashes() {
        handler.uncaughtException(thread, full)
        assertEquals(listOf<Throwable>(full), passedOn)
        assertEquals(0, messages)
    }

    @Test
    fun theMarkOnlyCoversThatFailure() {
        failedInACoroutine(SQLiteFullException("an earlier one"))
        handler.uncaughtException(thread, full)
        assertEquals(listOf<Throwable>(full), passedOn)
    }

    @Test
    fun theMarkOnlyCoversThatThread() {
        Thread { failedInACoroutine(full) }.apply {
            start()
            join()
        }
        handler.uncaughtException(thread, full)
        assertEquals(listOf<Throwable>(full), passedOn)
    }

    @Test
    fun theMarkIsUsedOnce() {
        failedInACoroutine(full)
        handler.uncaughtException(thread, full)
        handler.uncaughtException(thread, full)
        assertEquals(1, messages)
        assertEquals(1, passedOn.size)
    }

    @Test
    fun withNothingBeforeItTheGuardDoesntFail() {
        StorageFullGuard.handler(null) { messages++ }.uncaughtException(thread, bug)
        assertEquals(0, messages)
    }

    /** The same lookup kotlinx.coroutines does. Without the registration, the guard never acts. */
    @Test
    fun kotlinCoroutinesCanFindTheMarker() {
        val found = ServiceLoader.load(CoroutineExceptionHandler::class.java, CoroutineExceptionHandler::class.java.classLoader).toList()
        assertTrue(found.map { it.javaClass.name }.toString(), found.any { it is CoroutineFailureMarker })
    }
}
