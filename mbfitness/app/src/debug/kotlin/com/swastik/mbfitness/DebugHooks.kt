package com.swastik.mbfitness

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteFullException
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.swastik.mbfitness.data.BackupFolder
import com.swastik.mbfitness.data.CopyOutcome
import com.swastik.mbfitness.data.MemberDraft
import com.swastik.mbfitness.data.MemberSaveResult
import com.swastik.mbfitness.data.RenewalRequest
import com.swastik.mbfitness.data.RestoreOpen
import com.swastik.mbfitness.data.SendCopy
import com.swastik.mbfitness.domain.Aadhaar
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.ImportRow
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.widget.MbWidgetReceiver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Test hooks. This file only exists in debug builds; a release build gets an empty one from
 * src/release, so none of this can ship. Use them from adb:
 *
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --es mbf_theme light|dark|system
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ef mbf_font 1.3
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ei mbf_seed 300
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ei mbf_seed 40 --ez mbf_consent true
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ei mbf_fail 2
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ez mbf_onboarded true
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ez mbf_test_folder true
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ez mbf_history true
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ez mbf_storage_full true
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ez mbf_bug true
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ez mbf_crash true
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ez mbf_check_copy true
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ez mbf_daily true
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ez mbf_checkpoint true
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ez mbf_pin_widget true
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --ez mbf_fake_aadhaar true
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --es mbf_upi gymowner@okbank
 *   am start -n com.swastik.mbfitness.debug/com.swastik.mbfitness.MainActivity --es mbf_trainer 98xxxxxxxx
 */
object DebugHooks {
    private const val SEED_PHONE_BASE = 9_000_000_000L
    private const val PREFS = "mbf_debug"
    private const val FONT_KEY = "font"

    private const val TEST_FOLDER = "debug-test-folder"

    /** Inside this app's own storage, so a check never writes to the owner's files. */
    fun testFolder(app: App, uri: String): BackupFolder? =
        if (uri == TEST_FOLDER) FileBackupFolder(File(app.filesDir, TEST_FOLDER)) else null

    private class FileBackupFolder(private val folder: File) : BackupFolder {
        override fun isUsable() = folder.mkdirs() || folder.isDirectory
        override fun names() = folder.list().orEmpty().toList()
        override fun read(name: String) = File(folder, name).takeIf { it.exists() }?.readBytes()

        override fun write(name: String, bytes: ByteArray) {
            folder.mkdirs()
            File(folder, name).writeBytes(bytes)
        }

        override fun delete(name: String) {
            File(folder, name).delete()
        }
    }

    /** Six months of made-up payments. No SMS consent, so nothing can ever text them. */
    private fun addHistory(app: App) {
        MainScope().launch {
            val today = app.clock.today()
            val start = Dates.addMonths(today, -6)
            (1..12).forEach { index ->
                val phone = (SEED_PHONE_BASE + 900 + index).toString()
                val saved = app.members.addMember(MemberDraft("Member ${index.toString().padStart(2, '0')}", phone, 1, start + index))
                val id = (saved as? MemberSaveResult.Saved)?.memberId ?: return@forEach
                (0..5).filter { month -> index % (month + 2) != 0 }.forEach { month ->
                    val day = Dates.addMonths(start, month + 1) + index
                    if (day <= today) app.members.recordRenewal(RenewalRequest(id, 1, if (index % 5 == 0) 700 else 1200, PaymentMode.CASH, day))
                }
            }
        }
    }

    /** UIDAI's test range, so it's never a real person's. Set here so it stays out of adb commands. */
    private const val FAKE_AADHAAR = "999900000016"

    private fun addFakeAadhaar(app: App) {
        MainScope().launch {
            val aadhaar = (Aadhaar.parse(FAKE_AADHAAR) as Aadhaar.ParseResult.Valid).aadhaar
            val draft = MemberDraft("Aadhaar Test", "9000000500", 1, app.clock.today() + 30, aadhaar = aadhaar, aadhaarConsent = true)
            app.members.addMember(draft)
        }
    }

    /** A theme change recreates the activity, and that mustn't run these a second time. */
    private var oneOffsDone = false

