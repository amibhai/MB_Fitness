package com.swastik.mbfitness.domain

enum class HealthState { OK, WARNING, PROBLEM }

/** What the one-tap fix does. The screen turns these into Android calls. */
enum class HealthFix {
    RUN_REMINDERS,
    SMS_PERMISSION,
    EXACT_ALARMS,
    BATTERY,
    NOTIFICATIONS,
    HIBERNATION,
    PICK_SIM,
    OPEN_MESSAGES,
    BACKUP,
    TEST_MODE,
    SET_PIN,
    ERROR_LOG,
    GYM_DETAILS,
    DATE_AND_TIME,
}

data class HealthRow(
    val title: String,
    val state: HealthState,
    val detail: String,
    val fixLabel: String? = null,
    val fix: HealthFix? = null,
)

/** Everything the Health check looks at (SPEC 13). A null means it doesn't apply on this phone. */
data class HealthFacts(
    val nowMillis: Long,
    val lastRunAt: Long?,
    val nextRunAt: Long,
    val smsAllowed: Boolean,
    val installedOutsideStore: Boolean,
    val exactAlarmsAllowed: Boolean?,
    val batteryExempt: Boolean,
    val oemGuide: BatteryCard?,
    val notificationsAllowed: Boolean,
    val hibernationExempt: Boolean?,
    val hasSim: Boolean,
    val chosenSimMissing: Boolean,
    val sentTodayParts: Int,
    val dailyCapParts: Int,
    val queued: Int,
    val failed: Int,
    val today: Long,
    val lastBackupDay: Long?,
    val folderChosen: Boolean,
    val folderWritable: Boolean,
    val databaseOk: Boolean,
    val databaseBytes: Long,
    val lastExitReason: String?,
    val testMode: Boolean,
    val testModeForced: Boolean,
    val aadhaarSaved: Int,
    val pinSet: Boolean,
    val backupPasswordSet: Boolean,
    val errorCount: Int,
    /** When setup finished, so reminders that never ran once still show as late. */
    val setupDoneAt: Long? = null,
    /** Two SIMs, none chosen, and the phone asks which to use every time. */
    val phoneAsksWhichSim: Boolean = false,
    /** The day a backup was last sent off the phone. */
    val lastCopySentDay: Long? = null,
    /** Reminders that quote the owner's number wait until it's set. */
    val ownerNumberSet: Boolean = true,
)

/** Turns the facts into rows, each with a state and, where it helps, a fix (SPEC 13). */
object HealthCheck {
    private const val BYTES_PER_MB = 1024.0 * 1024

    fun rows(facts: HealthFacts): List<HealthRow> = listOfNotNull(
        clock(facts),
        reminders(facts),
        sms(facts),
        facts.exactAlarmsAllowed?.let(::exactAlarms),
        battery(facts),
        notifications(facts),
        facts.hibernationExempt?.let(::hibernation),
        sim(facts),
        ownerNumber(facts),
        usage(facts),
        backup(facts),
        offPhoneCopy(facts),
        database(facts),
        facts.lastExitReason?.let { HealthRow("Last time the app stopped", HealthState.OK, it) },
        testMode(facts),
        aadhaar(facts),
        errors(facts),
    )

    fun worst(rows: List<HealthRow>): HealthState = rows.maxOfOrNull { it.state } ?: HealthState.OK

    /** Only shown when it's wrong: with a right date, there's nothing to say. */
    private fun clock(facts: HealthFacts): HealthRow? =
        if (ClockCheck.looksWrong(facts.today)) {
            HealthRow(
                "Phone's date",
                HealthState.PROBLEM,
                "It says ${Dates.formatFull(facts.today)}, which can't be right. Reminders wait until it's fixed.",
                "Fix",
                HealthFix.DATE_AND_TIME,
            )
        } else {
            null
        }

    private fun ownerNumber(facts: HealthFacts): HealthRow =
        if (facts.ownerNumberSet) {
            HealthRow("Your phone number", HealthState.OK, "Set")
        } else {
            HealthRow("Your phone number", HealthState.WARNING, "Not set, so reminders that quote it can't go.", "Add", HealthFix.GYM_DETAILS)
        }

