package com.swastik.mbfitness.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.swastik.mbfitness.App
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Send a copy" (docs/DECISIONS.md): a backup file handed to the app bhaiya picks, such as
 * WhatsApp or Drive, through Android's share sheet. Folder backups stay on the phone, so this is
 * what saves the data if the phone is lost. The app itself still has no internet (hard rule 1).
 */
object SendCopy {
    /** Matches the provider in AndroidManifest.xml, so test and real builds each have their own. */
    fun authority(context: Context) = "${context.packageName}.files"

    fun chooser(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        // Android calls this once an app is picked, so a cancelled share isn't counted as sent.
        val picked = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CopySentReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Intent.createChooser(send, "Send a copy", picked.intentSender)
    }

    private const val MIME_TYPE = "application/json"
}

/** An app was picked for "Send a copy". Which one doesn't matter here. */
class CopySentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        val pending = goAsync()
        app.backgroundScope.launch {
            try {
                app.attempt("Send a copy") { app.backups.copySent() }
            } finally {
                pending.finish()
            }
        }
    }
}
