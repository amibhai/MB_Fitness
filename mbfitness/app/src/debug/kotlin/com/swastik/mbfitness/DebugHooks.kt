package com.swastik.mbfitness

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.ImportRow
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.domain.PhoneNumbers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Test hooks. This file only exists in debug builds; a release build gets an empty one from
 * src/release, so none of this can ship. Use them from adb:
 *
 *   am start -n com.swastik.mbfitness/.MainActivity --es mbf_theme light|dark|system
 *   am start -n com.swastik.mbfitness/.MainActivity --ef mbf_font 1.3
 *   am start -n com.swastik.mbfitness/.MainActivity --ei mbf_seed 300
 *   am start -n com.swastik.mbfitness/.MainActivity --ei mbf_seed 40 --ez mbf_consent true
 *   am start -n com.swastik.mbfitness/.MainActivity --ei mbf_fail 2
 *   am start -n com.swastik.mbfitness/.MainActivity --es mbf_upi gymowner@okbank
 *   am start -n com.swastik.mbfitness/.MainActivity --es mbf_trainer 98xxxxxxxx
 */
object DebugHooks {
    private const val SEED_PHONE_BASE = 9_000_000_000L
    private const val PREFS = "mbf_debug"
    private const val FONT_KEY = "font"

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
        val seed = activity.intent.getIntExtra("mbf_seed", 0)
        if (seed > 0) seedMembers(activity.application as App, seed, activity.intent.getBooleanExtra("mbf_consent", false))
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
