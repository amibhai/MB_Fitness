package com.swastik.mbfitness.ui.screens.today

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.R
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.ClosingEntry
import com.swastik.mbfitness.domain.ClosingSummary
import com.swastik.mbfitness.domain.ClosingTotals
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.domain.ReceiptNumbers
import com.swastik.mbfitness.domain.TodayProvider
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.EmptyState
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbIconButton
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.StatTile
import com.swastik.mbfitness.ui.screens.member.displayName
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class TodayUiState(
    val day: Long = 0,
    val today: Long = 0,
    val entries: List<ClosingEntry> = emptyList(),
    val totals: ClosingTotals = ClosingTotals(0, 0, 0),
    val settings: AppSettings = AppSettings(),
)

class TodayViewModel(members: MemberRepository, settings: SettingsStore, private val clock: TodayProvider) : ViewModel() {
    private val day = MutableStateFlow(clock.today())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val entries = day.flatMapLatest { selected ->
        members.observePaymentsOn(selected).map { rows ->
            selected to rows.map { ClosingEntry(it.memberName, it.receiptNo?.let(ReceiptNumbers::format), it.paid, it.mode) }
        }
    }

    val uiState: StateFlow<TodayUiState> =
        combine(entries, settings.settings) { (selected, list), current ->
            TodayUiState(selected, clock.today(), list, ClosingSummary.totals(list), current)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TodayUiState(clock.today(), clock.today()))

    fun previousDay() = day.update { it - 1 }

    fun nextDay() = day.update { minOf(it + 1, clock.today()) }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** SPEC 10.2 screen 8. */
@Composable
fun TodayClosingScreen() {
    val viewModel = appViewModel { app -> TodayViewModel(app.members, app.settings, app.clock) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val colors = MbTheme.colors

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Today's closing")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MbIconButton(R.drawable.ic_arrow_back, "Previous day", viewModel::previousDay)
            Text(
                if (state.day == state.today) "Today, ${Dates.formatShort(state.day)}" else Dates.formatHeader(state.day),
                Modifier
                    .weight(1f)
                    .semantics { heading() },
                style = MbType.BodyLarge,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            if (state.day < state.today) {
                MbIconButton(R.drawable.ic_arrow_back, "Next day", viewModel::nextDay, Modifier.rotate(180f))
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            item(key = "totals") {
                Column(verticalArrangement = Arrangement.spacedBy(ListGap)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(ListGap),
                    ) {
                        val tile = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                        StatTile("Cash", Money.format(state.totals.cash), tile, spoken = Money.spoken(state.totals.cash))
                        StatTile("UPI", Money.format(state.totals.upi), tile, spoken = Money.spoken(state.totals.upi))
                        StatTile("Other", Money.format(state.totals.other), tile, spoken = Money.spoken(state.totals.other))
                    }
                    StatTile("Total", Money.format(state.totals.total), Modifier.fillMaxWidth(), spoken = Money.spoken(state.totals.total))
                }
            }
            if (state.entries.isEmpty()) {
                item(key = "empty") { EmptyState("No payments on this day.") }
            } else {
                item(key = "share") {
                    SecondaryButton(
                        "Share summary",
                        {
                            val text = ClosingSummary.shareText(state.settings.gymName, state.day, state.entries)
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Closing", text))
                            snackbar.show("Summary copied")
                        },
                        Modifier.fillMaxWidth(),
                        icon = R.drawable.ic_content_copy,
                    )
                }
                items(state.entries) { entry ->
                    MbCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(ScreenPadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.memberName, style = MbType.Label, color = colors.textPrimary)
                                Text(
                                    listOfNotNull(entry.receipt, entry.mode.displayName()).joinToString(" · "),
                                    style = MbType.Caption,
                                    color = colors.textSecondary,
                                )
                            }
                            Text(Money.format(entry.amount), style = MbType.BodyLarge, color = colors.textPrimary)
                        }
                    }
                }
            }
        }
    }
}
