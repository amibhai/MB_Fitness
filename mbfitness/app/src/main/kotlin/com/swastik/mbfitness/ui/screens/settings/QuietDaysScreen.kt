package com.swastik.mbfitness.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.R
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.data.MessageRepository
import com.swastik.mbfitness.data.QuietDateEntity
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.QuietCalendar
import com.swastik.mbfitness.domain.QuietDate
import com.swastik.mbfitness.domain.TodayProvider
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.BannerTone
import com.swastik.mbfitness.ui.components.DateField
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.MbBanner
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbIconButton
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.MbToggleChip
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

data class QuietDaysUiState(
    val loaded: Boolean = false,
    val today: Long = 0,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val dates: List<QuietDateEntity> = emptyList(),
    val nextQuiet: String? = null,
    val everyDayIsQuiet: Boolean = false,
)

class QuietDaysViewModel(
    private val messages: MessageRepository,
    private val settings: SettingsStore,
    private val clock: TodayProvider,
) : ViewModel() {
    val uiState: StateFlow<QuietDaysUiState> =
        // Past holidays are hidden at once. Housekeeping deletes them 30 days later (SPEC 8.3).
        combine(settings.settings, messages.observeQuietDatesFrom(clock.today())) { current, dates ->
            build(current, dates)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), QuietDaysUiState(today = clock.today()))

    fun onToggleWeekday(day: DayOfWeek) {
        viewModelScope.launch {
            settings.update {
                it.copy(quietWeekdays = if (day in it.quietWeekdays) it.quietWeekdays - day else it.quietWeekdays + day)
            }
        }
    }

    fun add(date: Long, label: String) {
        viewModelScope.launch { messages.addQuietDate(date, label) }
    }

    fun remove(id: Long) {
        viewModelScope.launch { messages.removeQuietDate(id) }
    }

    private fun build(current: AppSettings, dates: List<QuietDateEntity>): QuietDaysUiState {
        val today = clock.today()
        val calendar = QuietCalendar(current.quietWeekdays, dates.map { QuietDate(it.date, it.label) })
        return QuietDaysUiState(
            loaded = true,
            today = today,
            weekdays = current.quietWeekdays,
            dates = dates,
            nextQuiet = calendar.nextQuietDay(today)?.let { day ->
                if (day == today) "Today is a quiet day (${calendar.reason(day)})." else "Next quiet day: ${Dates.formatHeader(day)} (${calendar.reason(day)})."
            },
            everyDayIsQuiet = calendar.nextNormalDay(today) == null,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** SPEC 8.3 and 10.2 screen 14. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuietDaysScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { app -> QuietDaysViewModel(app.messages, app.settings, app.clock) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = MbTheme.colors

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Quiet days", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            MbCard(Modifier.fillMaxWidth()) {
                Text(
                    "No automatic reminders go on quiet days. They go on the next normal day, with the right wording. Thank-yous, birthday wishes, your own SMS and broadcasts still go.",
                    style = MbType.Body,
                    color = colors.textPrimary,
                )
                state.nextQuiet?.let { Text(it, Modifier.padding(top = 8.dp), style = MbType.Label, color = colors.textPrimary) }
            }
            if (state.everyDayIsQuiet) {
                MbBanner("Every day is quiet, so no reminders will ever go.", BannerTone.WARNING)
            }

            SectionLabel("Every week")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    MbToggleChip(
                        day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                        day in state.weekdays,
                        { viewModel.onToggleWeekday(day) },
                    )
                }
            }

            SectionLabel("Holidays")
            state.dates.forEach { date ->
                MbCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(date.label, style = MbType.BodyLarge, color = colors.textPrimary)
                            Text(Dates.formatFull(date.date), style = MbType.Caption, color = colors.textSecondary)
                        }
                        MbIconButton(R.drawable.ic_close, "Remove ${date.label}", { viewModel.remove(date.id) })
                    }
                }
            }
            AddHoliday(state.today, viewModel::add)
        }
    }
}

/** Festival dates are never built in, because they move. Bhaiya adds them (SPEC 8.3). */
@Composable
private fun AddHoliday(today: Long, onAdd: (Long, String) -> Unit) {
    var day by remember { mutableStateOf<Long?>(null) }
    var name by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val dayError = when {
        !tried -> null
        day == null -> "Pick a date"
        day!! < today -> "Pick today or a later date"
        else -> null
    }
    val nameError = if (tried && name.isBlank()) "Give it a name, like Diwali" else null

    MbCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(ListGap)) {
            DateField("Date", day, { day = it })
            dayError?.let { Text(it, style = MbType.Caption, color = MbTheme.colors.danger) }
            MbTextField(name, { name = it }, "Holiday name", error = nameError, capitalization = KeyboardCapitalization.Words)
            SecondaryButton(
                "Add holiday",
                {
                    tried = true
                    val chosen = day
                    if (chosen != null && chosen >= today && name.isNotBlank()) {
                        onAdd(chosen, name)
                        day = null
                        name = ""
                        tried = false
                    }
                },
                Modifier.fillMaxWidth(),
            )
        }
    }
}
