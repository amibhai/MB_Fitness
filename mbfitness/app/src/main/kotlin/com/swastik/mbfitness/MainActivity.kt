package com.swastik.mbfitness

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
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
    /** A notification asked for the Messages screen. The navigation clears this once it's there. */
    val openMessagesRequest = mutableStateOf(false)

    /** A problem notice asked for Health check, where each fix is one tap away. */
    val openHealthRequest = mutableStateOf(false)

    private val viewModel: AppViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as App
                AppViewModel(app.settings, app.appLock, app.databaseDamaged)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(DebugHooks.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Every screen has a dark bar at the top, so the status bar icons are always light.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Opening normally would only crash again. This way the details can still reach Swastik.
        if ((application as App).keepsCrashing()) {
            startActivity(Intent(this, RecoveryActivity::class.java))
            finish()
            return
        }
        splash.setKeepOnScreenCondition { viewModel.uiState.value.loading }
        (application as App).watchForeground()
        DebugHooks.onActivityCreated(this)
        openMessagesRequest.value = intent.getBooleanExtra(EXTRA_OPEN_MESSAGES, false)
        openHealthRequest.value = intent.getBooleanExtra(EXTRA_OPEN_HEALTH, false)
        setContent {
            MbTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_MESSAGES, false)) openMessagesRequest.value = true
        if (intent.getBooleanExtra(EXTRA_OPEN_HEALTH, false)) openHealthRequest.value = true
    }

    companion object {
        const val EXTRA_OPEN_MESSAGES = "openMessages"
        const val EXTRA_OPEN_HEALTH = "openHealth"
    }
}