    private fun reminders(facts: HealthFacts): HealthRow {
        val detail = "${ReminderSchedule.lastRanText(facts.nowMillis, facts.lastRunAt)}. Next: ${MessageLabels.whenText(facts.nextRunAt, facts.nowMillis)}."
        return when (ReminderSchedule.health(facts.nowMillis, facts.lastRunAt, facts.setupDoneAt)) {
            RunHealth.OK -> HealthRow("Reminders", HealthState.OK, detail)
            RunHealth.LATE -> HealthRow("Reminders", HealthState.WARNING, detail, "Run now", HealthFix.RUN_REMINDERS)
            RunHealth.VERY_LATE -> HealthRow("Reminders", HealthState.PROBLEM, detail, "Fix", HealthFix.RUN_REMINDERS)
        }
    }

    private fun sms(facts: HealthFacts): HealthRow = when {
        facts.smsAllowed -> HealthRow("SMS permission", HealthState.OK, "On")
        facts.installedOutsideStore -> HealthRow(
            "SMS permission",
            HealthState.PROBLEM,
            "Off. If Android won't let you turn it on: App info, the three dots, Allow restricted settings, then try again.",
            "Fix",
            HealthFix.SMS_PERMISSION,
        )
        else -> HealthRow("SMS permission", HealthState.PROBLEM, "Off. Reminders can't go.", "Fix", HealthFix.SMS_PERMISSION)
    }

    private fun exactAlarms(allowed: Boolean): HealthRow =
        if (allowed) {
            HealthRow("Exact alarms", HealthState.OK, "Allowed, so reminders go on time")
        } else {
            HealthRow("Exact alarms", HealthState.WARNING, "Off, so reminders can be up to 30 minutes late", "Allow", HealthFix.EXACT_ALARMS)
        }

    private fun battery(facts: HealthFacts): HealthRow {
        val brand = facts.oemGuide?.let { " On ${it.brand} phones, also check its own battery settings." }.orEmpty()
        return if (facts.batteryExempt) {
            HealthRow("Battery saver", HealthState.OK, "Off for this app.$brand")
        } else {
            HealthRow("Battery saver", HealthState.WARNING, "On, so Android may stop reminders running.$brand", "Turn off", HealthFix.BATTERY)
        }
    }

    private fun notifications(facts: HealthFacts): HealthRow =
        if (facts.notificationsAllowed) {
            HealthRow("Notifications", HealthState.OK, "On")
        } else {
            HealthRow("Notifications", HealthState.WARNING, "Off, so you won't get the morning summary or hear about problems", "Allow", HealthFix.NOTIFICATIONS)
        }

    private fun hibernation(exempt: Boolean): HealthRow =
        if (exempt) {
            HealthRow("Unused app pause", HealthState.OK, "Off for this app")
        } else {
            HealthRow("Unused app pause", HealthState.WARNING, "On. Android could stop reminders if the app isn't opened for a while.", "Turn off", HealthFix.HIBERNATION)
        }

    private fun sim(facts: HealthFacts): HealthRow = when {
        !facts.hasSim -> HealthRow("SIM", HealthState.PROBLEM, "No SIM found, so SMS can't go")
        facts.chosenSimMissing -> HealthRow("SIM", HealthState.WARNING, "The SIM you chose isn't in this phone. Using the default one.", "Choose", HealthFix.PICK_SIM)
        facts.phoneAsksWhichSim -> HealthRow(
            "SIM",
            HealthState.WARNING,
            "Your phone asks which SIM to use for each SMS, so reminders may fail. Choose one for the app.",
            "Choose",
            HealthFix.PICK_SIM,
        )
        else -> HealthRow("SIM", HealthState.OK, "Ready")
    }

