package com.swastik.mbfitness.ui.screens.payment

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.swastik.mbfitness.App
import com.swastik.mbfitness.R
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.domain.UpiLink
import com.swastik.mbfitness.ui.components.MbIconButton
import com.swastik.mbfitness.ui.theme.BrandColors
import com.swastik.mbfitness.ui.theme.MbType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SPEC 10.2 screen 6. Always black on white so any phone's camera can read it, whatever mode
 * this phone is in. The link is built here on the phone, so it works with no internet.
 */
@Composable
fun UpiQrScreen(memberName: String, amount: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val settingsStore = (context.applicationContext as App).settings
    val settings by settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
    val payee = settings.upiPayeeName.ifBlank { settings.gymName }
    val ink = BrandColors.TextPrimaryLight

    DisposableEffect(view) {
        val window = (context as? android.app.Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val darkIconsBefore = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = true
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            if (darkIconsBefore != null) bars.isAppearanceLightStatusBars = darkIconsBefore
        }
    }

    val link = UpiLink.build(settings.upiId, payee, amount, UpiLink.note(memberName))
    val code by produceState<ImageBitmap?>(null, link) {
        value = if (settings.upiId.isBlank()) null else withContext(Dispatchers.Default) { qrBitmap(link) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BrandColors.SurfaceLight)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth()) {
            MbIconButton(R.drawable.ic_arrow_back, "Back", onBack, tint = ink)
        }
        Column(
            Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(Money.format(amount), style = MbType.Display, color = ink)
            code?.let { bitmap ->
                Image(
                    bitmap,
                    contentDescription = "UPI QR code for ${Money.spoken(amount)} to $payee",
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                )
            }
            Text(payee, style = MbType.BodyLarge, color = ink, textAlign = TextAlign.Center)
            Text(settings.upiId, style = MbType.Body, color = BrandColors.TextSecondaryLight, textAlign = TextAlign.Center)
            Text("Scan with any UPI app", style = MbType.Label, color = ink, textAlign = TextAlign.Center)
        }
    }
}

private const val QR_PIXELS = 720

/** The dark and light squares of the code. Separate from drawing, so a test can decode it. */
internal fun qrMatrix(content: String): BitMatrix {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_PIXELS, QR_PIXELS, hints)
}

private fun qrBitmap(content: String): ImageBitmap {
    val matrix = qrMatrix(content)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix.get(index % matrix.width, index / matrix.width)) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.RGB_565).asImageBitmap()
}
