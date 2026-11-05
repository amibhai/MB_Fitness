package com.swastik.mbfitness.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import kotlinx.coroutines.delay

data class SnackAction(val label: String, val onClick: () -> Unit)

/** [id] lets the same text show twice in a row. */
data class SnackMessage(val text: String, val actions: List<SnackAction> = emptyList(), val id: Long = System.nanoTime())

/** The app's one snackbar. It outlives screen changes, so a payment's Undo survives going back. */
@Stable
class SnackbarController {
    var current by mutableStateOf<SnackMessage?>(null)
        private set

    fun show(text: String, actions: List<SnackAction> = emptyList()) {
        current = SnackMessage(text, actions)
    }

    fun dismiss() {
        current = null
    }
}

val LocalSnackbar = staticCompositionLocalOf<SnackbarController> { error("No SnackbarController provided") }

/** "₹1,200 saved · Receipt MBF-0042", with Show QR and Undo, for 5 seconds (SPEC 7.3). */
@Composable
fun AppSnackbarHost(controller: SnackbarController, modifier: Modifier = Modifier) {
    val colors = MbTheme.colors
    val message = controller.current
    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(SNACKBAR_MILLIS)
            controller.dismiss()
        }
    }
    AnimatedVisibility(
        message != null,
        modifier,
        enter = fadeIn(tween(MOTION_MILLIS)) + slideInVertically(tween(MOTION_MILLIS)) { it / 2 },
        exit = fadeOut(tween(MOTION_MILLIS)) + slideOutVertically(tween(MOTION_MILLIS)) { it / 2 },
    ) {
        val shown = controller.current ?: return@AnimatedVisibility
        Surface(
            Modifier
                .fillMaxWidth()
                .padding(ScreenPadding),
            shape = RoundedCornerShape(14.dp),
            color = colors.header,
            shadowElevation = 4.dp,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                Text(
                    shown.text,
                    Modifier
                        .padding(end = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MbType.Label,
                    color = colors.onHeader,
                )
                Row(Modifier.align(Alignment.End)) {
                    shown.actions.forEach { action ->
                        TextButton(
                            onClick = {
                                controller.dismiss()
                                action.onClick()
                            },
                            Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(action.label, style = MbType.Label, color = colors.greeting)
                        }
                    }
                }
            }
        }
    }
}

private const val SNACKBAR_MILLIS = 5_000L
private const val MOTION_MILLIS = 200
