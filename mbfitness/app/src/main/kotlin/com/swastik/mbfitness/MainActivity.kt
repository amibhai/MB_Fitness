package com.swastik.mbfitness

import android.app.UiModeManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.swastik.mbfitness.ui.AppRoot
import com.swastik.mbfitness.ui.AppViewModel
import com.swastik.mbfitness.ui.theme.MbTheme

// FragmentActivity because androidx.biometric's BiometricPrompt requires it.
class MainActivity : FragmentActivity() {
    private val viewModel: AppViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as App
                AppViewModel(app.settings, app.appLock)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Every screen has a dark bar at the top, so the status bar icons are always light.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.uiState.value.loading }
        (application as App).watchForeground()
        applyDebugThemeOverride()
        setContent {
            MbTheme {
                AppRoot(viewModel)
            }
        }
    }

    /**
     * Debug builds only, and compiled out of a release: lets a light or dark check run on a
     * real phone for this app alone, without touching the phone's own setting:
     * adb shell am start -n com.swastik.mbfitness/.MainActivity --es mbf_theme light
     */
    private fun applyDebugThemeOverride() {
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mode = when (intent.getStringExtra("mbf_theme")) {
            "light" -> UiModeManager.MODE_NIGHT_NO
            "dark" -> UiModeManager.MODE_NIGHT_YES
            "system" -> UiModeManager.MODE_NIGHT_AUTO
            else -> return
        }
        getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }
}
