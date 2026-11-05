package com.swastik.mbfitness.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.MemberRow
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.MemberRules
import com.swastik.mbfitness.domain.MemberState
import com.swastik.mbfitness.domain.MemberTab
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.ScreenRules
import com.swastik.mbfitness.domain.TodayProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row on Home. */
data class MemberListItem(
    val id: Long,
    val name: String,
    val subtitle: String,
    val tab: MemberTab,
    val state: MemberState,
    val due: Long,
    val advance: Long,
)

sealed interface HomeBanner {
    /** Never automatic: this only suggests it (SPEC 4-A). */
    data class LongOverdue(val memberIds: Set<Long>) : HomeBanner
}

data class HomeUiState(
    val settings: AppSettings = AppSettings(),
    val testMode: Boolean = false,
    val today: Long = 0,
    val collectedThisMonth: Long = 0,
    val pending: Long = 0,
    val activeCount: Int = 0,
    val query: String = "",
    val tab: MemberTab = MemberTab.OVERDUE,
    val counts: Map<MemberTab, Int> = emptyMap(),
    val items: List<MemberListItem> = emptyList(),
    val totalMembers: Int = 0,
    val selection: Set<Long> = emptySet(),
    val banner: HomeBanner? = null,
    val loaded: Boolean = false,
)

private data class Filters(val query: String, val tab: MemberTab, val selection: Set<Long>)

class HomeViewModel(
    private val members: MemberRepository,
    private val settings: SettingsStore,
    private val clock: TodayProvider,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val tab = MutableStateFlow(MemberTab.OVERDUE)
    private val selection = MutableStateFlow(emptySet<Long>())
    private val tick = MutableStateFlow(0)

    private val filters = combine(query, tab, selection) { q, t, s -> Filters(q, t, s) }

    val uiState: StateFlow<HomeUiState> =
        combine(settings.settings, members.observeMembers(), members.observeCollectedThisMonth(), filters, tick) { current, rows, collected, filter, _ ->
            build(current, rows, collected, filter)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState(today = clock.today()))

    fun onQueryChange(text: String) = query.update { text }

    fun onTabSelected(selected: MemberTab) = tab.update { selected }

    /** Coming back to the app can mean a new day, which moves members between tabs. */
    fun onResume() = tick.update { it + 1 }

    fun onLongPress(id: Long) = selection.update { it + id }

    fun onToggleSelected(id: Long) = selection.update { if (id in it) it - id else it + id }

    fun clearSelection() = selection.update { emptySet() }

    /** Selects them ready for Mark as Left, so bhaiya still decides (SPEC 4-A). */
    fun onReviewLongOverdue(ids: Set<Long>) {
        tab.update { MemberTab.OVERDUE }
        selection.update { ids }
    }

    fun markSelectedAsLeft() {
        val ids = selection.value
        selection.update { emptySet() }
        viewModelScope.launch { ids.forEach { members.markLeft(it) } }
    }

    private fun build(current: AppSettings, rows: List<MemberRow>, collected: Long, filter: Filters): HomeUiState {
        val today = clock.today()
        val withTabs = rows.map { it to MemberRules.tab(it.status, it.expiryDate, today) }
        val items = withTabs
            .filter { (row, rowTab) ->
                val matchesTab = filter.query.isNotBlank() || rowTab == filter.tab
                matchesTab && ScreenRules.matchesSearch(row.name, row.phone, filter.query)
            }
            .sortedWith(compareBy({ it.first.expiryDate }, { it.first.name.lowercase() }))
            .map { (row, rowTab) -> row.toItem(rowTab) }
        val longOverdue = rows
            .filter { MemberRules.suggestMarkAsLeft(it.status, it.expiryDate, today) }
            .map { it.id }
            .toSet()
        return HomeUiState(
            settings = current,
            testMode = settings.isTestMode(current),
            today = today,
            collectedThisMonth = collected,
            pending = rows.sumOf { MemberRules.due(it.balance) },
            activeCount = rows.count { it.status == MemberState.ACTIVE },
            query = filter.query,
            tab = filter.tab,
            counts = withTabs.groupingBy { it.second }.eachCount(),
            items = items,
            totalMembers = rows.size,
            selection = filter.selection,
            banner = longOverdue.takeIf { it.isNotEmpty() }?.let(HomeBanner::LongOverdue),
            loaded = true,
        )
    }

    private fun MemberRow.toItem(rowTab: MemberTab) = MemberListItem(
        id = id,
        name = name,
        subtitle = "$planName · ${PhoneNumbers.display(phone)}",
        tab = rowTab,
        state = status,
        due = MemberRules.due(balance),
        advance = MemberRules.advance(balance),
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
