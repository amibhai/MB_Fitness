package com.swastik.mbfitness.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.MemberRules
import com.swastik.mbfitness.domain.MemberState
import com.swastik.mbfitness.domain.TodayProvider
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.EmptyState
import com.swastik.mbfitness.ui.components.ScreenPadding
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val settings: AppSettings = AppSettings(),
    val testMode: Boolean = false,
    val today: Long = 0,
    val collectedThisMonth: Long = 0,
    val pending: Long = 0,
    val activeCount: Int = 0,
)

class HomeViewModel(
    members: MemberRepository,
    settings: SettingsStore,
    clock: TodayProvider,
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> =
        combine(
            settings.settings,
            members.observeMembers(),
            members.observeCollectedThisMonth(),
        ) { current, rows, collected ->
            HomeUiState(
                settings = current,
                testMode = settings.isTestMode(current),
                today = clock.today(),
                collectedThisMonth = collected,
                pending = rows.sumOf { MemberRules.due(it.balance) },
                activeCount = rows.count { it.status == MemberState.ACTIVE },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState(today = clock.today()))

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** SPEC 10.2 screen 3. The member list, tabs and search arrive with Phase 4. */
@Composable
fun HomeScreen() {
    val viewModel = appViewModel { app -> HomeViewModel(app.members, app.settings, app.clock) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        HomeHeader(state.settings, state.today, state.testMode)
        StatTiles(state.collectedThisMonth, state.pending, state.activeCount, Modifier.padding(ScreenPadding))
        EmptyState("No members yet.")
    }
}
