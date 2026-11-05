package com.swastik.mbfitness.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.swastik.mbfitness.domain.MemberRules
import com.swastik.mbfitness.domain.PackLanguage
import com.swastik.mbfitness.domain.Pacing
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
    val biometric: Boolean = false,
    val recoveryCodeHash: String? = null,
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
        return AppSettings(
            gymName = this[GYM_NAME] ?: defaults.gymName,
            ownerName = this[OWNER_NAME] ?: defaults.ownerName,
            greeting = this[GREETING] ?: defaults.greeting,
            trainerPhone = this[TRAINER_PHONE] ?: defaults.trainerPhone,
            upiId = this[UPI_ID] ?: defaults.upiId,
            upiPayeeName = this[UPI_PAYEE] ?: defaults.upiPayeeName,
            logoFile = this[LOGO_FILE],
            reminderHour = this[REMINDER_HOUR] ?: defaults.reminderHour,
            reminderMinute = this[REMINDER_MINUTE] ?: defaults.reminderMinute,
            dailyCapParts = this[DAILY_CAP] ?: defaults.dailyCapParts,
            windowCapParts = this[WINDOW_CAP] ?: defaults.windowCapParts,
            simSubscriptionId = this[SIM_ID],
            renewalRule = this[RENEWAL_RULE]?.let(RenewalRule::valueOf) ?: defaults.renewalRule,
            restartAfterDays = this[RESTART_DAYS] ?: defaults.restartAfterDays,
            quietWeekdays = this[QUIET_WEEKDAYS]?.map(DayOfWeek::valueOf)?.toSet() ?: defaults.quietWeekdays,
            morningSummary = this[MORNING_SUMMARY] ?: defaults.morningSummary,
            allClearNotice = this[ALL_CLEAR] ?: defaults.allClearNotice,
            testMode = this[TEST_MODE] ?: defaults.testMode,
            packLanguage = this[PACK_LANGUAGE]?.let(PackLanguage::valueOf) ?: defaults.packLanguage,
            pinHash = this[PIN_HASH],
            pinSalt = this[PIN_SALT],
            biometric = this[BIOMETRIC] ?: defaults.biometric,
            recoveryCodeHash = this[RECOVERY_HASH],
            backupFolderUri = this[BACKUP_FOLDER],
            backupPasswordSet = this[BACKUP_PASSWORD_SET] ?: defaults.backupPasswordSet,
            backupPasswordHint = this[BACKUP_HINT],
            backupKeyWrapped = this[BACKUP_KEY],
            backupSalt = this[BACKUP_SALT],
            lastBackupDay = this[LAST_BACKUP_DAY],
            lastDailyRunDay = this[LAST_RUN_DAY],
            lastDailyRunAt = this[LAST_RUN_AT],
            onboardingDone = this[ONBOARDING_DONE] ?: defaults.onboardingDone,
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
        this[BIOMETRIC] = settings.biometric
        put(RECOVERY_HASH, settings.recoveryCodeHash)
        put(BACKUP_FOLDER, settings.backupFolderUri)
        this[BACKUP_PASSWORD_SET] = settings.backupPasswordSet
        put(BACKUP_HINT, settings.backupPasswordHint)
        put(BACKUP_KEY, settings.backupKeyWrapped)
        put(BACKUP_SALT, settings.backupSalt)
        put(LAST_BACKUP_DAY, settings.lastBackupDay)
        put(LAST_RUN_DAY, settings.lastDailyRunDay)
        put(LAST_RUN_AT, settings.lastDailyRunAt)
        this[ONBOARDING_DONE] = settings.onboardingDone
    }

    private fun <T> MutablePreferences.put(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private companion object {
        val GYM_NAME = stringPreferencesKey("gymName")
        val OWNER_NAME = stringPreferencesKey("ownerName")
        val GREETING = stringPreferencesKey("greeting")
        val TRAINER_PHONE = stringPreferencesKey("trainerPhone")
        val UPI_ID = stringPreferencesKey("upiId")
        val UPI_PAYEE = stringPreferencesKey("upiPayeeName")
        val LOGO_FILE = stringPreferencesKey("logoFile")
        val REMINDER_HOUR = intPreferencesKey("reminderHour")
        val REMINDER_MINUTE = intPreferencesKey("reminderMinute")
        val DAILY_CAP = intPreferencesKey("dailyCapParts")
        val WINDOW_CAP = intPreferencesKey("windowCapParts")
        val SIM_ID = intPreferencesKey("simSubscriptionId")
        val RENEWAL_RULE = stringPreferencesKey("renewalRule")
        val RESTART_DAYS = intPreferencesKey("restartAfterDays")
        val QUIET_WEEKDAYS = stringSetPreferencesKey("quietWeekdays")
        val MORNING_SUMMARY = booleanPreferencesKey("morningSummary")
        val ALL_CLEAR = booleanPreferencesKey("allClearNotice")
        val TEST_MODE = booleanPreferencesKey("testMode")
        val PACK_LANGUAGE = stringPreferencesKey("packLanguage")
        val PIN_HASH = stringPreferencesKey("pinHash")
        val PIN_SALT = stringPreferencesKey("pinSalt")
        val BIOMETRIC = booleanPreferencesKey("biometric")
        val RECOVERY_HASH = stringPreferencesKey("recoveryCodeHash")
        val BACKUP_FOLDER = stringPreferencesKey("backupFolderUri")
        val BACKUP_PASSWORD_SET = booleanPreferencesKey("backupPasswordSet")
        val BACKUP_HINT = stringPreferencesKey("backupPasswordHint")
        val BACKUP_KEY = stringPreferencesKey("backupKeyWrapped")
        val BACKUP_SALT = stringPreferencesKey("backupSalt")
        val LAST_BACKUP_DAY = longPreferencesKey("lastBackupDay")
        val LAST_RUN_DAY = longPreferencesKey("lastDailyRunDay")
        val LAST_RUN_AT = longPreferencesKey("lastDailyRunAt")
        val ONBOARDING_DONE = booleanPreferencesKey("onboardingDone")
    }
}
