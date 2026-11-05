package com.swastik.mbfitness

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.fragment.app.FragmentActivity

// FragmentActivity because androidx.biometric's BiometricPrompt requires it (Phase 3).
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text(getString(R.string.app_name))
                }
            }
        }
    }
}
