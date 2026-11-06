package com.swastik.mbfitness.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max

/** Keeps a small copy of the gym's logo inside the app (SPEC 4-F). Backups carry it too. */
class LogoStore(private val context: Context, private val settings: SettingsStore) {
    private val folder get() = File(context.filesDir, BackupManager.LOGO_FOLDER)

    /** False if the picture couldn't be read. The old logo stays in that case. */
    suspend fun save(picture: Uri): Boolean = withContext(Dispatchers.IO) {
        val bitmap = decode(picture) ?: return@withContext false
        try {
            folder.mkdirs()
            // A new name each time, because the logo on screen only reloads when its name changes.
            val name = "logo_${System.nanoTime()}.png"
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, QUALITY, it) }
            folder.listFiles()?.filter { it.name != name }?.forEach(File::delete)
            settings.update { it.copy(logoFile = "${BackupManager.LOGO_FOLDER}/$name") }
            true
        } catch (_: IOException) {
            false
        }
    }

    suspend fun remove() {
        withContext(Dispatchers.IO) { folder.listFiles()?.forEach(File::delete) }
        settings.update { it.copy(logoFile = null) }
    }

    private fun decode(picture: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(picture)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val full = context.contentResolver.openInputStream(picture)?.use { BitmapFactory.decodeStream(it, null, options) }
        full?.let(::scaledDown)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun scaledDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_SIDE) return bitmap
        val scale = MAX_SIDE.toFloat() / longest
        return bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
    }

    private companion object {
        const val MAX_SIDE = 512
        const val QUALITY = 100
    }
}
