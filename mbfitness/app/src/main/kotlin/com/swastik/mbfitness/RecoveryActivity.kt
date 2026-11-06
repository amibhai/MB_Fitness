package com.swastik.mbfitness

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.view.WindowCompat
import com.swastik.mbfitness.data.CopyOutcome
import com.swastik.mbfitness.data.SendCopy
import com.swastik.mbfitness.work.DeviceIntents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Shown instead of the app when it keeps crashing (docs/DECISIONS.md). Without this, the error log
 * can't be reached, so nobody could find out why. It uses plain Android views, and doesn't open
 * the database until asked to, so whatever is crashing the app can't take this down too.
 */
class RecoveryActivity : Activity() {
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout())
        // The screen is white, so the status bar's icons have to be dark to be seen.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
    }

    private fun layout(): View {
        val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        column.addView(
            text(R.string.recovery_title, TITLE_SP).apply { setTypeface(typeface, Typeface.BOLD) },
        )
        column.addView(
            text(R.string.recovery_message, BODY_SP).apply { setPadding(0, padding / 2, 0, padding) },
        )
        column.addView(button(R.string.recovery_try_again, ::tryAgain))
        column.addView(button(R.string.recovery_copy, ::copyDetails))
        column.addView(button(R.string.recovery_send, ::sendCopy))
        return ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.WHITE)
            addView(column)
        }
    }

    private fun text(@StringRes id: Int, size: Float) = TextView(this).apply {
        setText(id)
        textSize = size
        setTextColor(Color.BLACK)
    }

    private fun button(@StringRes id: Int, action: () -> Unit) = Button(this).apply {
        setText(id)
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun tryAgain() {
        app.forgetCrashes()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun copyDetails() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MB Fitness crash", app.crashDetails()))
        Toast.makeText(this, R.string.recovery_copied, Toast.LENGTH_LONG).show()
    }

    private fun sendCopy() {
        app.backgroundScope.launch {
            // If the database is what's crashing the app, this can fail too. Then it just says so.
            val outcome = try {
                app.backups.copyToSend()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CopyOutcome.Failed(error.javaClass.simpleName)
            }
            runOnUiThread {
                val sent = outcome is CopyOutcome.Ready && DeviceIntents.open(this@RecoveryActivity, SendCopy.chooser(this@RecoveryActivity, outcome.file))
                if (!sent) Toast.makeText(this@RecoveryActivity, R.string.recovery_send_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val PADDING_DP = 24
        const val TITLE_SP = 24f
        const val BODY_SP = 16f
    }
}
