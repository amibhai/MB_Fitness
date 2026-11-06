package com.swastik.mbfitness

import android.database.sqlite.SQLiteFullException
import androidx.compose.runtime.MonotonicFrameClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineExceptionHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.ServiceLoader
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * SPEC 14 and docs/DECISIONS.md: a screen's failure doesn't close the app. The handler is called
 * directly here, so no test ever ends a real thread or leaves a failed coroutine behind.
 */
@RunWith(AndroidJUnit4::class)
class FailureGuardTest {
    private val full = SQLiteFullException("database or disk is full")
    private val bug = IllegalStateException("A real bug")
    private val passedOn = mutableListOf<Throwable>()
    private val recovered = mutableListOf<Throwable>()
    private val fatal = mutableListOf<Throwable>()
    private val handler = FailureGuard.handler({ _, error -> passedOn += error }, { _, error -> fatal += error }) { recovered += it }
    private val thread = Thread.currentThread()

    /** Compose's coroutines carry one of these. */
    private val composeContext: CoroutineContext = object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = onFrame(0)
    }

    /** What kotlinx.coroutines does just before it hands a failed coroutine to its thread. */
    private fun failedInACoroutine(error: Throwable, context: CoroutineContext = EmptyCoroutineContext) =
        CoroutineFailureMarker().handleException(context, error)

    @Test
    fun aFullPhoneIsRecognised() {
        assertTrue(FailureGuard.isStorageFull(full))
        assertTrue(FailureGuard.isStorageFull(RuntimeException("wrapped", full)))
        assertTrue(FailureGuard.isStorageFull(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(FailureGuard.STORAGE_FULL, FailureGuard.messageFor(full))
        assertEquals(FailureGuard.WENT_WRONG, FailureGuard.messageFor(IOException("Broken pipe")))
        assertEquals(FailureGuard.WENT_WRONG, FailureGuard.messageFor(bug))
    }

    @Test
    fun aSaveThatFailsOnAFullPhoneSaysSoAndTheAppCarriesOn() {
        failedInACoroutine(full)
        handler.uncaughtException(thread, full)
        assertEquals(listOf<Throwable>(full), recovered)
        assertTrue(passedOn.isEmpty())
        assertTrue(fatal.isEmpty())
    }

    @Test
    fun aBugInAScreensWorkIsLoggedAndTheAppCarriesOn() {
        failedInACoroutine(bug)
        handler.uncaughtException(thread, bug)
        assertEquals(listOf<Throwable>(bug), recovered)
        assertTrue(passedOn.isEmpty())
    }

    /** Carrying on would leave the screen frozen, so the app closes and opens fresh. */
    @Test
    fun aFailureInsideComposeStillClosesTheApp() {
        failedInACoroutine(bug, composeContext)
        handler.uncaughtException(thread, bug)
        assertEquals(listOf<Throwable>(bug), passedOn)
        assertEquals(listOf<Throwable>(bug), fatal)
        assertTrue(recovered.isEmpty())
    }

    /** Running out of memory leaves nothing safe to carry on with. */
    @Test
    fun anErrorStillClosesTheApp() {
        val oom = OutOfMemoryError("test")
        failedInACoroutine(oom)
        handler.uncaughtException(thread, oom)
        assertEquals(listOf<Throwable>(oom), passedOn)
        assertTrue(recovered.isEmpty())
    }

    /** Its thread has already ended, and a main thread can't come back. Its details are kept. */
    @Test
    fun aFailureOutsideACoroutineStillClosesTheAppAndIsReported() {
        handler.uncaughtException(thread, full)
        assertEquals(listOf<Throwable>(full), passedOn)
        assertEquals(listOf<Throwable>(full), fatal)
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun aBrokenReportCantStopTheAppClosing() {
        val failingReport = FailureGuard.handler({ _, error -> passedOn += error }, { _, _ -> error("no disk") }) { recovered += it }
        failingReport.uncaughtException(thread, bug)
        assertEquals(listOf<Throwable>(bug), passedOn)
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
        assertEquals(1, recovered.size)
        assertEquals(1, passedOn.size)
    }

    @Test
    fun withNothingBeforeItTheGuardDoesntFail() {
        FailureGuard.handler(null) { recovered += it }.uncaughtException(thread, bug)
        assertTrue(recovered.isEmpty())
    }

    /** The same lookup kotlinx.coroutines does. Without the registration, the guard never acts. */
    @Test
    fun kotlinCoroutinesCanFindTheMarker() {
        val found = ServiceLoader.load(CoroutineExceptionHandler::class.java, CoroutineExceptionHandler::class.java.classLoader).toList()
        assertTrue(found.map { it.javaClass.name }.toString(), found.any { it is CoroutineFailureMarker })
    }
}
