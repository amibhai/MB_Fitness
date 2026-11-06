package com.swastik.mbfitness

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.IntentCompat

/** A share target in debug builds only, so "Send a copy" can be checked without a real app. */
class DebugShareTarget : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val bytes = uri?.let { contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() } }
        Log.i("MbfDebug", "share target: ${bytes?.size} bytes from $uri")
        finish()
    }
}