    /** Text size for this app alone, so a check never touches the phone's own setting. */
    fun wrapContext(base: Context): Context {
        val scale = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(FONT_KEY, 0f)
        if (scale <= 0f) return base
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = scale })
    }

    @SuppressLint("UseKtx", "ApplySharedPref") // Written at once, because the next line recreates the activity.
    private fun applyFontOverride(activity: MainActivity) {
        val scale = activity.intent.getFloatExtra("mbf_font", 0f)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (scale <= 0f || prefs.getFloat(FONT_KEY, 0f) == scale) return
        prefs.edit().putFloat(FONT_KEY, scale).commit()
        activity.recreate()
    }

    fun onActivityCreated(activity: MainActivity) {
        applyThemeOverride(activity)
        applyFontOverride(activity)
        if (oneOffsDone) return
        oneOffsDone = true
        if (activity.intent.getBooleanExtra("mbf_onboarded", false)) {
            MainScope().launch { (activity.application as App).settings.update { it.copy(onboardingDone = true) } }
        }
        val seed = activity.intent.getIntExtra("mbf_seed", 0)
        if (seed > 0) seedMembers(activity.application as App, seed, activity.intent.getBooleanExtra("mbf_consent", false))
        if (activity.intent.getBooleanExtra("mbf_fake_aadhaar", false)) addFakeAadhaar(activity.application as App)
        if (activity.intent.getBooleanExtra("mbf_test_folder", false)) {
            MainScope().launch { (activity.application as App).settings.update { it.copy(backupFolderUri = TEST_FOLDER) } }
        }
        if (activity.intent.getBooleanExtra("mbf_history", false)) addHistory(activity.application as App)
        // Asks the launcher to offer the widget. The launcher shows its own dialog, so nothing is
        // added unless that's accepted.
        if (activity.intent.getBooleanExtra("mbf_pin_widget", false)) {
            AppWidgetManager.getInstance(activity).requestPinAppWidget(ComponentName(activity, MbWidgetReceiver::class.java), null, null)
        }
        // As if a save failed on a full phone: the app should say so and carry on.
        if (activity.intent.getBooleanExtra("mbf_storage_full", false)) {
            MainScope().launch {
                // So a check on the phone can tell this really happened.
                Log.i("MbfDebug", "storage-full check: failing a save now")
                throw SQLiteFullException("database or disk is full (debug check)")
            }
        }
        // As if a screen's work hit a bug: the app should say so, log it and carry on.
        if (activity.intent.getBooleanExtra("mbf_bug", false)) {
            MainScope().launch {
                Log.i("MbfDebug", "bug check: failing a screen's work now")
                throw IllegalStateException("A made-up bug (debug check)")
            }
        }
        // Reads a "Send a copy" file through the app's own provider, as a receiving app would.
        if (activity.intent.getBooleanExtra("mbf_check_copy", false)) {
            val app = activity.application as App
            MainScope().launch {
                val file = (app.backups.copyToSend() as CopyOutcome.Ready).file
                val uri = FileProvider.getUriForFile(app, SendCopy.authority(app), file)
                val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                Log.i("MbfDebug", "copy check: ${bytes.size} bytes through $uri, opens: ${app.backups.open(bytes) is RestoreOpen.Ready}")
            }
        }
        // Moves everything from the write-ahead log into the main file, so a check can damage it.
        if (activity.intent.getBooleanExtra("mbf_checkpoint", false)) {
            val app = activity.application as App
            MainScope().launch(Dispatchers.IO) {
                app.database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                Log.i("MbfDebug", "checkpoint check: done")
            }
        }
        // Runs the whole daily job now, the weekly backups included.
        if (activity.intent.getBooleanExtra("mbf_daily", false)) {
            val app = activity.application as App
            MainScope().launch {
                app.dailyRunner.run()
                Log.i("MbfDebug", "daily check: ran, own copy from ${app.backups.ownCopyDay()}")
            }
        }
        // As if the main thread hit a bug: the app closes, and its details wait for the next start.
        if (activity.intent.getBooleanExtra("mbf_crash", false)) {
            Handler(Looper.getMainLooper()).post { throw IllegalStateException("A made-up crash (debug check)") }
        }
        val fail = activity.intent.getIntExtra("mbf_fail", 0)
        if (fail > 0) failQueued(activity.application as App, fail)
        activity.intent.getStringExtra("mbf_upi")?.let { upi ->
            MainScope().launch { (activity.application as App).settings.update { it.copy(upiId = upi) } }
        }
        activity.intent.getStringExtra("mbf_trainer")?.let { number ->
            val normalized = PhoneNumbers.normalize(number) ?: return@let
            MainScope().launch { (activity.application as App).settings.update { it.copy(trainerPhone = normalized) } }
        }
    }

    /** Sets night mode for this app alone, so a check never touches the phone's own setting. */
    private fun applyThemeOverride(activity: MainActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mode = when (activity.intent.getStringExtra("mbf_theme")) {
            "light" -> UiModeManager.MODE_NIGHT_NO
            "dark" -> UiModeManager.MODE_NIGHT_YES
            "system" -> UiModeManager.MODE_NIGHT_AUTO
            else -> return
        }
        activity.getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }

    /** Marks some queued rows as failed without touching the radio, to check the Failed tab. */
    private fun failQueued(app: App, count: Int) {
        MainScope().launch {
            val dao = app.database.messages()
            dao.withStatusOnly(MessageStatus.QUEUED).take(count).forEach { row ->
                dao.update(row.copy(status = MessageStatus.FAILED, attempts = 3, lastError = "No signal"))
            }
        }
    }

    /**
     * Fake members for checks on a phone. Debug builds always run in Test mode, so even with
     * [consent] on, nothing can reach these made-up numbers (hard rule 9).
     */
    private fun seedMembers(app: App, count: Int, consent: Boolean) {
        MainScope().launch {
            if (app.members.existingPhones().isNotEmpty()) return@launch
            val today = app.clock.today()
            val rows = (1..count).map { index ->
                ImportRow(
                    line = index + 1,
                    name = "Member ${index.toString().padStart(3, '0')}",
                    phone = "+91${SEED_PHONE_BASE + index}",
                    planMonths = if (index % 3 == 0) 3 else 1,
                    // With consent, close to today, so some reminders are due. Otherwise a wide spread.
                    expiry = if (consent) today + (index % 12) - 6 else today + (index % 160) - 100,
                    balanceDue = if (index % 5 == 0) 700 else 0,
                    dob = Dates.of(1990 + index % 20, 1 + index % 12, 1 + index % 28),
                    notes = "",
                    aadhaar = null,
                )
            }
            app.members.importMembers(rows, smsConsent = consent)
        }
    }
}
