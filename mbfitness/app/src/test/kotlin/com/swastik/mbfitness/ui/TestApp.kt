package com.swastik.mbfitness.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.swastik.mbfitness.App
import com.swastik.mbfitness.data.AadhaarVault
import com.swastik.mbfitness.data.AppDatabase
import com.swastik.mbfitness.data.FakeAadhaarVault
import com.swastik.mbfitness.data.FakeClock
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.TodayProvider
import com.swastik.mbfitness.sms.FakeSmsSender
import com.swastik.mbfitness.sms.SmsSender
import com.swastik.mbfitness.work.FakeKicker
import com.swastik.mbfitness.work.FakeNotices
import com.swastik.mbfitness.work.Notices
import com.swastik.mbfitness.work.OutboxKicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files

/** The real app, with an in-memory database, a settable clock and no real Keystore. */
class TestApp : App() {
    val fakeClock = FakeClock(Dates.of(2027, 1, 1))
    val fakeVault = FakeAadhaarVault()
    override val clock: TodayProvider get() = fakeClock

    override fun createDatabase(): AppDatabase =
        AppDatabase.configure(Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java)).build()

    override fun createSettings(): SettingsStore {
        val folder = Files.createTempDirectory("mbf").toFile()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            File(folder, "settings.preferences_pb")
        }
        return SettingsStore(store, forceTestMode = true)
    }

    override fun createVault(): AadhaarVault = fakeVault

    val fakeSender = FakeSmsSender()
    val fakeNotices = FakeNotices()
    val fakeKicker = FakeKicker()
    override val notices: Notices get() = fakeNotices
    override fun createSender(): SmsSender = fakeSender
    override fun createKicker(): OutboxKicker = fakeKicker

    /** No alarms or WorkManager in unit tests. */
    override fun onAppOpened() = Unit

    override suspend fun armReminders() = Unit

    var smsAllowed = true
    override fun canSendSms(): Boolean = smsAllowed
}
