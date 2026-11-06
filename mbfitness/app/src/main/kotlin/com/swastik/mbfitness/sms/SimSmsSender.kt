package com.swastik.mbfitness.sms

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** The only place in the app that touches SmsManager (hard rule 3, SPEC 8.5). */
class SimSmsSender(private val context: Context) : SmsSender {
    override fun send(request: SmsRequest): SendOutcome {
        if (!hasPermission(Manifest.permission.SEND_SMS)) return SendOutcome.Blocked(BlockReason.NO_PERMISSION)
        return try {
            val manager = managerFor(request.subscriptionId)
            val parts = manager.divideMessage(request.body)
            val sent = ArrayList(parts.indices.map { resultIntent(SentReceiver::class.java, "sent", request, it, parts.size) })
            val delivered = ArrayList(parts.indices.map { resultIntent(DeliveredReceiver::class.java, "delivered", request, it, parts.size) })
            manager.sendMultipartTextMessage(request.phone, null, parts, sent, delivered)
            SendOutcome.Accepted(parts.size)
        } catch (_: SecurityException) {
            SendOutcome.Blocked(BlockReason.NO_PERMISSION)
        } catch (error: RuntimeException) {
            // Whatever the phone's SMS service throws (no telephony, service restarting, bad
            // number) counts as a failed try. It must never stop the rest of the queue.
            SendOutcome.Failed("Could not send: ${error.message}")
        }
    }

    /** Immutable, explicit and unique per part, so results can't be spoofed or mixed up (SPEC 8.5). */
    private fun resultIntent(receiver: Class<*>, kind: String, request: SmsRequest, part: Int, partCount: Int): PendingIntent {
        val intent = Intent(context, receiver)
            .setData("mbf://sms/$kind/${request.messageId}/${request.attempt}/$part".toUri())
            .putExtra(EXTRA_MESSAGE_ID, request.messageId)
            .putExtra(EXTRA_ATTEMPT, request.attempt)
            .putExtra(EXTRA_PART_COUNT, partCount)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The chosen SIM if it's still there, otherwise the phone's default (SPEC 8.5). */
    private fun managerFor(subscriptionId: Int?): SmsManager {
        val chosen = subscriptionId?.takeIf(::isActive)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(SmsManager::class.java)
            if (chosen != null) manager.createForSubscriptionId(chosen) else manager
        } else {
            legacyManager(chosen)
        }
    }

    // The only way on Android 8 to 11, which is why these deprecated calls stay.
    @Suppress("DEPRECATION")
    private fun legacyManager(subscriptionId: Int?): SmsManager =
        if (subscriptionId != null) SmsManager.getSmsManagerForSubscriptionId(subscriptionId) else SmsManager.getDefault()

    private fun isActive(subscriptionId: Int): Boolean {
        // Without the permission we can't check, so assume the choice still stands.
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) return true
        return try {
            context.getSystemService(SubscriptionManager::class.java).getActiveSubscriptionInfo(subscriptionId) != null
        } catch (_: SecurityException) {
            true
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_ATTEMPT = "attempt"
        const val EXTRA_PART_COUNT = "partCount"

        /** Two SIMs, none chosen, and the phone asks which to use each time. */
        const val NO_SIM_CHOSEN = "Your phone asks which SIM to use for each SMS. Choose one in Settings, under SIM."

        /**
         * A plain-language reason for the Messages screen, saying what bhaiya can do about it. A
         * phone with two SIMs that asks which to use each time reports only "failed", which would
         * read as the network's fault, so [phoneAsksWhichSim] gives the real reason instead.
         */
        fun describeResult(code: Int, phoneAsksWhichSim: Boolean = false): String = when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE if phoneAsksWhichSim -> NO_SIM_CHOSEN
            SmsManager.RESULT_ERROR_NO_SERVICE -> "No signal"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Airplane mode is on or the radio is off"
            SmsManager.RESULT_ERROR_NULL_PDU -> "The phone couldn't prepare this SMS"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "The network refused this SMS. Check the SIM has an SMS pack or balance."
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Android's limit on SMS was reached"
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "The SIM's fixed dialling list blocked this number"
            else -> "Could not send (code $code)"
        }
    }
}
