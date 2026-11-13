package com.swastik.mbfitness.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.swastik.mbfitness.domain.Aadhaar
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.PackLanguage
import com.swastik.mbfitness.domain.TemplatePacks
import com.swastik.mbfitness.domain.TodayProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files

class FakeClock(var day: Long, var hour: Int = 10) : TodayProvider {
    private var extraMillis = 0L
    override fun today(): Long = day
    override fun nowMillis(): Long = Dates.millisAt(day, hour) + extraMillis++

    fun advance(millis: Long) {
        extraMillis += millis
    }
}

/** Stands in for the Keystore, which Robolectric doesn't have. Can also fail on demand. */
class FakeAadhaarVault : AadhaarVault {
    var refusesToEncrypt = false
    var keyLost = false

    override fun encrypt(digits: String): String? = if (refusesToEncrypt) null else PREFIX + digits.reversed()

    override fun decrypt(cipherText: String): String? =
        if (keyLost) null else cipherText.removePrefix(PREFIX).reversed()

    private companion object {
        const val PREFIX = "sealed:"
    }
}

/** A fresh in-memory database and settings file, wired up the way App.kt wires the real ones. */
class TestEnvironment(startDay: Long = Dates.of(2027, 1, 1)) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder: File = Files.createTempDirectory("mbf").toFile()
    val clock = FakeClock(startDay)
    val vault = FakeAadhaarVault()
    val db: AppDatabase = AppDatabase.configure(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)).build()
    val settings = SettingsStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            File(folder, "settings.preferences_pb")
        },
        forceTestMode = false,
    )
    val repository = MemberRepository(db, settings, vault, clock)

    fun close() {
        db.close()
        folder.deleteRecursively()
    }
}

/**
 * For tests about other SMS: a member who agrees to SMS also gets a welcome (1.0.2), so this
 * switches it off, the same way bhaiya can in Templates.
 */
suspend fun AppDatabase.switchOffWelcome() =
    templates().upsert(TemplatePacks.preset(MessageKind.WELCOME, PackLanguage.ENGLISH)!!.copy(enabled = false).toEntity())

/** Made up: the 9999 prefix is the range UIDAI uses for test numbers. Never a real person's. */
fun fakeAadhaarDigits(first11: String = "99990000001"): String = first11 + checkDigitOf(first11)

fun fakeAadhaar(first11: String = "99990000001"): Aadhaar =
    (Aadhaar.parse(fakeAadhaarDigits(first11)) as Aadhaar.ParseResult.Valid).aadhaar

private fun checkDigitOf(digits: String): Char =
    ('0'..'9').first { (Aadhaar.parse(digits + it) as? Aadhaar.ParseResult.Valid) != null }
