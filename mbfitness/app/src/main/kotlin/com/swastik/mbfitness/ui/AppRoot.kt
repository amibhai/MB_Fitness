package com.swastik.mbfitness.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.ui.navigation.AppNavigation
import com.swastik.mbfitness.ui.screens.lock.LockScreen
import com.swastik.mbfitness.ui.screens.lock.LockViewModel
import com.swastik.mbfitness.ui.screens.splash.BrandSplash

/** Splash on a cold start, then the lock screen if a PIN is set, then the app. */
@Composable
fun AppRoot(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when {
        state.loading -> Unit // The system splash is still on screen.
        state.showBrandSplash -> BrandSplash(state.settings)
        state.locked -> {
            val lock = appViewModel { app -> LockViewModel(app.appLock, app.settings, app.clock) }
            val lockState by lock.uiState.collectAsStateWithLifecycle()
            LockScreen(lockState, lock)
        }
        else -> AppNavigation()
    }
}
