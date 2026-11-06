package com.swastik.mbfitness.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.domain.BackupRules
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.domain.RenewalRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.DayOfWeek

class FakeBackupFolder : BackupFolder {
    val files = linkedMapOf<String, ByteArray>()
    var usable = true
    var failsToWrite = false

    override fun isUsable() = usable
    override fun names() = files.keys.toList()
    override fun read(name: String) = files[name]

    override fun write(name: String, bytes: ByteArray) {
        if (failsToWrite) throw IOException("Disk full")
        files[name] = bytes
    }

    override fun delete(name: String) {
        files.remove(name)
    }
}

/** SPEC 15: "backup, wipe, restore round-trip", and Aadhaar across both kinds of backup. */
@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private val env = TestEnvironment()
    private val folder = FakeBackupFolder()
    private val keyVault = FakeAadhaarVault()
    private val filesDir: File = Files.createTempDirectory("mbf-files").toFile()
    private val backups = managerFor(env)
    private val digits = fakeAadhaarDigits()

    private fun managerFor(environment: TestEnvironment, vault: AadhaarVault = environment.vault) = BackupManager(
        environment.db,
        environment.settings,
        vault,
        keyVault,
        environment.clock,
        ErrorLogger(environment.db, environment.clock),
        "1.0.0",
        filesDir,
    ) { folder }

    @After
    fun tearDown() {
        env.close()
        filesDir.deleteRecursively()
    }

    private suspend fun gymWithData(): Long {
        env.settings.update {
            it.copy(
                gymName = "Iron Temple",
                trainerPhone = "+919000000099",
                upiId = "gym@okbank",
                reminderHour = 7,
                renewalRule = RenewalRule.START_FROM_PAYMENT,
                quietWeekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY),
                pinHash = "secret-hash",
                pinSalt = "secret-salt",
                recoveryCodeHash = "secret-code",
            )
        }
        val id = saved(MemberDraft("Rahul Sharma", "9876543210", 1, env.clock.today() - 10, smsConsent = true, aadhaar = fakeAadhaar(), aadhaarConsent = true))
        env.repository.recordRenewal(RenewalRequest(id, 1, 700, PaymentMode.UPI, env.clock.today()))
        val other = saved(MemberDraft("Anil Kumar", "9123456780", 2, env.clock.today() - 5, notes = "Morning batch"))
        env.repository.freeze(other)
        env.repository.addPlan("6 months", 6, 5000)
        env.db.quietDates().upsert(QuietDateEntity(date = env.clock.today() + 9, label = "Diwali"))
        env.db.broadcasts().insert(BroadcastEntity(body = "Closed", audience = "ALL_ACTIVE", createdAt = 5, recipientCount = 2))
        env.db.messages().insert(
            MessageEntity(memberId = id, phone = "+919876543210", kind = MessageKind.MANUAL, body = "Old", parts = 1, priority = 1, status = MessageStatus.SENT, createdAt = 5, notBefore = 0, sentAt = 6),
        )
        // The thank-you for that payment would still be waiting, which a restore stops on purpose.
        env.db.backup().stopUnsent("Settled for this test")
        return id
    }

    private suspend fun saved(draft: MemberDraft) = (env.repository.addMember(draft) as MemberSaveResult.Saved).memberId

    private suspend fun wipe() {
        val dao = env.db.backup()
        dao.clearMessages()
        dao.clearEvents()
        dao.clearFreezes()
        dao.clearPayments()
        dao.clearMembers()
        dao.clearPlans()
        dao.clearTemplates()
        dao.clearBroadcasts()
        dao.clearQuietDates()
        dao.clearCounters()
    }

    private suspend fun ready(bytes: ByteArray, password: String? = null, manager: BackupManager = backups) =
        manager.open(bytes, password) as RestoreOpen.Ready

    @Test
    fun aBackupWipeAndRestoreGivesBackAByteIdenticalFile() = runTest {
        gymWithData()
        val first = backups.buildFile()!!
        wipe()
        assertTrue(env.repository.existingPhones().isEmpty())

        val result = backups.restore(ready(first).plan)
        assertEquals(BackupRestoreResult.Restored(0), result)
        assertEquals(first.toString(Charsets.UTF_8), backups.buildFile()!!.toString(Charsets.UTF_8))
    }

    @Test
    fun thePreviewReadsLikeTheSpec() = runTest {
        gymWithData()
        val open = ready(backups.buildFile()!!)
        assertEquals("2 members, 1 payment, last payment ${Dates.formatShort(env.clock.today())}", open.summary)
        assertNull(open.aadhaarNote)
    }

    @Test
    fun openingAFileChangesNothing() = runTest {
        gymWithData()
        val bytes = backups.buildFile()!!
        val before = env.repository.existingPhones()
        backups.open(bytes)
        assertEquals(before, env.repository.existingPhones())
    }

    @Test
    fun theFileNeverHoldsPinDetails() = runTest {
        gymWithData()
        val text = backups.buildFile()!!.toString(Charsets.UTF_8)
        assertFalse("secret-hash" in text)
        assertFalse("secret-salt" in text)
        assertFalse("secret-code" in text)
        assertTrue(BackupRules.FORMAT in text)
        assertTrue("\"appVersion\":\"1.0.0\"" in text)
    }

    @Test
    fun settingsComeBackButPinAndTestModeStayAsTheyAreHere() = runTest {
        gymWithData()
        val bytes = backups.buildFile()!!
        env.settings.update { AppSettings(testMode = true, pinHash = "new-pin", backupFolderUri = "here", simSubscriptionId = 3, lastBackupDay = 5) }
        backups.restore(ready(bytes).plan)

        val after = env.settings.current()
        assertEquals("Iron Temple", after.gymName)
        assertEquals("+919000000099", after.trainerPhone)
        assertEquals(7, after.reminderHour)
        assertEquals(RenewalRule.START_FROM_PAYMENT, after.renewalRule)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), after.quietWeekdays)
        assertEquals("new-pin", after.pinHash)
        assertTrue(after.testMode)
        assertEquals("here", after.backupFolderUri)
        assertEquals(3, after.simSubscriptionId)
        assertEquals(5L, after.lastBackupDay)
        assertTrue(after.onboardingDone)
    }

    @Test
    fun afterARestoreNothingRunsUntilTomorrow() = runTest {
        gymWithData()
        val bytes = backups.buildFile()!!
        env.settings.update { it.copy(lastDailyRunDay = env.clock.today() - 3) }
        backups.restore(ready(bytes).plan)
        assertEquals(env.clock.today(), env.settings.current().lastDailyRunDay)
    }

    /** They might have gone after the backup was taken, and a second copy is the worse mistake. */
    @Test
    fun whatWasWaitingWhenTheBackupWasTakenIsNotSentAfterARestore() = runTest {
        val id = gymWithData()
        env.db.messages().insert(MessageEntity(memberId = id, phone = "+919876543210", kind = MessageKind.ON_DUE, body = "Due", parts = 1, priority = 3, dedupeKey = "k1", createdAt = 7, notBefore = 0))
        env.db.messages().insert(MessageEntity(memberId = id, phone = "+919876543210", kind = MessageKind.MANUAL, body = "Hi", parts = 1, priority = 1, status = MessageStatus.SENDING, createdAt = 7, notBefore = 0))
        backups.restore(ready(backups.buildFile()!!).plan)

        assertTrue(env.db.messages().withStatusOnly(MessageStatus.QUEUED).isEmpty())
        assertTrue(env.db.messages().withStatusOnly(MessageStatus.SENDING).isEmpty())
        assertTrue("its key stays, so it isn't planned again", "k1" in env.db.messages().usedKeys(0))
    }

    @Test
    fun receiptNumbersCarryOnAfterARestore() = runTest {
        val id = gymWithData()
        backups.restore(ready(backups.buildFile()!!).plan)
        val next = env.repository.recordRenewal(RenewalRequest(id, 1, 1200, PaymentMode.CASH, env.clock.today()))
        assertEquals("MBF-0002", next.receipt)
    }

    // ---- Aadhaar in a backup with no password ----

    @Test
    fun aBackupWithNoPasswordHoldsOnlyTheSealedNumber() = runTest {
        gymWithData()
        val text = backups.buildFile()!!.toString(Charsets.UTF_8)
        assertFalse("the full number must not be in the file", digits in text)
        assertTrue(digits.takeLast(4) in text)
    }

    @Test
    fun theSamePhoneKeepsTheFullNumberFromAPlainBackup() = runTest {
        val id = gymWithData()
        val bytes = backups.buildFile()!!
        wipe()
        assertEquals(BackupRestoreResult.Restored(0), backups.restore(ready(bytes).plan))
        assertEquals(digits, env.repository.revealAadhaar(id)?.revealDigits())
    }

    @Test
    fun aNewPhoneGetsTheLast4FromAPlainBackupAndIsToldBeforehand() = runTest {
        val id = gymWithData()
        val bytes = backups.buildFile()!!
        env.vault.keyLost = true

        val open = ready(bytes)
        assertEquals(BackupRules.describeLost(1), open.aadhaarNote)
        wipe()
        assertEquals(BackupRestoreResult.Restored(1), backups.restore(open.plan))

        val member = env.repository.member(id)!!
        assertEquals(digits.takeLast(4), member.aadhaarLast4)
        assertNull("it can't be read here", env.repository.revealAadhaar(id))
        assertEquals("the money is all there", 500L, env.repository.balanceOf(id))
    }

    // ---- Aadhaar in a password backup ----

    private val password = "correct horse"

    @Test
    fun aPasswordBackupIsUnreadableWithoutThePassword() = runTest {
        gymWithData()
        assertTrue(backups.setPassword(password, "the usual"))
        val text = backups.buildFile()!!.toString(Charsets.UTF_8)
        listOf(digits, "Rahul Sharma", "9876543210", "Iron Temple").forEach { assertFalse(it, it in text) }
        assertTrue("the hint is readable, so it can be shown", "the usual" in text)
        assertTrue("\"encrypted\":true" in text)
    }

    @Test
    fun aPasswordBackupAsksThenRefusesTheWrongOneThenOpens() = runTest {
        gymWithData()
        backups.setPassword(password, "the usual")
        val bytes = backups.buildFile()!!

        assertEquals(RestoreOpen.NeedsPassword("the usual"), backups.open(bytes))
        assertEquals(RestoreOpen.WrongPassword, backups.open(bytes, "wrong password"))
        assertTrue(backups.open(bytes, password) is RestoreOpen.Ready)
    }

    @Test
    fun aPasswordBackupBringsTheFullNumberBackOnANewPhone() = runTest {
        val id = gymWithData()
        backups.setPassword(password, null)
        val bytes = backups.buildFile()!!

        // A new phone: the old key is gone, but this phone's vault works for anything new.
        val newVault = object : AadhaarVault by env.vault {
            override fun decrypt(cipherText: String) = if (cipherText.startsWith("new:")) cipherText.removePrefix("new:") else null
            override fun encrypt(digits: String) = "new:$digits"
        }
        val onNewPhone = managerFor(env, newVault)
        val open = ready(bytes, password, onNewPhone)
        assertNull("nothing to re-enter", open.aadhaarNote)
        wipe()
        assertEquals(BackupRestoreResult.Restored(0), onNewPhone.restore(open.plan))
        assertEquals("new:$digits", env.repository.member(id)!!.aadhaarEnc)
    }

    @Test
    fun theSameDataComesBackAfterAPasswordRoundTrip() = runTest {
        gymWithData()
        backups.setPassword(password, null)
        val before = backups.buildFile()!!
        val open = ready(before, password)
        wipe()
        backups.restore(open.plan)
        val phones = env.repository.existingPhones()
        assertEquals(setOf("+919876543210", "+919123456780"), phones.keys)
        // A fresh IV each time means the bytes differ, as they must. The data inside doesn't.
        assertNotEquals(before.toString(Charsets.UTF_8), backups.buildFile()!!.toString(Charsets.UTF_8))
        assertEquals(open.plan.data, ready(backups.buildFile()!!, password).plan.data)
    }

    @Test
    fun changingThePasswordOnlyOpensNewBackupsWithTheNewOne() = runTest {
        gymWithData()
        backups.setPassword(password, null)
        val old = backups.buildFile()!!
        backups.setPassword("another password", null)
        val fresh = backups.buildFile()!!
        assertTrue(backups.open(old, password) is RestoreOpen.Ready)
        assertTrue(backups.open(fresh, "another password") is RestoreOpen.Ready)
        assertEquals(RestoreOpen.WrongPassword, backups.open(fresh, password))
    }

    @Test
    fun removingThePasswordGoesBackToPlainBackups() = runTest {
        gymWithData()
        backups.setPassword(password, "hint")
        backups.removePassword()
        val current = env.settings.current()
        assertFalse(current.backupPasswordSet)
        assertNull(current.backupKeyWrapped)
        assertNull(current.backupSalt)
        assertNull(current.backupPasswordHint)
        assertFalse(digits in backups.buildFile()!!.toString(Charsets.UTF_8))
    }

    @Test
    fun ifTheKeystoreRefusesThePasswordIsntSetAtAll() = runTest {
        keyVault.refusesToEncrypt = true
        assertFalse(backups.setPassword(password, null))
        assertFalse(env.settings.current().backupPasswordSet)
    }

    /** Falling back to a plain file would quietly undo what the password was for. */
    @Test
    fun ifTheStoredKeyIsLostABackupStopsRatherThanGoingOutUnprotected() = runTest {
        gymWithData()
        env.settings.update { it.copy(backupFolderUri = "folder") }
        backups.setPassword(password, null)
        keyVault.keyLost = true
        assertNull(backups.buildFile())
        assertEquals(BackupOutcome.PasswordNeeded, backups.backupNow())
        assertTrue(folder.files.isEmpty())
    }

    // ---- Bad files ----

    @Test
    fun rubbishIsRefused() = runTest {
        assertTrue(backups.open("not json".toByteArray()) is RestoreOpen.NotABackup)
        assertTrue(backups.open("{}".toByteArray()) is RestoreOpen.NotABackup)
        assertTrue(backups.open("""{"format":"something-else","schemaVersion":1}""".toByteArray()) is RestoreOpen.NotABackup)
        assertTrue(backups.open("""{"format":"${BackupRules.FORMAT}","schemaVersion":1,"appVersion":"1"}""".toByteArray()) is RestoreOpen.NotABackup)
    }

    @Test
    fun aFileFromANewerAppIsRefusedWithoutReadingTheRest() = runTest {
        val text = """{"format":"${BackupRules.FORMAT}","schemaVersion":${AppDatabase.VERSION + 1},"somethingNew":[1,2,3]}"""
        assertEquals(RestoreOpen.TooNew, backups.open(text.toByteArray()))
    }

    @Test
    fun aDamagedFileIsRefusedBeforeAnythingIsTouched() = runTest {
        gymWithData()
        val text = backups.buildFile()!!.toString(Charsets.UTF_8)
        val broken = text.replace("\"memberId\":1,", "\"memberId\":999,")
        val open = backups.open(broken.toByteArray())
        assertTrue(open.toString(), open is RestoreOpen.NotABackup)
        assertTrue((open as RestoreOpen.NotABackup).reason, "damaged" in open.reason)
        assertEquals(2, env.repository.existingPhones().size)
    }

    // ---- The folder ----

    private suspend fun useFolder() = env.settings.update { it.copy(backupFolderUri = "folder") }

    @Test
    fun aBackupGoesToTheFolderAndIsRemembered() = runTest {
        gymWithData()
        useFolder()
        assertEquals(BackupOutcome.Saved(BackupRules.fileName(env.clock.today())), backups.backupNow())
        assertEquals(listOf(BackupRules.fileName(env.clock.today())), folder.names())
        assertEquals(env.clock.today(), env.settings.current().lastBackupDay)
    }

    @Test
    fun noFolderOrAMissingOneIsReportedNotHidden() = runTest {
        assertEquals(BackupOutcome.NoFolder, backups.backupNow())
        useFolder()
        folder.usable = false
        assertEquals(BackupOutcome.FolderGone, backups.backupNow())
        assertNull(env.settings.current().lastBackupDay)
    }

    @Test
    fun aFailedWriteIsLoggedAndNotCountedAsABackup() = runTest {
        useFolder()
        folder.failsToWrite = true
        assertTrue(backups.backupNow() is BackupOutcome.Failed)
        assertNull(env.settings.current().lastBackupDay)
    }

    @Test
    fun onlyTheNewestEightStayInTheFolder() = runTest {
        useFolder()
        repeat(10) { index ->
            env.clock.day += 7L
            backups.backupNow()
            assertTrue("after backup $index", folder.files.size <= 8)
        }
        assertEquals(8, folder.files.size)
        assertEquals(BackupRules.fileName(env.clock.today()), BackupRules.newest(folder.names()))
    }

    @Test
    fun theWeeklyBackupOnlyRunsWhenDue() = runTest {
        useFolder()
        // Friday, never backed up: due now.
        assertTrue(backups.runWeeklyIfDue())
        assertEquals(1, folder.files.size)
        // Saturday: not due.
        env.clock.day += 1
        assertTrue(backups.runWeeklyIfDue())
        assertEquals(1, folder.files.size)
        // Sunday: due.
        env.clock.day += 1
        assertTrue(backups.runWeeklyIfDue())
        assertEquals(2, folder.files.size)
    }

    @Test
    fun theWeeklyBackupSaysSoIfItCouldntHappen() = runTest {
        assertTrue("no folder isn't a failure here. Health check nags about it.", backups.runWeeklyIfDue())
        useFolder()
        folder.usable = false
        assertFalse(backups.runWeeklyIfDue())
    }

    // ---- The safety copy ----

    @Test
    fun aSafetyCopyIsSavedBeforeARestoreAndCanPutTheDataBack() = runTest {
        gymWithData()
        val old = backups.buildFile()!!
        val added = saved(MemberDraft("New Person", "9000000011", 1, env.clock.today()))
        assertFalse(backups.hasSafetyCopy())

        backups.restore(ready(old).plan)
        assertNull("the restore removed them", env.repository.member(added))
        assertTrue(backups.hasSafetyCopy())

        backups.restore(ready(backups.safetyCopy()!!).plan)
        assertNotNull("and the safety copy puts them back", env.repository.member(added))
    }

    @Test
    fun theSafetyCopyAlsoGoesToTheFolderIfThereIsOne() = runTest {
        gymWithData()
        useFolder()
        backups.restore(ready(backups.buildFile()!!).plan)
        assertTrue(folder.names().any { it.startsWith("mbfitness_safety_") })
    }

    @Test
    fun aBrokenFolderDoesntBlockARestore() = runTest {
        gymWithData()
        useFolder()
        folder.failsToWrite = true
        assertTrue(backups.restore(ready(backups.buildFile()!!).plan) is BackupRestoreResult.Restored)
    }

    @Test
    fun theSafetyCopyNeverHoldsTheFullNumberInPlainText() = runTest {
        gymWithData()
        backups.setPassword(password, null)
        backups.restore(ready(backups.buildFile()!!, password).plan)
        assertFalse(digits in backups.safetyCopy()!!.toString(Charsets.UTF_8))
    }

    // ---- The logo ----

    @Test
    fun theLogoTravelsWithTheBackup() = runTest {
        val logo = File(filesDir, "logo/logo_1.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        env.settings.update { it.copy(logoFile = "logo/logo_1.png") }
        val bytes = backups.buildFile()!!
        logo.delete()
        env.settings.update { it.copy(logoFile = null) }

        backups.restore(ready(bytes).plan)
        val restored = File(filesDir, env.settings.current().logoFile!!)
        assertEquals(listOf<Byte>(1, 2, 3, 4), restored.readBytes().toList())
    }

    @Test
    fun aBackupWithNoLogoClearsTheOldOne() = runTest {
        val bytes = backups.buildFile()!!
        File(filesDir, "logo/logo_1.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        env.settings.update { it.copy(logoFile = "logo/logo_1.png") }
        backups.restore(ready(bytes).plan)
        assertNull(env.settings.current().logoFile)
        assertTrue(File(filesDir, "logo").listFiles().orEmpty().isEmpty())
    }

    // ---- The database itself ----

    @Test
    fun aHealthyDatabasePassesItsCheck() = runTest {
        gymWithData()
        assertTrue(env.db.passesQuickCheck())
    }

    /** Android would delete this and start an empty one. The app has to be told. */
    @Test
    fun aDamagedDatabaseIsKeptAndReported() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("damaged.db")
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(8192) { 7 })
        var told = false
        val callback = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = KeepDamagedDatabaseFactory { told = true }.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name("damaged.db").callback(callback).build(),
        )
        runCatching { helper.writableDatabase.query("SELECT 1").close() }
        helper.close()
        assertTrue("the app is told", told)
        assertTrue("a copy is kept", File("${file.path}.damaged").exists())
    }

    // ---- CSV ----

    @Test
    fun csvExportsNeverIncludeAadhaar() = runTest {
        gymWithData()
        val members = backups.membersCsv()
        val payments = backups.paymentsCsv()
        assertFalse(digits in members)
        assertFalse(digits.takeLast(4) in members)
        assertFalse("aadhaar" in members.lowercase())
        assertFalse("sealed" in members)
        assertFalse(digits in payments)
    }

    @Test
    fun csvExportsHoldBalancesAndPayments() = runTest {
        gymWithData()
        val members = backups.membersCsv().trim().split("\r\n")
        assertEquals(3, members.size)
        assertTrue(members[1], members[1].startsWith("Rahul Sharma,9876543210,1,"))
        assertTrue("the balance", ",500," in members[1])
        val payments = backups.paymentsCsv()
        assertTrue(payments, "MBF-0001,Rahul Sharma,9876543210,renewal," in payments)
        assertTrue(payments, ",upi," in payments)
    }

    @Test
    fun deletedMembersAreLeftOutOfTheCsv() = runTest {
        val id = gymWithData()
        env.repository.deleteMember(id)
        assertFalse("Rahul" in backups.membersCsv())
    }
}
