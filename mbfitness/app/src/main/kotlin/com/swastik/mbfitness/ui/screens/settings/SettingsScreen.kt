package com.swastik.mbfitness.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.App
import com.swastik.mbfitness.R
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.screens.lock.canUseBiometric
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import kotlinx.coroutines.launch

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    val colors = MbTheme.colors
    MbCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MbType.BodyLarge, color = colors.textPrimary)
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.textSecondary)
        }
    }
}

/** Part of SPEC 10.2 screen 12. The rest arrives with Phase 6. */
@Composable
fun SettingsScreen(
    onSetPin: () -> Unit,
    onPlans: () -> Unit,
    onRecycleBin: () -> Unit,
    onTemplates: () -> Unit,
    onQuietDays: () -> Unit,
) {
    val context = LocalContext.current
    val settingsStore = (context.applicationContext as App).settings
    val settings by settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    val colors = MbTheme.colors
    val pinOn = settings.pinHash != null

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Settings")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            SectionLabel("Gym")
            NavRow("Plans and prices", onPlans)
            SectionLabel("Reminders")
            NavRow("Templates", onTemplates)
            NavRow("Quiet days", onQuietDays)
            SectionLabel("Data")
            NavRow("Recycle bin", onRecycleBin)
            SectionLabel("Security")
            MbCard(Modifier.fillMaxWidth(), onClick = onSetPin) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App PIN", style = MbType.BodyLarge, color = colors.textPrimary)
                        Text(if (pinOn) "On" else "Off", style = MbType.Caption, color = colors.textSecondary)
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.textSecondary)
                }
            }
            if (pinOn && canUseBiometric(context)) {
                MbCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Unlock with fingerprint or face",
                            Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            style = MbType.BodyLarge,
                            color = colors.textPrimary,
                        )
                        Switch(
                            settings.biometric,
                            { on -> scope.launch { settingsStore.update { it.copy(biometric = on) } } },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.button),
                        )
                    }
                }
            }
        }
    }
}
