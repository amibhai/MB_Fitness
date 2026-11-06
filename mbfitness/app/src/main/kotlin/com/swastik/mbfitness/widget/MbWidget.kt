package com.swastik.mbfitness.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.swastik.mbfitness.App
import com.swastik.mbfitness.MainActivity
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.domain.WidgetRules
import com.swastik.mbfitness.domain.WidgetSummary
import com.swastik.mbfitness.ui.theme.BrandColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/** The home-screen widget (SPEC 4-E): counts when small, and more when there's room. */
class MbWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = summaryFor(context.applicationContext as App)
        provideContent { WidgetContent(summary) }
    }

    @Composable
    private fun WidgetContent(summary: WidgetSummary) {
        val roomy = LocalSize.current.height >= MEDIUM.height
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(day = BrandColors.SurfaceLight, night = BrandColors.SurfaceDark))
                .cornerRadius(CORNER.dp)
                .padding(PADDING.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                summary.headline,
                style = TextStyle(primaryText, fontSize = HEADLINE_SIZE.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            if (roomy) {
                summary.pending?.let { pending ->
                    Spacer(GlanceModifier.height(GAP.dp))
                    Text("${Money.format(pending)} pending", style = TextStyle(secondaryText, fontSize = BODY_SIZE.sp), maxLines = 1)
                }
                if (summary.overdueNames.isNotEmpty()) {
                    Spacer(GlanceModifier.height(GAP.dp))
                    Text(summary.overdueNames.joinToString(", "), style = TextStyle(secondaryText, fontSize = BODY_SIZE.sp), maxLines = 1)
                }
            }
        }
    }

    private val primaryText get() = ColorProvider(day = BrandColors.TextPrimaryLight, night = BrandColors.TextPrimaryDark)
    private val secondaryText get() = ColorProvider(day = BrandColors.TextSecondaryLight, night = BrandColors.TextSecondaryDark)

    companion object {
        private val SMALL = DpSize(110.dp, 40.dp)
        private val MEDIUM = DpSize(180.dp, 100.dp)
        private const val CORNER = 18
        private const val PADDING = 12
        private const val GAP = 4
        private const val HEADLINE_SIZE = 16
        private const val BODY_SIZE = 13

        suspend fun summaryFor(app: App): WidgetSummary {
            val rows = app.members.observeMembers().first().map {
                WidgetRules.Member(it.name, it.status, it.expiryDate, it.balance)
            }
            return WidgetRules.summarize(rows, app.clock.today(), app.settings.current().pinHash != null)
        }

        /** Does nothing if no widget has been added to the home screen. */
        suspend fun refresh(context: Context) = MbWidget().updateAll(context)
    }
}

class MbWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MbWidget()
}

/**
 * Keeps the widget true: whenever members or payments change, it refreshes. Watching the data
 * means no path can forget to, whether that's a payment, an undo, an import or a restore. Several
 * changes close together make one refresh.
 */
class WidgetRefresher(private val changes: Flow<*>, private val refresh: suspend () -> Unit) {
    suspend fun watch() {
        changes.drop(1).conflate().collect {
            refresh()
            delay(MIN_GAP_MILLIS)
        }
    }

    private companion object {
        const val MIN_GAP_MILLIS = 1_000L
    }
}