    private fun usage(facts: HealthFacts): HealthRow {
        val detail = "${facts.sentTodayParts} of ${facts.dailyCapParts} SMS today. ${facts.queued} waiting."
        return if (facts.failed > 0) {
            val failed = if (facts.failed == 1) "1 failed." else "${facts.failed} failed."
            HealthRow("SMS today", HealthState.WARNING, "$detail $failed", "See them", HealthFix.OPEN_MESSAGES)
        } else {
            HealthRow("SMS today", HealthState.OK, detail)
        }
    }

    private fun backup(facts: HealthFacts): HealthRow {
        val last = BackupRules.lastBackupText(facts.today, facts.lastBackupDay)
        return when {
            !facts.folderChosen -> HealthRow("Backup", HealthState.WARNING, "$last. No folder chosen.", "Set up", HealthFix.BACKUP)
            !facts.folderWritable -> HealthRow("Backup", HealthState.PROBLEM, "$last. The folder can't be reached any more.", "Fix", HealthFix.BACKUP)
            BackupRules.isOverdue(facts.today, facts.lastBackupDay) -> HealthRow("Backup", HealthState.WARNING, "$last. That's overdue.", "Back up", HealthFix.BACKUP)
            else -> HealthRow("Backup", HealthState.OK, last)
        }
    }

    /** A backup in the phone's own folder goes if the phone is lost, so a copy should live elsewhere too. */
    private fun offPhoneCopy(facts: HealthFacts): HealthRow {
        val sent = facts.lastCopySentDay
        return when {
            sent == null -> HealthRow("Copy off this phone", HealthState.WARNING, "None sent yet. Send one to your WhatsApp or Drive, so a lost phone doesn't lose your data.", "Send", HealthFix.BACKUP)
            BackupRules.isCopyDue(facts.today, sent) -> HealthRow("Copy off this phone", HealthState.WARNING, "Last sent ${Dates.formatShort(sent)}. Time to send a new one.", "Send", HealthFix.BACKUP)
            else -> HealthRow("Copy off this phone", HealthState.OK, "Last sent ${Dates.formatShort(sent)}")
        }
    }

    private fun database(facts: HealthFacts): HealthRow {
        val size = "%.1f MB".format(facts.databaseBytes / BYTES_PER_MB)
        return if (facts.databaseOk) {
            HealthRow("Database", HealthState.OK, "OK. $size.")
        } else {
            HealthRow("Database", HealthState.PROBLEM, "Damaged. Restore your latest backup.", "Restore", HealthFix.BACKUP)
        }
    }

    private fun testMode(facts: HealthFacts): HealthRow = when {
        facts.testModeForced -> HealthRow("Test mode", HealthState.WARNING, "Always on in this test build")
        facts.testMode -> HealthRow("Test mode", HealthState.WARNING, "On, so members get nothing. Every SMS goes to your own number.", "Turn off", HealthFix.TEST_MODE)
        else -> HealthRow("Test mode", HealthState.OK, "Off")
    }

    /** Never shows a number or who it belongs to (hard rule 11). */
    private fun aadhaar(facts: HealthFacts): HealthRow {
        val count = if (facts.aadhaarSaved == 1) "1 saved" else "${facts.aadhaarSaved} saved"
        val backups = if (facts.backupPasswordSet) "Backups include them, protected by your password." else "Backups hold the last 4 digits only, unless a backup password is set."
        return when {
            facts.aadhaarSaved == 0 -> HealthRow("Aadhaar", HealthState.OK, "None saved")
            !facts.pinSet -> HealthRow("Aadhaar", HealthState.WARNING, "$count, with no app PIN to protect them. $backups", "Set PIN", HealthFix.SET_PIN)
            else -> HealthRow("Aadhaar", HealthState.OK, "$count, protected by your PIN. $backups")
        }
    }

    private fun errors(facts: HealthFacts): HealthRow =
        HealthRow(
            "Error log",
            HealthState.OK,
            if (facts.errorCount == 0) "Nothing logged" else if (facts.errorCount == 1) "1 entry" else "${facts.errorCount} entries",
            if (facts.errorCount > 0) "Open" else null,
            if (facts.errorCount > 0) HealthFix.ERROR_LOG else null,
        )
}
