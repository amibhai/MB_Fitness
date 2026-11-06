package com.swastik.mbfitness

import android.app.Application
import android.database.sqlite.SQLiteFullException
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * SPEC 14: a full phone mustn't crash the app. Any screen can hit this when it saves, so it's
 * caught in one place. It only steps in for a coroutine that failed: its thread carries on
 * afterwards, so the app can too. Anything else has already ended its thread, and a main thread
 * can't come back, so that goes on to Android as before. A real bug still crashes, so it's noticed.
 */
object StorageFullGuard {
    private const val MESSAGE = "Your phone's storage is full, so that wasn't saved. Free up some space and try again."
    private val failingCoroutine = ThreadLocal<Throwable>()

    fun isStorageFull(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { cause ->
        cause is SQLiteFullException ||
            (cause is IOException && (cause.message?.contains("ENOSPC") == true || cause.message?.contains("No space left") == true))
    }

    /** Called through [CoroutineFailureMarker], just before kotlinx.coroutines hands this failure to its thread. */
    internal fun markCoroutineFailure(error: Throwable) = failingCoroutine.set(error)

    fun handler(previous: Thread.UncaughtExceptionHandler?, onStorageFull: () -> Unit) =
        Thread.UncaughtExceptionHandler { thread, error ->
            val fromCoroutine = failingCoroutine.get() === error
            failingCoroutine.remove()
            if (fromCoroutine && isStorageFull(error)) onStorageFull() else previous?.uncaughtException(thread, error)
        }

    fun install(app: Application) {
        val main = Handler(Looper.getMainLooper())
        Thread.setDefaultUncaughtExceptionHandler(
            handler(Thread.getDefaultUncaughtExceptionHandler()) {
                main.post { Toast.makeText(app, MESSAGE, Toast.LENGTH_LONG).show() }
            },
        )
    }
}

/**
 * kotlinx.coroutines calls each handler listed in META-INF/services just before it hands a failed
 * coroutine to its thread. This one only notes which failure that is, for [StorageFullGuard]. It
 * stops nothing. It's found by registration, not by class name, so a release build's renaming
 * can't break it. proguard-rules.pro keeps it.
 */
class CoroutineFailureMarker : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        StorageFullGuard.markCoroutineFailure(exception)
    }
}
