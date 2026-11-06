package com.swastik.mbfitness.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.swastik.mbfitness.App
import com.swastik.mbfitness.data.AppLock
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.data.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Builds a ViewModel from the manual DI container in App.kt (SPEC 5.3). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, noinline create: (App) -> VM): VM =
    viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(this[APPLICATION_KEY] as App) } },
    )

data class AppUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val showBrandSplash: Boolean = true,
    val locked: Boolean = false,
    val databaseDamaged: Boolean = false,
)

/**
 * What the root of the app shows. Being a ViewModel, it survives rotation, which is what keeps
 * the branded splash to cold starts only (SPEC 10.2 screen 1).
 */
class AppViewModel(settings: SettingsStore, appLock: AppLock, databaseDamaged: StateFlow<Boolean>) : ViewModel() {
    private val splashDone = MutableStateFlow(false)

    val uiState: StateFlow<AppUiState> =
        combine(settings.settings, appLock.unlocked, splashDone, databaseDamaged) { current, unlocked, splash, damaged ->
            AppUiState(
                loading = false,
                settings = current,
                showBrandSplash = !splash,
                locked = current.pinHash != null && !unlocked,
                databaseDamaged = damaged,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    init {
        viewModelScope.launch {
            delay(BRAND_SPLASH_MILLIS)
            splashDone.value = true
        }
    }

    private companion object {
        const val BRAND_SPLASH_MILLIS = 800L
    }
}
