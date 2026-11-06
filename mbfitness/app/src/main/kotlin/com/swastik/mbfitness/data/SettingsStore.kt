package com.swastik.mbfitness.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.swastik.mbfitness.domain.MemberRules
import com.swastik.mbfitness.domain.PackLanguage
import com.swastik.mbfitness.domain.Pacing
import com.swastik.mbfitness.domain.ReminderSchedule
import com.swastik.mbfitness.domain.RenewalRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek

/** Everything changeable, from SPEC 6. Defaults here are what a fresh install starts with. */
data class AppSettings(
    val gymName: String = "MB Fitness Club",
    val ownerName: String = "Jeetu Bhaiya",
    val greeting: String = "JAI SHREE RAM",
    val trainerPhone: String = "",
    val upiId: String = "",
    val upiPayeeName: String = "",
    val logoFile: String? = null,
    val reminderHour: Int = 9,
    val reminderMinute: Int = 0,
    val dailyCapParts: Int = Pacing.Limits().dailyCapParts,
    val windowCapParts: Int = Pacing.Limits().windowCapParts,
    val simSubscriptionId: Int? = null,
    val renewalRule: RenewalRule = RenewalRule.CONTINUE_FROM_EXPIRY,
    val restartAfterDays: Int = MemberRules.DEFAULT_RESTART_AFTER_DAYS,
    val quietWeekdays: Set<DayOfWeek> = setOf(DayOfWeek.SUNDAY),
    val morningSummary: Boolean = true,
    val allClearNotice: Boolean = true,
    /** What the owner chose. Read [SettingsStore.isTestMode], which debug builds force on. */
    val testMode: Boolean = false,
    val packLanguage: PackLanguage = PackLanguage.ENGLISH,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    /** So the pad can check as soon as enough digits are in. */
    val pinLength: Int? = null,
    val pinFailures: Int = 0,
    val pinLockedUntil: Long? = null,
    val biometric: Boolean = false,
    val recoveryCodeHash: String? = null,
    val recoverySalt: String? = null,
    val backupFolderUri: String? = null,
    val backupPasswordSet: Boolean = false,
    val backupPasswordHint: String? = null,
    /** The derived backup key, wrapped by the Keystore, so weekly backups can run unattended. */
    val backupKeyWrapped: String? = null,
    val backupSalt: String? = null,
    val lastBackupDay: Long? = null,
    val lastDailyRunDay: Long? = null,
    val lastDailyRunAt: Long? = null,
    val onboardingDone: Boolean = false,
    /** When setup finished, so "reminders have stopped" can show even if they never ran once. */
    val setupDoneAt: Long? = null,
    /** The day a backup was last sent off the phone with "Send a copy". */
    val lastCopySentDay: Long? = null,
    /** The settings file was found damaged and started again, so setup asks for them again. */
    val settingsReset: Boolean = false,
)

