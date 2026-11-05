package com.swastik.mbfitness

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.swastik.mbfitness.data.AadhaarVault
import com.swastik.mbfitness.data.AppDatabase
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
 * settings can't be opened yet.
 */
class App : Application() {
    val clock: TodayProvider = SystemTodayProvider
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val settings: SettingsStore by lazy { SettingsStore(settingsDataStore, forceTestMode = BuildConfig.DEBUG) }
    val aadhaarVault: AadhaarVault by lazy { KeystoreAadhaarVault() }
    val members: MemberRepository by lazy { MemberRepository(database, settings, aadhaarVault, clock) }
}
