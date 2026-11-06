package com.swastik.mbfitness.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.ui.components.AppSnackbarHost
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.SnackbarController
import com.swastik.mbfitness.ui.navigation.AppNavigation
import com.swastik.mbfitness.ui.screens.lock.LockScreen
import com.swastik.mbfitness.ui.screens.lock.LockViewModel
import com.swastik.mbfitness.ui.screens.onboarding.OnboardingScreen
import com.swastik.mbfitness.ui.screens.settings.DamagedDatabaseScreen
import com.swastik.mbfitness.ui.screens.splash.BrandSplash
import com.swastik.mbfitness.ui.theme.MbTheme

/** Splash on a cold start, then the lock screen if a PIN is set, then the app. */
@Composable
fun AppRoot(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var importAfterSetup by rememberSaveable { mutableStateOf(false) }
    when {
        state.loading -> Unit // The system splash is still on screen.
        state.showBrandSplash -> BrandSplash(state.settings)
        state.locked -> {
            val lock = appViewModel { app -> LockViewModel(app.appLock, app.settings, app.clock) }
            val lockState by lock.uiState.collectAsStateWithLifecycle()
            LockScreen(lockState, lock)
        }
        state.databaseDamaged -> WithSnackbar { DamagedDatabaseScreen() }
        // An import offered at the end of setup opens once the app is showing.
        !state.settings.onboardingDone -> WithSnackbar { OnboardingScreen(onImportMembers = { importAfterSetup = true }) }
        else -> AppNavigation(openImport = importAfterSetup, onImportOpened = { importAfterSetup = false })
    }
}

/** The main navigation has its own. These screens show without it, and share some of its parts. */
@Composable
private fun WithSnackbar(content: @Composable () -> Unit) {
    val snackbar = remember { SnackbarController() }
    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            containerColor = MbTheme.colors.background,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { AppSnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.padding(padding)) { content() }
        }
    }
}
