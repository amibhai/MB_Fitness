package com.swastik.mbfitness.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The gym's logo, saved inside the app (SPEC 4-F), or an "MB" mark until one is picked. A
 * missing or unreadable file falls back to the mark rather than failing.
 */
@Composable
fun GymLogo(logoFile: String?, gymName: String, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val context = LocalContext.current
    val logo by produceState<ImageBitmap?>(null, logoFile) {
        value = logoFile?.let { name ->
            withContext(Dispatchers.IO) {
                val file = File(context.filesDir, name)
                if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
            }
        }
    }
    val colors = MbTheme.colors
    val shaped = modifier
        .size(size)
        .clip(CircleShape)
        .clearAndSetSemantics { contentDescription = "$gymName logo" }
    val image = logo
    if (image != null) {
        Image(image, contentDescription = null, shaped, contentScale = ContentScale.Crop)
    } else {
        Box(shaped.background(colors.accent), contentAlignment = Alignment.Center) {
            Text("MB", style = if (size >= 80.dp) MbType.Display else MbType.BodyLarge, color = colors.onButton)
        }
    }
}
