package com.swastik.mbfitness.ui.components

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.swastik.mbfitness.work.DeviceIntents

/**
 * Asks for SMS, and for notifications too, so problems can be reported. Once Android stops
 * showing the prompt, the app's settings page is the only way left, so it opens that instead.
 * An answer in under a second means no prompt appeared. A prompt someone backed out of can
 * look the same otherwise, and that shouldn't throw them into Settings.
 */
@Composable
fun rememberSmsPermissionRequest(onDone: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val askedAt = remember { longArrayOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val noPromptAppeared = SystemClock.uptimeMillis() - askedAt[0] < NO_PROMPT_MILLIS
        if (results[Manifest.permission.SEND_SMS] != true && noPromptAppeared) openSettingsIfPromptIsGone(context)
        onDone()
    }
    return {
        askedAt[0] = SystemClock.uptimeMillis()
        val wanted = buildList {
            add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        launcher.launch(wanted.toTypedArray())
    }
}

private fun openSettingsIfPromptIsGone(context: Context) {
    val activity = context.findActivity() ?: return
    if (activity.shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS)) return
    DeviceIntents.open(activity, DeviceIntents.appInfo(activity))
}

private const val NO_PROMPT_MILLIS = 1_000L

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Some phones have had their file picker removed. That should be a message, not a crash. */
fun <I> ActivityResultLauncher<I>.launchOr(input: I, onMissing: () -> Unit) {
    try {
        launch(input)
    } catch (_: ActivityNotFoundException) {
        onMissing()
    }
}