/**
 * The settings file (DataStore). [forceTestMode] is true in debug builds, so a debug build can
 * only ever text the trainer's own number (hard rule 9).
 */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val forceTestMode: Boolean,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    fun isTestMode(settings: AppSettings): Boolean = forceTestMode || settings.testMode

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            preferences.write(transform(preferences.toSettings()))
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        val hour = this[REMINDER_HOUR] ?: defaults.reminderHour
        val minute = this[REMINDER_MINUTE] ?: defaults.reminderMinute
        // A time reminders can't go at would make every alarm or send fail, so it can't get through.
        val timeAllowed = ReminderSchedule.isAllowedTime(hour, minute)
        return AppSettings(
            gymName = this[GYM_NAME] ?: defaults.gymName,
            ownerName = this[OWNER_NAME] ?: defaults.ownerName,
            greeting = this[GREETING] ?: defaults.greeting,
            trainerPhone = this[TRAINER_PHONE] ?: defaults.trainerPhone,
            upiId = this[UPI_ID] ?: defaults.upiId,
            upiPayeeName = this[UPI_PAYEE] ?: defaults.upiPayeeName,
            logoFile = this[LOGO_FILE],
            reminderHour = if (timeAllowed) hour else defaults.reminderHour,
            reminderMinute = if (timeAllowed) minute else defaults.reminderMinute,
            dailyCapParts = this[DAILY_CAP]?.takeIf { it > 0 } ?: defaults.dailyCapParts,
            windowCapParts = this[WINDOW_CAP]?.takeIf { it > 0 } ?: defaults.windowCapParts,
            simSubscriptionId = this[SIM_ID],
            renewalRule = this[RENEWAL_RULE].named(RenewalRule.entries) ?: defaults.renewalRule,
            restartAfterDays = this[RESTART_DAYS]?.coerceAtLeast(0) ?: defaults.restartAfterDays,
            quietWeekdays = this[QUIET_WEEKDAYS]?.mapNotNull { it.named(DayOfWeek.entries) }?.toSet() ?: defaults.quietWeekdays,
            morningSummary = this[MORNING_SUMMARY] ?: defaults.morningSummary,
            allClearNotice = this[ALL_CLEAR] ?: defaults.allClearNotice,
            testMode = this[TEST_MODE] ?: defaults.testMode,
            packLanguage = this[PACK_LANGUAGE].named(PackLanguage.entries) ?: defaults.packLanguage,
            pinHash = this[PIN_HASH],
            pinSalt = this[PIN_SALT],
            pinLength = this[PIN_LENGTH],
            pinFailures = this[PIN_FAILURES] ?: defaults.pinFailures,
            pinLockedUntil = this[PIN_LOCKED_UNTIL],
            biometric = this[BIOMETRIC] ?: defaults.biometric,
            recoveryCodeHash = this[RECOVERY_HASH],
            recoverySalt = this[RECOVERY_SALT],
            backupFolderUri = this[BACKUP_FOLDER],
            backupPasswordSet = this[BACKUP_PASSWORD_SET] ?: defaults.backupPasswordSet,
            backupPasswordHint = this[BACKUP_HINT],
            backupKeyWrapped = this[BACKUP_KEY],
            backupSalt = this[BACKUP_SALT],
            lastBackupDay = this[LAST_BACKUP_DAY],
            lastDailyRunDay = this[LAST_RUN_DAY],
            lastDailyRunAt = this[LAST_RUN_AT],
            onboardingDone = this[ONBOARDING_DONE] ?: defaults.onboardingDone,
            setupDoneAt = this[SETUP_DONE_AT],
            lastCopySentDay = this[LAST_COPY_SENT_DAY],
            settingsReset = this[SETTINGS_RESET] ?: defaults.settingsReset,
        )
    }

    private fun MutablePreferences.write(settings: AppSettings) {
        this[GYM_NAME] = settings.gymName
        this[OWNER_NAME] = settings.ownerName
        this[GREETING] = settings.greeting
        this[TRAINER_PHONE] = settings.trainerPhone
        this[UPI_ID] = settings.upiId
        this[UPI_PAYEE] = settings.upiPayeeName
        put(LOGO_FILE, settings.logoFile)
        this[REMINDER_HOUR] = settings.reminderHour
        this[REMINDER_MINUTE] = settings.reminderMinute
        this[DAILY_CAP] = settings.dailyCapParts
        this[WINDOW_CAP] = settings.windowCapParts
        put(SIM_ID, settings.simSubscriptionId)
        this[RENEWAL_RULE] = settings.renewalRule.name
        this[RESTART_DAYS] = settings.restartAfterDays
        this[QUIET_WEEKDAYS] = settings.quietWeekdays.map { it.name }.toSet()
        this[MORNING_SUMMARY] = settings.morningSummary
        this[ALL_CLEAR] = settings.allClearNotice
        this[TEST_MODE] = settings.testMode
        this[PACK_LANGUAGE] = settings.packLanguage.name
        put(PIN_HASH, settings.pinHash)
        put(PIN_SALT, settings.pinSalt)
        put(PIN_LENGTH, settings.pinLength)
        this[PIN_FAILURES] = settings.pinFailures
        put(PIN_LOCKED_UNTIL, settings.pinLockedUntil)
        this[BIOMETRIC] = settings.biometric
        put(RECOVERY_HASH, settings.recoveryCodeHash)
        put(RECOVERY_SALT, settings.recoverySalt)
        put(BACKUP_FOLDER, settings.backupFolderUri)
        this[BACKUP_PASSWORD_SET] = settings.backupPasswordSet
        put(BACKUP_HINT, settings.backupPasswordHint)
        put(BACKUP_KEY, settings.backupKeyWrapped)
        put(BACKUP_SALT, settings.backupSalt)
        put(LAST_BACKUP_DAY, settings.lastBackupDay)
        put(LAST_RUN_DAY, settings.lastDailyRunDay)
        put(LAST_RUN_AT, settings.lastDailyRunAt)
        this[ONBOARDING_DONE] = settings.onboardingDone
        put(SETUP_DONE_AT, settings.setupDoneAt)
        put(LAST_COPY_SENT_DAY, settings.lastCopySentDay)
        this[SETTINGS_RESET] = settings.settingsReset
    }

    /** A value written by a newer version, or damaged, falls back to the default instead of crashing. */
    private fun <E : Enum<E>> String?.named(entries: List<E>): E? = this?.let { name -> entries.firstOrNull { it.name == name } }

    private fun <T> MutablePreferences.put(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    companion object {
        /**
         * What a damaged settings file is replaced with: the defaults, and a note saying so. A
         * damaged file would otherwise stop the app opening at all. Members and payments are in
         * the database, so they're untouched.
         */
        fun afterDamage(): Preferences = mutablePreferencesOf(SETTINGS_RESET to true)

        private val GYM_NAME = stringPreferencesKey("gymName")
        private val OWNER_NAME = stringPreferencesKey("ownerName")
        private val GREETING = stringPreferencesKey("greeting")
        private val TRAINER_PHONE = stringPreferencesKey("trainerPhone")
        private val UPI_ID = stringPreferencesKey("upiId")
        private val UPI_PAYEE = stringPreferencesKey("upiPayeeName")
        private val LOGO_FILE = stringPreferencesKey("logoFile")
        private val REMINDER_HOUR = intPreferencesKey("reminderHour")
        private val REMINDER_MINUTE = intPreferencesKey("reminderMinute")
        private val DAILY_CAP = intPreferencesKey("dailyCapParts")
        private val WINDOW_CAP = intPreferencesKey("windowCapParts")
        private val SIM_ID = intPreferencesKey("simSubscriptionId")
        private val RENEWAL_RULE = stringPreferencesKey("renewalRule")
        private val RESTART_DAYS = intPreferencesKey("restartAfterDays")
        private val QUIET_WEEKDAYS = stringSetPreferencesKey("quietWeekdays")
        private val MORNING_SUMMARY = booleanPreferencesKey("morningSummary")
        private val ALL_CLEAR = booleanPreferencesKey("allClearNotice")
        private val TEST_MODE = booleanPreferencesKey("testMode")
        private val PACK_LANGUAGE = stringPreferencesKey("packLanguage")
        private val PIN_HASH = stringPreferencesKey("pinHash")
        private val PIN_SALT = stringPreferencesKey("pinSalt")
        private val PIN_LENGTH = intPreferencesKey("pinLength")
        private val PIN_FAILURES = intPreferencesKey("pinFailures")
        private val PIN_LOCKED_UNTIL = longPreferencesKey("pinLockedUntil")
        private val BIOMETRIC = booleanPreferencesKey("biometric")
        private val RECOVERY_HASH = stringPreferencesKey("recoveryCodeHash")
        private val RECOVERY_SALT = stringPreferencesKey("recoverySalt")
        private val BACKUP_FOLDER = stringPreferencesKey("backupFolderUri")
        private val BACKUP_PASSWORD_SET = booleanPreferencesKey("backupPasswordSet")
        private val BACKUP_HINT = stringPreferencesKey("backupPasswordHint")
        private val BACKUP_KEY = stringPreferencesKey("backupKeyWrapped")
        private val BACKUP_SALT = stringPreferencesKey("backupSalt")
        private val LAST_BACKUP_DAY = longPreferencesKey("lastBackupDay")
        private val LAST_RUN_DAY = longPreferencesKey("lastDailyRunDay")
        private val LAST_RUN_AT = longPreferencesKey("lastDailyRunAt")
        private val ONBOARDING_DONE = booleanPreferencesKey("onboardingDone")
        private val SETUP_DONE_AT = longPreferencesKey("setupDoneAt")
        private val LAST_COPY_SENT_DAY = longPreferencesKey("lastCopySentDay")
        private val SETTINGS_RESET = booleanPreferencesKey("settingsReset")
    }
}
