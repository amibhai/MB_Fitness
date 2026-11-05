package com.swastik.mbfitness.ui.screens.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.ui.components.GymLogo
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

/** Shown for under a second, on a cold start only (SPEC 10.2 screen 1). */
@Composable
fun BrandSplash(settings: AppSettings) {
    val colors = MbTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.header)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            settings.greeting,
            Modifier.padding(top = 32.dp),
            style = MbType.Label.copy(letterSpacing = 0.2.em),
            color = colors.greeting,
            textAlign = TextAlign.Center,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GymLogo(settings.logoFile, settings.gymName, size = 120.dp)
            Spacer(Modifier.height(20.dp))
            Text(settings.gymName, style = MbType.Display, color = colors.onHeader, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(settings.ownerName, style = MbType.BodyLarge, color = colors.onHeaderMuted, textAlign = TextAlign.Center)
        }
        Text("App by Swastik", style = MbType.Caption, color = colors.onHeaderMuted)
    }
}
