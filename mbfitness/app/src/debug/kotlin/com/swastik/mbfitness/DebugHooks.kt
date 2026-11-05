package com.swastik.mbfitness

import android.app.UiModeManager
import android.os.Build
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.ImportRow
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Test hooks. This file only exists in debug builds; a release build gets an empty one from
 * src/release, so none of this can ship. Use them from adb:
 *
 *   am start -n com.swastik.mbfitness/.MainActivity --es mbf_theme light|dark|system
 *   am start -n com.swastik.mbfitness/.MainActivity --ei mbf_seed 300
 *   am start -n com.swastik.mbfitness/.MainActivity --es mbf_upi gymowner@okbank
 */
object DebugHooks {
    private const val SEED_PHONE_BASE = 9_000_000_000L

    fun onActivityCreated(activity: MainActivity) {
        applyThemeOverride(activity)
        val seed = activity.intent.getIntExtra("mbf_seed", 0)
        if (seed > 0) seedMembers(activity.application as App, seed)
        activity.intent.getStringExtra("mbf_upi")?.let { upi ->
            MainScope().launch { (activity.application as App).settings.update { it.copy(upiId = upi) } }
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

    /** Fake members for the 300-member check. No SMS consent, so nothing can ever text them. */
    private fun seedMembers(app: App, count: Int) {
        MainScope().launch {
            if (app.members.existingPhones().isNotEmpty()) return@launch
            val today = app.clock.today()
            val rows = (1..count).map { index ->
                ImportRow(
                    line = index + 1,
                    name = "Member ${index.toString().padStart(3, '0')}",
                    phone = "+91${SEED_PHONE_BASE + index}",
                    planMonths = if (index % 3 == 0) 3 else 1,
                    expiry = today + (index % 160) - 100,
                    balanceDue = if (index % 5 == 0) 700 else 0,
                    dob = Dates.of(1990 + index % 20, 1 + index % 12, 1 + index % 28),
                    notes = "",
                    aadhaar = null,
                )
            }
            app.members.importMembers(rows, smsConsent = false)
        }
    }
}
