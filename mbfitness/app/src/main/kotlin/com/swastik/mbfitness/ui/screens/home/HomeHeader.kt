package com.swastik.mbfitness.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.ui.components.GymLogo
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.StatTile
import com.swastik.mbfitness.ui.theme.BrandColors
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

/** The red ribbon that says SMS only go to the owner's own number (SPEC 8.11). */
@Composable
fun TestModeRibbon() {
    Text(
        "TEST MODE",
        Modifier
            .fillMaxWidth()
            .background(BrandColors.Danger)
            .padding(vertical = 4.dp),
        style = MbType.SectionLabel,
        color = BrandColors.OnDanger,
        textAlign = TextAlign.Center,
    )
}

/** Logo, greeting, owner, gym and date (SPEC 10.2 screen 3). */
@Composable
fun HomeHeader(settings: AppSettings, today: Long, testMode: Boolean) {
    val colors = MbTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.header)
            .statusBarsPadding(),
    ) {
        if (testMode) TestModeRibbon()
        Row(
            Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GymLogo(settings.logoFile, settings.gymName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    settings.greeting,
                    style = MbType.SectionLabel.copy(letterSpacing = 0.15.em),
                    color = colors.greeting,
                )
                Text(
                    settings.ownerName,
                    Modifier.semantics { heading() },
                    style = MbType.Title,
                    color = colors.onHeader,
                )
                Text(
                    "${settings.gymName} · ${Dates.formatHeader(today)}",
                    style = MbType.Caption,
                    color = colors.onHeaderMuted,
                )
            }
        }
    }
}

/** Collected this month, Pending, Active. They wrap rather than clip at a large font size. */
@Composable
fun StatTiles(collected: Long, pending: Long, active: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tile = Modifier
            .weight(1f)
            .fillMaxHeight()
        StatTile("Collected this month", Money.format(collected), tile, spoken = Money.spoken(collected))
        StatTile("Pending", Money.format(pending), tile, spoken = Money.spoken(pending))
        StatTile("Active", active.toString(), tile)
    }
}
