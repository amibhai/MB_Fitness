package com.swastik.mbfitness

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What happens to a failure nothing else caught (SPEC 14, docs/DECISIONS.md). Swastik won't
 * always be around to fix things, so the app keeps going wherever that's safe, and keeps the
 * details for him in the error log.
 *
 * A screen's coroutine (a save, a load) leaves its thread running when it fails, so the app
 * carries on: a full phone gets its own message, anything else a general one. Two kinds still
 * close the app, which then opens fresh. A failure inside Compose itself stops the screen
 * drawing, so carrying on would leave it frozen. And an Error, such as running out of memory,
 * leaves nothing safe to carry on with. Anything outside a coroutine has already ended its
 * thread, and a main thread can't come back: its details wait in a file for the next start.
 */
object FailureGuard {
    const val STORAGE_FULL = "Your phone's storage is full, so that wasn't saved. Free up some space and try again."
    const val WENT_WRONG = "Something went wrong, so that didn't work. Please try again."
    private val failingCoroutine = ThreadLocal<Throwable>()

    fun isStorageFull(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { cause ->
        cause is SQLiteFullException ||
            (cause is IOException && (cause.message?.contains("ENOSPC") == true || cause.message?.contains("No space left") == true))
    }

    /** What a person sees when a screen's work fails. */
    fun messageFor(error: Throwable): String = if (isStorageFull(error)) STORAGE_FULL else WENT_WRONG

    /** Called through [CoroutineFailureMarker], just before kotlinx.coroutines hands this failure to its thread. */
    internal fun markCoroutineFailure(context: CoroutineContext, error: Throwable) {
        // Compose's own coroutines carry its frame clock. One of those failing stops the screen drawing.
        if (context[MonotonicFrameClock] == null) failingCoroutine.set(error)
    }

    /**
     * [onRecovered] gets a screen's failure the app carries on from. [onFatal] gets everything
     * else, just before Android closes the app.
     */
    fun handler(
        previous: Thread.UncaughtExceptionHandler?,
        onFatal: (Thread, Throwable) -> Unit = { _, _ -> },
        onRecovered: (Throwable) -> Unit,
    ) = Thread.UncaughtExceptionHandler { thread, error ->
        val fromCoroutine = failingCoroutine.get() === error
        failingCoroutine.remove()
        if (fromCoroutine && error is Exception) {
            onRecovered(error)
        } else {
            // Nothing here may stop Android closing the app as it should.
            try {
                onFatal(thread, error)
            } catch (_: Throwable) {
                // The crash itself matters more than its report.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun install(app: App) {
        Thread.setDefaultUncaughtExceptionHandler(
            handler(
                Thread.getDefaultUncaughtExceptionHandler(),
                onFatal = { thread, error -> app.recordCrash(thread, error) },
            ) { error -> app.onScreenFailure(error) },
        )
    }
}

/**
 * Starts a screen's own small job, such as Undo or saving a plan, from Compose's scope. A failure
 * there would stop the screen drawing, which [FailureGuard] can't safely recover from, so it's
 * caught here instead and handled like any screen failure: a message, the details in the error
 * log, and the app carries on.
 */
fun CoroutineScope.launchSafely(context: Context, block: suspend CoroutineScope.() -> Unit): Job = launch {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        (context.applicationContext as App).onScreenFailure(error)
    }
}

/**
 * kotlinx.coroutines calls each handler listed in META-INF/services just before it hands a failed
 * coroutine to its thread. This one only notes which failure that is, for [FailureGuard]. It
 * stops nothing. It's found by registration, not by class name, so a release build's renaming
 * can't break it. proguard-rules.pro keeps it.
 */
class CoroutineFailureMarker : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        FailureGuard.markCoroutineFailure(context, exception)
    }
}
