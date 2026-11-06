package com.swastik.mbfitness.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.swastik.mbfitness.App
import kotlinx.coroutines.launch

/** The radio's answer for one part of an SMS (SPEC 8.5). */
class SentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        val id = intent.getLongExtra(SimSmsSender.EXTRA_MESSAGE_ID, -1)
        val attempt = intent.getIntExtra(SimSmsSender.EXTRA_ATTEMPT, 0)
        val partCount = intent.getIntExtra(SimSmsSender.EXTRA_PART_COUNT, 1)
        val code = resultCode
        val ok = code == Activity.RESULT_OK
        val pending = goAsync()
        app.backgroundScope.launch {
            try {
                val retryAt = app.messages.onPartResult(id, attempt, partCount, ok, if (ok) null else SimSmsSender.describeResult(code))
                // The outbox works out the next wake-up, because it can see the whole queue.
                if (retryAt != null) app.outboxKicker.kick()
            } finally {
                pending.finish()
            }
        }
    }
}

/** A delivery report. Not every carrier sends these, and that's fine (SPEC 8.5). */
class DeliveredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        val id = intent.getLongExtra(SimSmsSender.EXTRA_MESSAGE_ID, -1)
        val attempt = intent.getIntExtra(SimSmsSender.EXTRA_ATTEMPT, 0)
        val partCount = intent.getIntExtra(SimSmsSender.EXTRA_PART_COUNT, 1)
        if (resultCode != Activity.RESULT_OK) return
        val pending = goAsync()
        app.backgroundScope.launch {
            try {
                app.messages.onDelivered(id, attempt, partCount)
            } finally {
                pending.finish()
            }
        }
    }
}
