package com.swastik.mbfitness.ui

import android.content.ClipboardManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.FailureGuard
import com.swastik.mbfitness.MainActivity
import com.swastik.mbfitness.RecoveryActivity
import com.swastik.mbfitness.launchSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * docs/DECISIONS.md: a screen's failure says so and the app carries on, with the details kept
 * in the error log for Swastik, never with an Aadhaar number in them.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ScreenFailureTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private fun logged(): List<String> = runBlocking {
        repeat(50) {
            val entries = app.database.errorLog().observeAll().first()
            if (entries.isNotEmpty()) return@runBlocking entries.map { "${it.tag}: ${it.message}" }
            Thread.sleep(20)
        }
        emptyList()
    }

    @Test
    fun aFailedScreenJobSaysSoAndIsLogged() = runBlocking {
        val job = CoroutineScope(Job()).launchSafely(app) { error("Saving the plan failed") }
        job.join()
        assertFalse("it finished rather than failing its scope", job.isCancelled)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(FailureGuard.WENT_WRONG, ShadowToast.getTextOfLatestToast())
        val entry = logged().single()
        assertTrue(entry, entry.startsWith("Screen: IllegalStateException: Saving the plan failed"))
        assertTrue(entry, "ScreenFailureTest" in entry)
    }

    @Test
    fun aFullPhoneGetsItsOwnMessage() = runBlocking {
        CoroutineScope(Job()).launchSafely(app) { throw android.database.sqlite.SQLiteFullException("disk is full") }.join()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(FailureGuard.STORAGE_FULL, ShadowToast.getTextOfLatestToast())
    }

    /** Without this, nobody could read why it crashes on every start (docs/DECISIONS.md). */
    @Test
    fun aPhoneThatKeepsCrashingOffersAWayOut() {
        val crash = IllegalStateException("It keeps crashing")
        repeat(2) { app.recordCrash(Thread.currentThread(), crash) }
        assertFalse(app.keepsCrashing())
        app.recordCrash(Thread.currentThread(), crash)
        assertTrue(app.keepsCrashing())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(RecoveryActivity::class.java.name, opened.component?.className)

        ActivityScenario.launch(RecoveryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val buttons = allButtons(activity.window.decorView)
                assertEquals(listOf("Try again", "Copy the details for Swastik", "Send a copy of your data"), buttons.map { it.text.toString() })
                buttons[1].performClick()
                val copied = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                assertTrue(copied, "It keeps crashing" in copied)
                assertTrue(copied, copied.startsWith("MB Fitness "))

                buttons[0].performClick()
                assertFalse("Try again lets it open normally", app.keepsCrashing())
                assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
                assertTrue(activity.isFinishing)
            }
        }
    }

    private fun allButtons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { allButtons(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun aCrashReachesTheErrorLogAtTheNextStartWithoutTheNumber() {
        app.recordCrash(Thread.currentThread(), IllegalStateException("bad input 999900000016"))
        app.collectCrashReport()
        val entry = logged().single()
        assertTrue(entry, entry.startsWith("Crash (${Thread.currentThread().name}): IllegalStateException: bad input"))
        assertFalse(entry, "999900000016" in entry)
        app.collectCrashReport()
        assertEquals("only once", 1, logged().size)
    }
}
