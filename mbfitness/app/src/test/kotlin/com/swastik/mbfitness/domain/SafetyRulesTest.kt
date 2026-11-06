package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyRulesTest {
    private val friday = Dates.of(2027, 1, 1)
    private val sunday = Dates.of(2027, 1, 3)

    @Test
    fun backupsAreNamedByDate() {
        assertEquals("mbfitness_backup_2027-01-01.json", BackupRules.fileName(friday))
        assertTrue(BackupRules.isBackupFile("mbfitness_backup_2027-01-01.json"))
        assertFalse(BackupRules.isBackupFile("mbfitness_safety_2027-01-01.json"))
        assertFalse(BackupRules.isBackupFile("holiday.jpg"))
    }

    @Test
    fun onlyTheNewestEightAreKeptAndOtherFilesAreLeftAlone() {
        val backups = (1..10).map { BackupRules.fileName(friday + it * 7) }
        val names = backups.shuffled(kotlin.random.Random(1)) + listOf("photo.jpg", "mbfitness_safety_2027-01-01.json")
        assertEquals(setOf(backups[0], backups[1]), BackupRules.filesToDelete(names).toSet())
        assertTrue(BackupRules.filesToDelete(backups.take(8)).isEmpty())
    }

    @Test
    fun theNewestBackupIsFoundForRestore() {
        assertEquals(BackupRules.fileName(sunday), BackupRules.newest(listOf(BackupRules.fileName(friday), BackupRules.fileName(sunday), "z.txt")))
        assertNull(BackupRules.newest(listOf("z.txt")))
    }

    @Test
    fun theWeeklyBackupIsDueOnSundayOrTheNextRunAfterAMissedOne() {
        assertTrue("never backed up", BackupRules.isWeeklyDue(friday, null))
        assertTrue("Sunday", BackupRules.isWeeklyDue(sunday, sunday - 7))
        assertFalse("already done today", BackupRules.isWeeklyDue(sunday, sunday))
        assertTrue("Sunday was missed", BackupRules.isWeeklyDue(sunday + 2, sunday - 7))
        assertFalse("Sunday's was done", BackupRules.isWeeklyDue(sunday + 2, sunday))
        assertFalse("a manual one this week counts", BackupRules.isWeeklyDue(sunday + 5, sunday + 1))
    }

    @Test
    fun aBackupIsOverdueAfterAWeekAndADay() {
        assertTrue(BackupRules.isOverdue(friday, null))
        assertFalse(BackupRules.isOverdue(friday, friday - 8))
        assertTrue(BackupRules.isOverdue(friday, friday - 9))
    }

    @Test
    fun theRestorePreviewReadsLikeTheSpec() {
        assertEquals("120 members, 840 payments, last payment 3 Jan", BackupRules.describe(120, 840, sunday))
        assertEquals("1 member, 1 payment, last payment 3 Jan", BackupRules.describe(1, 1, sunday))
        assertEquals("0 members, 0 payments", BackupRules.describe(0, 0, null))
    }

    @Test
    fun theRestorePreviewSaysWhatWillBeLost() {
        assertNull(BackupRules.describeLost(0))
        assertEquals("1 Aadhaar number will need re-entering. Only its last 4 digits come back.", BackupRules.describeLost(1))
        assertEquals("3 Aadhaar numbers will need re-entering. Only their last 4 digits come back.", BackupRules.describeLost(3))
    }

    @Test
    fun backupPasswordsAreCheckedTwice() {
        assertEquals("Use at least 8 characters", BackupRules.passwordProblem("short", "short"))
        assertEquals("The two passwords don't match", BackupRules.passwordProblem("longenough", "longenoug"))
        assertNull(BackupRules.passwordProblem("longenough", "longenough"))
    }

    @Test
    fun theLastBackupIsDescribed() {
        assertEquals("No backup yet", BackupRules.lastBackupText(friday, null))
        assertEquals("Last backup: today", BackupRules.lastBackupText(friday, friday))
        assertEquals("Last backup: yesterday", BackupRules.lastBackupText(friday, friday - 1))
        assertEquals("Last backup: 3 Jan", BackupRules.lastBackupText(sunday + 5, sunday))
    }

    private fun member(name: String = "Rahul Sharma", balance: Long = 0) = CsvExport.MemberRow(
        name, "+919876543210", 1, "1 month", "active", friday, balance, friday - 30, Dates.of(1995, 8, 15), "", null, null,
    )

    @Test
    fun aMemberExportMatchesTheImportFile() {
        val csv = CsvExport.members(listOf(member(balance = 700)))
        val lines = csv.trim().split("\r\n")
        assertEquals("name,phone,plan_months,expiry_date,balance_due,dob,notes,advance,plan,status,joined,guardian_name,guardian_phone", lines[0])
        assertEquals("Rahul Sharma,9876543210,1,01-01-2027,700,15-08-1995,,0,1 month,active,02-12-2026,,", lines[1])

        val preview = MemberImport.preview(csv, emptyMap(), useAadhaar = false, today = friday)
        assertEquals("an export can go straight back in", emptyList<ImportError>(), preview.errors)
        assertEquals("Rahul Sharma", preview.rows.single().name)
        assertEquals(700L, preview.rows.single().balanceDue)
    }

    @Test
    fun anAdvanceGoesInItsOwnColumn() {
        val row = CsvExport.members(listOf(member(balance = -200))).trim().split("\r\n")[1].split(",")
        assertEquals("0", row[4])
        assertEquals("200", row[7])
    }

    @Test
    fun specialCharactersAreQuoted() {
        val csv = CsvExport.members(listOf(member(name = "Sharma, \"Rahul\"")))
        assertTrue(csv, "\"Sharma, \"\"Rahul\"\"\"" in csv)
        val back = MemberImport.preview(csv, emptyMap(), useAadhaar = false, today = friday)
        assertEquals("Sharma, \"Rahul\"", back.rows.single().name)
    }

    @Test
    fun aNameCantRunAsAFormulaInExcel() {
        val csv = CsvExport.members(listOf(member(name = "=SUM(A1)")))
        assertTrue(csv, "'=SUM(A1)" in csv)
    }

    @Test
    fun paymentsExportReadably() {
        val csv = CsvExport.payments(
            listOf(CsvExport.PaymentRow("MBF-0042", "Rahul Sharma", "+919876543210", "renewal", friday, 1200, 500, "upi", "Part")),
        )
        assertEquals("MBF-0042,Rahul Sharma,9876543210,renewal,01-01-2027,1200,500,upi,Part", csv.trim().split("\r\n")[1])
    }

    @Test
    fun csvFilesAreNamedByDate() {
        assertEquals("mbfitness_members_2027-01-01.csv", CsvExport.fileName("members", friday))
    }

    private fun facts() = HealthFacts(
        nowMillis = Dates.millisAt(friday, 10),
        lastRunAt = Dates.millisAt(friday, 9),
        nextRunAt = Dates.millisAt(friday + 1, 9),
        smsAllowed = true,
        installedOutsideStore = true,
        exactAlarmsAllowed = true,
        batteryExempt = true,
        oemGuide = null,
        notificationsAllowed = true,
        hibernationExempt = true,
        hasSim = true,
        chosenSimMissing = false,
        sentTodayParts = 12,
        dailyCapParts = 90,
        queued = 3,
        failed = 0,
        today = friday,
        lastBackupDay = friday - 2,
        folderChosen = true,
        folderWritable = true,
        databaseOk = true,
        databaseBytes = 2_097_152,
        lastExitReason = null,
        testMode = false,
        testModeForced = false,
        aadhaarSaved = 0,
        pinSet = true,
        backupPasswordSet = false,
        errorCount = 0,
    )

    private fun HealthFacts.row(title: String) = HealthCheck.rows(this).single { it.title == title }

    @Test
    fun aHealthyPhoneIsAllGreen() {
        assertEquals(HealthState.OK, HealthCheck.worst(HealthCheck.rows(facts())))
        assertEquals("Reminders last ran: today 9:00 AM. Next: tomorrow 9:00 AM.", facts().row("Reminders").detail)
        assertEquals("12 of 90 SMS today. 3 waiting.", facts().row("SMS today").detail)
        assertEquals("OK. 2.0 MB.", facts().row("Database").detail)
    }

    @Test
    fun remindersGoAmberThenRedAndCanBeFixed() {
        val hour = 60L * 60 * 1000
        val late = facts().copy(lastRunAt = facts().nowMillis - 30 * hour).row("Reminders")
        assertEquals(HealthState.WARNING, late.state)
        assertEquals(HealthFix.RUN_REMINDERS, late.fix)
        assertEquals(HealthState.PROBLEM, facts().copy(lastRunAt = facts().nowMillis - 60 * hour).row("Reminders").state)
    }

    @Test
    fun aRefusedSmsPermissionPointsAtRestrictedSettings() {
        val row = facts().copy(smsAllowed = false).row("SMS permission")
        assertEquals(HealthState.PROBLEM, row.state)
        assertEquals(HealthFix.SMS_PERMISSION, row.fix)
        assertTrue(row.detail, "Allow restricted settings" in row.detail)
        assertFalse(facts().copy(smsAllowed = false, installedOutsideStore = false).row("SMS permission").detail.contains("restricted"))
    }

    @Test
    fun rowsThatDontApplyAreLeftOut() {
        val rows = HealthCheck.rows(facts().copy(exactAlarmsAllowed = null, hibernationExempt = null)).map { it.title }
        assertFalse("Exact alarms" in rows)
        assertFalse("Unused app pause" in rows)
    }

    @Test
    fun eachPhoneSettingHasItsFix() {
        assertEquals(HealthFix.EXACT_ALARMS, facts().copy(exactAlarmsAllowed = false).row("Exact alarms").fix)
        assertEquals(HealthFix.BATTERY, facts().copy(batteryExempt = false).row("Battery saver").fix)
        assertEquals(HealthFix.NOTIFICATIONS, facts().copy(notificationsAllowed = false).row("Notifications").fix)
        assertEquals(HealthFix.HIBERNATION, facts().copy(hibernationExempt = false).row("Unused app pause").fix)
    }

    @Test
    fun aPhoneMakersBatterySettingsAreMentioned() {
        val row = facts().copy(oemGuide = OemGuide.cardFor("Xiaomi")).row("Battery saver")
        assertTrue(row.detail, "Xiaomi" in row.detail)
    }

    @Test
    fun theSimRowReportsWhatsWrong() {
        assertEquals(HealthState.OK, facts().row("SIM").state)
        assertEquals(HealthState.PROBLEM, facts().copy(hasSim = false).row("SIM").state)
        assertEquals(HealthFix.PICK_SIM, facts().copy(chosenSimMissing = true).row("SIM").fix)
    }

    @Test
    fun failedMessagesAreOffered() {
        val row = facts().copy(failed = 2).row("SMS today")
        assertEquals(HealthState.WARNING, row.state)
        assertEquals(HealthFix.OPEN_MESSAGES, row.fix)
        assertTrue(row.detail, "2 failed." in row.detail)
    }

    @Test
    fun theBackupRowCoversEveryWayItCanFail() {
        assertEquals(HealthState.OK, facts().row("Backup").state)
        assertEquals(HealthState.WARNING, facts().copy(folderChosen = false).row("Backup").state)
        assertEquals(HealthState.PROBLEM, facts().copy(folderWritable = false).row("Backup").state)
        assertEquals(HealthState.WARNING, facts().copy(lastBackupDay = friday - 20).row("Backup").state)
        assertEquals(HealthFix.BACKUP, facts().copy(folderWritable = false).row("Backup").fix)
    }

    @Test
    fun aDamagedDatabaseSaysRestore() {
        val row = facts().copy(databaseOk = false).row("Database")
        assertEquals(HealthState.PROBLEM, row.state)
        assertEquals(HealthFix.BACKUP, row.fix)
    }

    @Test
    fun theLastExitReasonOnlyShowsWhenThereIsOne() {
        assertFalse("Last time the app stopped" in HealthCheck.rows(facts()).map { it.title })
        assertEquals("Out of memory", facts().copy(lastExitReason = "Out of memory").row("Last time the app stopped").detail)
    }

    @Test
    fun testModeIsAWarningAndForcedInADebugBuildHasNoFix() {
        assertEquals(HealthState.OK, facts().row("Test mode").state)
        assertEquals(HealthFix.TEST_MODE, facts().copy(testMode = true).row("Test mode").fix)
        assertNull(facts().copy(testModeForced = true).row("Test mode").fix)
    }

    @Test
    fun theAadhaarRowNeverShowsANumberOnlyACount() {
        assertEquals("None saved", facts().row("Aadhaar").detail)
        val unprotected = facts().copy(aadhaarSaved = 3, pinSet = false).row("Aadhaar")
        assertEquals(HealthState.WARNING, unprotected.state)
        assertEquals(HealthFix.SET_PIN, unprotected.fix)
        assertTrue(unprotected.detail, "3 saved" in unprotected.detail)
        val full = facts().copy(aadhaarSaved = 1, backupPasswordSet = true).row("Aadhaar")
        assertEquals(HealthState.OK, full.state)
        assertTrue(full.detail, "Backups include them" in full.detail)
        assertTrue("last 4" in facts().copy(aadhaarSaved = 1).row("Aadhaar").detail)
    }

    @Test
    fun theErrorLogIsOfferedOnlyWhenThereIsSomething() {
        assertNull(facts().row("Error log").fix)
        assertEquals("Nothing logged", facts().row("Error log").detail)
        assertEquals(HealthFix.ERROR_LOG, facts().copy(errorCount = 2).row("Error log").fix)
        assertEquals("2 entries", facts().copy(errorCount = 2).row("Error log").detail)
        assertEquals("1 entry", facts().copy(errorCount = 1).row("Error log").detail)
    }

    @Test
    fun theWorstRowDecidesTheWhole() {
        assertEquals(HealthState.WARNING, HealthCheck.worst(HealthCheck.rows(facts().copy(batteryExempt = false))))
        assertEquals(HealthState.PROBLEM, HealthCheck.worst(HealthCheck.rows(facts().copy(batteryExempt = false, smsAllowed = false))))
    }

    @Test
    fun brandsGetTheirOwnBatteryCard() {
        listOf("Xiaomi", "Redmi", "POCO").forEach { assertEquals(it, "Xiaomi", OemGuide.cardFor(it)?.brand) }
        assertEquals("Samsung", OemGuide.cardFor("samsung")?.brand)
        assertEquals("Realme, Oppo and OnePlus", OemGuide.cardFor("OnePlus")?.brand)
        assertEquals("Vivo", OemGuide.cardFor("vivo")?.brand)
        assertNull(OemGuide.cardFor("Google"))
    }

    @Test
    fun stepsThatCantApplyAreLeftOutOfOnboarding() {
        val newest = OnboardingRules.steps(activeSims = 2, sdk = 37)
        assertEquals(OnboardingStep.entries.toList(), newest)
        val oneSim = OnboardingRules.steps(activeSims = 1, sdk = 37)
        assertFalse(OnboardingStep.SIM in oneSim)
        val old = OnboardingRules.steps(activeSims = 1, sdk = 26)
        assertFalse(OnboardingStep.NOTIFICATIONS in old)
        assertFalse(OnboardingStep.ALARMS in old)
        assertFalse(OnboardingStep.HIBERNATION in old)
        assertTrue(OnboardingStep.SMS in old)
    }

    @Test
    fun aRestrictedPermissionIsSpottedByItsInstantRefusal() {
        assertTrue(OnboardingRules.looksRestricted(granted = false, showRationale = false, elapsedMillis = 100, installedOutsideStore = true))
        assertFalse("granted", OnboardingRules.looksRestricted(granted = true, showRationale = false, elapsedMillis = 100, installedOutsideStore = true))
        assertFalse("someone said no", OnboardingRules.looksRestricted(granted = false, showRationale = true, elapsedMillis = 100, installedOutsideStore = true))
        assertFalse("a person took their time", OnboardingRules.looksRestricted(granted = false, showRationale = false, elapsedMillis = 4000, installedOutsideStore = true))
        assertFalse("from a store", OnboardingRules.looksRestricted(granted = false, showRationale = false, elapsedMillis = 100, installedOutsideStore = false))
    }

    @Test
    fun thePlanCheckSetsTheDailyLimit() {
        assertEquals(90, OnboardingRules.capForPlan(hasFreeSms = true))
        assertEquals(50, OnboardingRules.capForPlan(hasFreeSms = false))
    }

    @Test
    fun settingsFieldsAreChecked() {
        assertNull(SettingsRules.dailyCapProblem("90"))
        assertNotNull(SettingsRules.dailyCapProblem("4"))
        assertNotNull(SettingsRules.dailyCapProblem("101"))
        assertNotNull(SettingsRules.dailyCapProblem("many"))
        assertNull(SettingsRules.trainerPhoneProblem("9876543210"))
        assertNull(SettingsRules.trainerPhoneProblem(""))
        assertEquals("Number must be 10 digits", SettingsRules.trainerPhoneProblem("12345"))
        assertNull(SettingsRules.upiProblem("gym@okbank"))
        assertNull(SettingsRules.upiProblem(""))
        assertNotNull(SettingsRules.upiProblem("not a upi"))
        assertEquals("Gym name can't be empty", SettingsRules.requiredProblem(" ", "Gym name"))
        assertNull(SettingsRules.requiredProblem("MB", "Gym name"))
    }

    @Test
    fun reminderTimeAndRenewalRulesReadPlainly() {
        assertEquals("9:00 AM", SettingsRules.timeText(9, 0))
        assertEquals("7:30 PM", SettingsRules.timeText(19, 30))
        RenewalRule.entries.forEach {
            assertTrue(SettingsRules.renewalRuleTitle(it).isNotBlank())
            assertTrue(SettingsRules.renewalRuleDetail(it).isNotBlank())
        }
    }
}
