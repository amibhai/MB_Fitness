package com.swastik.mbfitness.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.data.MemberDraft
import com.swastik.mbfitness.data.MemberSaveResult
import com.swastik.mbfitness.data.fakeAadhaar
import com.swastik.mbfitness.domain.BackupRules
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.SnackbarController
import com.swastik.mbfitness.ui.navigation.AppNavigation
import com.swastik.mbfitness.ui.screens.onboarding.OnboardingScreen
import com.swastik.mbfitness.ui.theme.MbTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings, Health check, backups, PIN and onboarding, on the real screens with fakes behind them. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
@RunWith(AndroidJUnit4::class)
class SafetyUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val today get() = app.fakeClock.today()

    private fun showApp() {
        runBlocking { app.settings.update { it.copy(lastBackupDay = app.fakeClock.today(), trainerPhone = it.trainerPhone.ifBlank { "+919000000099" }) } }
        compose.setContent { MbTheme { AppNavigation() } }
    }

    private fun waitForText(text: String) = compose.waitUntil(WAIT_MILLIS) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun gone(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(WAIT_MILLIS) {
        compose.onAllNodesWithText("Anything").fetchSemanticsNodes()
        condition()
    }

    private fun tap(label: String) {
        waitForText(label)
        runCatching { compose.onNodeWithText(label).performScrollTo() }
        compose.onNodeWithText(label).performClick()
    }

    private fun type(fieldLabel: String, text: String) {
        waitForText(fieldLabel)
        compose.onNode(hasSetTextAction() and hasText(fieldLabel)).performTextReplacement(text)
    }

    private fun current() = runBlocking { app.settings.current() }

    private fun openSettings(row: String? = null) {
        compose.onNodeWithText("Settings").performClick()
        waitForText("Plans and prices")
        row?.let(::tap)
    }

    private fun addMember(aadhaar: Boolean = false): Long = runBlocking {
        val draft = MemberDraft(
            "Rahul Sharma",
            "9876543210",
            1,
            today + 30,
            smsConsent = true,
            aadhaar = if (aadhaar) fakeAadhaar() else null,
            aadhaarConsent = aadhaar,
        )
        (app.members.addMember(draft) as MemberSaveResult.Saved).memberId
    }

    // ---- Settings ----

    @Test
    fun gymDetailsCanBeChangedAndAreChecked() {
        showApp()
        openSettings("Gym name")
        type("Gym name", "   ")
        tap("Save")
        waitForText("Gym name can't be empty")
        type("Gym name", "Iron Temple")
        tap("Save")
        waitFor { current().gymName == "Iron Temple" }
        waitForText("Iron Temple")
    }

    @Test
    fun theOwnersNumberIsCheckedAndStoredProperly() {
        showApp()
        openSettings("Your phone number")
        type("Your phone number", "12345")
        tap("Save")
        waitForText("Number must be 10 digits")
        type("Your phone number", "9000000099")
        tap("Save")
        waitFor { current().trainerPhone == "+919000000099" }
        waitForText("90000 00099")
    }

    @Test
    fun theUpiIdIsChecked() {
        showApp()
        openSettings("UPI ID")
        type("UPI ID", "not a upi id")
        tap("Save")
        waitForText("doesn't look like a UPI ID")
        type("UPI ID", "gym@okbank")
        tap("Save")
        waitFor { current().upiId == "gym@okbank" }
    }

    @Test
    fun theDailyLimitStaysInRange() {
        showApp()
        openSettings("Daily SMS limit")
        type("Daily SMS limit", "500")
        tap("Save")
        waitForText("Use a number from 5 to 100")
        type("Daily SMS limit", "50")
        tap("Save")
        waitFor { current().dailyCapParts == 50 }
    }

    @Test
    fun theRenewalRuleCanBeChanged() {
        showApp()
        openSettings("Renewal rule")
        tap("Start from the payment date")
        waitFor { current().renewalRule == com.swastik.mbfitness.domain.RenewalRule.START_FROM_PAYMENT }
    }

    @Test
    fun aTestBuildCantTurnOffTestMode() {
        showApp()
        openSettings()
        waitForText("Always on in this test build")
    }

    @Test
    fun theMorningSummaryCanBeTurnedOff() {
        showApp()
        openSettings()
        tap("Morning summary")
        waitFor { !current().morningSummary }
        assertTrue(gone("Also when all is clear"))
    }

    // ---- The PIN ----

    @Test
    fun withNoPinTheRowGoesStraightToSettingOne() {
        showApp()
        openSettings("App PIN")
        waitForText("Choose a PIN")
    }

    /** The open item from Phase 3: nobody can change a PIN they don't know. */
    @Test
    fun aPinCantBeChangedWithoutTheCurrentOne() {
        runBlocking { app.appLock.setPin("1234", newRecoveryCode = true) }
        app.appLock.markUnlocked()
        showApp()
        openSettings("App PIN")
        waitForText("Enter your PIN")
        assertTrue(gone("Change PIN"))
        listOf("1", "2", "3", "4").forEach { compose.onNodeWithText(it).performClick() }
        waitForText("Change PIN")
        waitForText("Turn off PIN")
    }

    @Test
    fun turningOffThePinWarnsAboutAadhaarAndWorks() {
        runBlocking { app.appLock.setPin("1234", newRecoveryCode = true) }
        app.appLock.markUnlocked()
        addMember(aadhaar = true)
        showApp()
        openSettings("App PIN")
        waitForText("Enter your PIN")
        listOf("1", "2", "3", "4").forEach { compose.onNodeWithText(it).performClick() }
        tap("Turn off PIN")
        waitForText("Aadhaar numbers stay encrypted")
        tap("Turn off")
        waitFor { current().pinHash == null }
    }

    @Test
    fun aNewRecoveryCodeReplacesTheOldOne() {
        val old = runBlocking { app.appLock.setPin("1234", newRecoveryCode = true) }!!
        app.appLock.markUnlocked()
        showApp()
        openSettings("App PIN")
        waitForText("Enter your PIN")
        listOf("1", "2", "3", "4").forEach { compose.onNodeWithText(it).performClick() }
        tap("Get a new recovery code")
        tap("Get a new one")
        waitForText("Write this down")
        assertFalse("the old code stopped working", runBlocking { app.appLock.checkRecoveryCode(old) })
    }

    // ---- Health check: SPEC 17's "correct states when permissions are toggled" ----

    private fun openHealth() {
        openSettings("Health check")
        waitForText("Reminders")
    }

    /** A long list only builds what's on screen, so rows further down need scrolling to. */
    private fun scrollListTo(text: String) {
        compose.waitUntil(WAIT_MILLIS) {
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true)) }.isSuccess
        }
        waitForText(text)
    }

    @Test
    fun aHealthyPhoneShowsNoFixes() {
        runBlocking {
            app.settings.update { it.copy(backupFolderUri = "folder", lastBackupDay = app.fakeClock.today()) }
        }
        compose.setContent { MbTheme { AppNavigation() } }
        app.fakeFolder.usable = true
        openHealth()
        waitForText("Exact alarms")
        assertTrue(compose.onAllNodesWithText("Allow").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Turn off").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun smsPermissionOffShowsAFix() {
        app.smsAllowed = false
        showApp()
        openHealth()
        waitForText("Allow restricted settings")
        assertTrue(compose.onAllNodesWithText("Fix").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun exactAlarmsOffShowsAFix() {
        app.fakeChecks.exactAlarms = false
        showApp()
        openHealth()
        waitForText("up to 30 minutes late")
        assertTrue(compose.onAllNodesWithText("Allow").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun exactAlarmsAreLeftOutWhereTheyDontApply() {
        app.fakeChecks.exactAlarms = null
        showApp()
        openHealth()
        waitForText("Battery saver")
        assertTrue(gone("Exact alarms"))
    }

    @Test
    fun batteryNotificationsAndHibernationEachShowUp() {
        app.fakeChecks.battery = false
        app.fakeChecks.notifications = false
        app.fakeChecks.hibernation = false
        showApp()
        openHealth()
        waitForText("Android may stop reminders running")
        waitForText("you won't get the morning summary")
        waitForText("if the app isn't opened for a while")
    }

    @Test
    fun aMissingSimIsAProblem() {
        app.fakeChecks.sim = false
        showApp()
        openHealth()
        waitForText("No SIM found")
    }

    @Test
    fun theLastCrashIsReported() {
        app.fakeChecks.exitReason = "It crashed"
        showApp()
        openHealth()
        scrollListTo("It crashed")
    }

    @Test
    fun aadhaarShowsACountAndNeverANumber() {
        addMember(aadhaar = true)
        showApp()
        openHealth()
        scrollListTo("1 saved, with no app PIN")
        val digits = com.swastik.mbfitness.data.fakeAadhaarDigits()
        assertTrue(gone(digits))
        assertTrue(gone(digits.takeLast(4)))
    }

    @Test
    fun theErrorLogOpensAndCanBeCopied() {
        runBlocking { app.errors.log("Test", "Something odd") }
        showApp()
        openHealth()
        scrollListTo("1 entry")
        tap("Open")
        waitForText("Something odd")
        tap("Copy as text")
        waitForText("Copied")
    }

    // ---- Backup ----

    @Test
    fun backingUpNeedsAFolderFirst() {
        showApp()
        openSettings("Backup and restore")
        waitForText("Choose a folder to turn them on")
        compose.onNodeWithText("Back up now").assertIsNotEnabled()
    }

    private fun useFolder() = runBlocking { app.settings.update { it.copy(backupFolderUri = "folder") } }

    @Test
    fun aBackupGoesToTheFolderAndCanBeRestored() {
        val id = addMember()
        useFolder()
        showApp()
        openSettings("Backup and restore")
        tap("Back up now")
        waitForText("Backed up as ${BackupRules.fileName(today)}")
        assertEquals(1, app.fakeFolder.files.size)

        runBlocking { app.members.deleteMember(id) }
        tap("Restore latest backup")
        waitForText("Replace everything?")
        waitForText("1 member, 0 payments")
        waitForText("A safety copy is saved first")
        tap("Restore")
        waitForText("Restored")
        waitFor { runBlocking { app.members.member(id)?.deletedAt == null } }
        waitForText("Undo the last restore")
    }

    @Test
    fun aFolderWithNoReadableBackupSaysSoInPlainWords() {
        useFolder()
        app.fakeFolder.files[BackupRules.fileName(today)] = "this is not a backup".toByteArray()
        showApp()
        openSettings("Backup and restore")
        tap("Restore latest backup")
        waitForText("No backup in that folder can be read.")
    }

    /** It's made daily and folder backups weekly, so it's often the newer of the two. */
    @Test
    fun theAppsNewerCopyIsOfferedAboveAnOlderFolderBackup() {
        useFolder()
        addMember()
        app.fakeFolder.files[BackupRules.fileName(today - 7)] = runBlocking { app.backups.buildFile()!! }
        runBlocking {
            app.settings.update { it.copy(lastBackupDay = today) }
            app.backups.runScheduledBackups()
        }
        showApp()
        openSettings("Backup and restore")
        scrollListTo("Restore latest backup")
        tap("Restore the app's newer copy (1 Jan)")
        waitForText("Replace everything?")
        waitForText("1 member")
    }

    /** A newest file that didn't save whole mustn't hide the good one before it. */
    @Test
    fun restoreLatestUsesTheNewestBackupThatOpens() {
        useFolder()
        addMember()
        app.fakeFolder.files[BackupRules.fileName(today - 7)] = runBlocking { app.backups.buildFile()!! }
        app.fakeFolder.files[BackupRules.fileName(today)] = "{ half a fi".toByteArray()
        showApp()
        openSettings("Backup and restore")
        tap("Restore latest backup")
        waitForText("Replace everything?")
        waitForText("1 member")
    }

    @Test
    fun aPasswordIsCheckedAndWarnedAbout() {
        showApp()
        openSettings("Backup and restore")
        tap("Set a password")
        waitForText("can never be opened")
        type("Password", "short")
        type("Password again", "short")
        tap("Save")
        waitForText("Use at least 8 characters")
        type("Password", "long enough one")
        type("Password again", "long enough one")
        type("Hint (optional)", "the usual")
        tap("Save")
        waitFor { current().backupPasswordSet }
        waitForText("Hint: the usual")
        waitForText("Change password")
    }

    @Test
    fun aPasswordBackupAsksForItOnRestore() {
        addMember()
        useFolder()
        runBlocking { app.backups.setPassword("long enough one", "the usual") }
        showApp()
        openSettings("Backup and restore")
        tap("Back up now")
        waitForText("Backed up as")
        tap("Restore latest backup")
        waitForText("Backup password")
        waitForText("Hint: the usual")
        compose.onNode(hasSetTextAction() and hasText("Password")).performTextInput("wrong password")
        tap("Open")
        waitForText("That isn't the password")
        compose.onNode(hasSetTextAction() and hasText("Password")).performTextReplacement("long enough one")
        tap("Open")
        waitForText("Replace everything?")
    }

    @Test
    fun homeNagsAboutAMissingBackupOnceThereAreMembers() {
        compose.setContent { MbTheme { AppNavigation() } }
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodesWithContentDescription("Collected this month", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("nothing to protect yet", gone("no backup yet"))
        addMember()
        waitForText("Your data has no backup yet.")
        tap("Set up")
        waitForText("Choose a folder to turn them on")
    }

    @Test
    fun anOverdueBackupComesAboveTheSixtyDaySuggestion() {
        runBlocking {
            app.settings.update { it.copy(backupFolderUri = "folder", lastBackupDay = app.fakeClock.today() - 20) }
            app.members.addMember(MemberDraft("Long Gone", "9876543210", 1, app.fakeClock.today() - 90))
        }
        compose.setContent { MbTheme { AppNavigation() } }
        waitForText("Your backup is overdue.")
        assertTrue(gone("60+ days overdue"))
    }

    /** Backups in a folder stay on the phone, so once a month Home asks for a copy elsewhere. */
    @Test
    fun homeAsksForACopyOffThePhoneOnceEverythingElseIsFine() {
        runBlocking {
            app.settings.update { it.copy(backupFolderUri = "folder", lastBackupDay = app.fakeClock.today(), trainerPhone = "+919000000099") }
            app.members.addMember(MemberDraft("Rahul Sharma", "9876543210", 1, app.fakeClock.today()))
        }
        compose.setContent { MbTheme { AppNavigation() } }
        waitForText("Keep a copy of your data off this phone")
        tap("Send")
        waitForText("None sent yet")
        scrollListTo("Send a copy")
    }

    @Test
    fun aCopySentThisMonthKeepsHomeQuiet() {
        runBlocking {
            app.settings.update { it.copy(backupFolderUri = "folder", lastBackupDay = app.fakeClock.today(), lastCopySentDay = app.fakeClock.today() - 29, trainerPhone = "+919000000099") }
            // Overdue, because that's the tab Home opens on.
            app.members.addMember(MemberDraft("Rahul Sharma", "9876543210", 1, app.fakeClock.today() - 5))
        }
        compose.setContent { MbTheme { AppNavigation() } }
        waitForText("Rahul Sharma")
        assertTrue(gone("Keep a copy of your data off this phone"))
    }

    /** Reminders that quote the owner's number wait until it's set (docs/DECISIONS.md). */
    @Test
    fun homeAsksForTheOwnersNumberWhenRemindersAreWaitingForIt() {
        runBlocking {
            app.settings.update { it.copy(backupFolderUri = "folder", lastBackupDay = app.fakeClock.today()) }
            app.members.addMember(MemberDraft("Rahul Sharma", "9876543210", 1, app.fakeClock.today()))
        }
        compose.setContent { MbTheme { AppNavigation() } }
        waitForText("Add your phone number")
        tap("Add")
        waitForText("Plans and prices")
    }

    /** With the phone's date this wrong, every date in the app would be too. */
    @Test
    fun homeSaysWhenThePhonesDateIsWrong() {
        app.fakeClock.day = com.swastik.mbfitness.domain.Dates.of(2020, 1, 1)
        compose.setContent { MbTheme { AppNavigation() } }
        waitForText("Your phone's date is wrong (1 Jan 2020)")
        app.fakeClock.day = com.swastik.mbfitness.domain.Dates.of(2027, 1, 1)
    }

    // ---- Old records ----

    private fun memberWhoLeft(name: String, phone: String, daysAgo: Long): Long = runBlocking {
        val id = (app.members.addMember(MemberDraft(name, phone, 1, today - daysAgo - 30)) as MemberSaveResult.Saved).memberId
        app.fakeClock.day = today - daysAgo
        app.members.markLeft(id)
        app.fakeClock.day = com.swastik.mbfitness.domain.Dates.of(2027, 1, 1)
        id
    }

    /** SPEC 12: a suggestion, never automatic, and it only happens after a yes. */
    @Test
    fun membersWhoLeftOverThreeYearsAgoAreOnlySuggested() {
        val old = memberWhoLeft("Long Gone", "9876543210", daysAgo = 1200)
        val recent = memberWhoLeft("Just Left", "9876543211", daysAgo = 100)
        showApp()
        waitForText("1 member left over 3 years ago")
        assertEquals("nothing has been removed yet", null, runBlocking { app.members.member(old)?.deletedAt })

        tap("Review")
        waitForText("Remove old records?")
        tap("Keep them")
        assertEquals(null, runBlocking { app.members.member(old)?.deletedAt })

        tap("Review")
        tap("Remove")
        waitFor { runBlocking { app.members.member(old)?.deletedAt != null } }
        assertEquals("someone who left recently stays", null, runBlocking { app.members.member(recent)?.deletedAt })
    }

    @Test
    fun someoneWhoRejoinedAndLeftAgainCountsFromTheLatestTime() {
        val id = memberWhoLeft("Came Back", "9876543210", daysAgo = 1200)
        runBlocking {
            app.members.rejoin(id)
            app.fakeClock.day = today - 10
            app.members.markLeft(id)
            app.fakeClock.day = com.swastik.mbfitness.domain.Dates.of(2027, 1, 1)
        }
        showApp()
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodesWithContentDescription("Collected this month", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(gone("left over 3 years ago"))
    }

    // ---- Help and About ----

    @Test
    fun helpHasItsSixTopics() {
        showApp()
        openSettings("Help")
        listOf("Record a payment", "Add a member", "Set a quiet day", "Send a broadcast", "Back up your data", "Reminders stopped?").forEach(::scrollListTo)
    }

    @Test
    fun aboutCreditsTheBuilderAndGivesTheContactForData() {
        runBlocking { app.settings.update { it.copy(trainerPhone = "+919000000099") } }
        showApp()
        openSettings("About")
        waitForText("Designed & developed by Swastik")
        waitForText("contact the gym on 90000 00099")
    }

    // ---- Onboarding ----

    private fun showOnboarding(onImport: () -> Unit = {}) {
        val snackbar = SnackbarController()
        compose.setContent {
            MbTheme {
                CompositionLocalProvider(LocalSnackbar provides snackbar) {
                    OnboardingScreen(onImportMembers = onImport)
                }
            }
        }
    }

    /** Every step can be skipped, and the app still ends up usable (SPEC 11.1). */
    @Test
    fun onboardingCanBeSkippedAllTheWayThrough() {
        app.smsAllowed = false
        showOnboarding()
        tap("Start")
        waitForText("Your gym")
        repeat(20) {
            if (compose.onAllNodesWithText("Skip for now").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("Skip for now").performClick()
                compose.waitForIdle()
            }
        }
        waitForText("You're set")
        assertFalse(current().onboardingDone)
        tap("Start using the app")
        waitFor { current().onboardingDone }
    }

    @Test
    fun theGymDetailsAreCheckedAndSaved() {
        showOnboarding()
        tap("Start")
        type("Your phone number", "123")
        tap("Next")
        waitForText("Number must be 10 digits")
        assertEquals("", current().trainerPhone)

        type("Your phone number", "9000000099")
        type("Gym name", "Iron Temple")
        type("UPI ID", "gym@okbank")
        tap("Next")
        waitForText("SMS permission")
        val saved = current()
        assertEquals("+919000000099", saved.trainerPhone)
        assertEquals("Iron Temple", saved.gymName)
        assertEquals("gym@okbank", saved.upiId)
        assertEquals("the payee defaults to the owner", saved.ownerName, saved.upiPayeeName)
    }

    @Test
    fun skippingTheGymStepSavesNothing() {
        showOnboarding()
        tap("Start")
        type("Gym name", "Half Typed")
        tap("Skip for now")
        waitForText("SMS permission")
        assertEquals("MB Fitness Club", current().gymName)
    }

    private fun goToStep(title: String) {
        tap("Start")
        repeat(20) {
            if (compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithText("Skip for now").performClick()
                compose.waitForIdle()
            }
        }
        waitForText(title)
    }

    @Test
    fun thePlanCheckSetsTheDailyLimit() {
        showOnboarding()
        goToStep("Your SIM plan")
        tap("Not sure")
        waitForText("The limit is set to 50 a day")
        assertEquals(50, current().dailyCapParts)
        tap("Next")
        waitFor { compose.onAllNodesWithText("Your SIM plan").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun answeringYesKeepsTheHigherLimit() {
        showOnboarding()
        goToStep("Your SIM plan")
        tap("Yes")
        waitFor { current().dailyCapParts == 90 }
    }

    /** Turning Test mode on here and skipping would have left members silently getting nothing. */
    @Test
    fun theTestStepGoesToTheOwnerAndLeavesTestModeAlone() {
        runBlocking { app.settings.update { it.copy(trainerPhone = "+919000000099") } }
        showOnboarding()
        goToStep("Send a test")
        tap("Send me a test SMS")
        waitForText("It can take a minute to arrive")
        val queued = runBlocking { app.database.messages().withStatusOnly(com.swastik.mbfitness.domain.MessageStatus.QUEUED) }
        assertEquals("+919000000099", queued.single().phone)
        assertFalse(current().testMode)
        assertTrue(app.fakeKicker.kicks > 0)
    }

    @Test
    fun theTestStepNeedsYourNumber() {
        showOnboarding()
        goToStep("Send a test")
        tap("Send me a test SMS")
        waitForText("Go back and add your phone number first")
    }

    @Test
    fun aPhoneWithOneSimIsNotAskedWhichOne() {
        app.fakeChecks.slots = 1
        showOnboarding()
        goToStep("Your SIM plan")
        assertTrue(gone("Which SIM?"))
    }

    @Test
    fun aDualSimPhoneWithOneSimSkipsTheQuestionAndBackStillWorks() {
        app.fakeChecks.slots = 2
        app.fakeChecks.sims = listOf(com.swastik.mbfitness.work.SimChoice(1, "Jio (SIM 1)"))
        app.smsAllowed = true
        showOnboarding()
        goToStep("Your SIM plan")
        tap("Back")
        waitForText("SMS permission")
    }

    @Test
    fun aDualSimPhoneChoosesOne() {
        app.fakeChecks.slots = 2
        app.fakeChecks.sims = listOf(com.swastik.mbfitness.work.SimChoice(1, "Jio (SIM 1)"), com.swastik.mbfitness.work.SimChoice(2, "Airtel (SIM 2)"))
        showOnboarding()
        goToStep("Which SIM?")
        tap("Airtel (SIM 2)")
        waitFor { current().simSubscriptionId == 2 }
    }

    @Test
    fun aRestoreCanStartFromTheFirstScreen() {
        showOnboarding()
        tap("Restore from a backup")
        waitForText("Pick a backup file")
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Welcome")
    }

    @Test
    fun finishingWithImportOpensIt() {
        var asked = false
        showOnboarding(onImport = { asked = true })
        goToStep("You're set")
        tap("Import members")
        waitFor { asked && current().onboardingDone }
    }

    // ---- The zero opening balance ----

    @Test
    fun aZeroOpeningBalanceIsLeftOutOfTheHistory() {
        val row = com.swastik.mbfitness.domain.ImportRow(2, "Rahul Sharma", "+919876543210", 1, today + 30, 0, null, "", null)
        runBlocking { app.members.importMembers(listOf(row), smsConsent = true) }
        showApp()
        tap("Paid")
        tap("Rahul Sharma")
        waitForText("Record payment")
        waitForText("PAYMENTS")
        assertTrue(gone("Opening balance"))
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
