package com.swastik.mbfitness

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.swastik.mbfitness.data.AadhaarVault
import com.swastik.mbfitness.data.AppDatabase
import com.swastik.mbfitness.data.AppLock
import com.swastik.mbfitness.data.KeystoreAadhaarVault
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.SystemTodayProvider
import com.swastik.mbfitness.domain.TodayProvider

/** Lives at files/datastore/settings.preferences_pb, which the backup rules include. */
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Manual DI container (SPEC 5.3). Everything added here must stay lazy: this class also runs
 * when a boot receiver starts the process before the first unlock, when the database and
 * settings can't be opened yet. Tests subclass it to swap in fakes.
 */
open class App : Application() {
    open val clock: TodayProvider = SystemTodayProvider
    val database: AppDatabase by lazy { createDatabase() }
    val settings: SettingsStore by lazy { createSettings() }
    val aadhaarVault: AadhaarVault by lazy { createVault() }
    val members: MemberRepository by lazy { MemberRepository(database, settings, aadhaarVault, clock) }
    val appLock: AppLock by lazy { AppLock(settings, clock) }

    protected open fun createDatabase(): AppDatabase = AppDatabase.create(this)
    protected open fun createSettings(): SettingsStore = SettingsStore(settingsDataStore, forceTestMode = BuildConfig.DEBUG)
    protected open fun createVault(): AadhaarVault = KeystoreAadhaarVault()

    private var watchingForeground = false

    /**
     * Called from MainActivity rather than onCreate: this process can start before the first
     * unlock, when ProcessLifecycleOwner isn't available.
     */
    fun watchForeground() {
        if (watchingForeground) return
        watchingForeground = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = appLock.onForegrounded()
                override fun onStop(owner: LifecycleOwner) = appLock.onBackgrounded()
            },
        )
    }
}
