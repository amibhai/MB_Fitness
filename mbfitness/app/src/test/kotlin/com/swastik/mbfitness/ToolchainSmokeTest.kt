package com.swastik.mbfitness

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves Robolectric runs against the app's real manifest and resources. */
@RunWith(AndroidJUnit4::class)
class ToolchainSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun appStartsWithItsOwnApplicationClass() {
        assertTrue(context is App)
    }

    @Test
    fun resourcesAreAvailable() {
        assertEquals("MB Fitness Club", context.getString(R.string.app_name))
    }
}
