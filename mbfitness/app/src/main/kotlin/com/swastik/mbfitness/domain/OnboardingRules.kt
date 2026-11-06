package com.swastik.mbfitness.domain

/** A phone maker's own battery settings, which can stop reminders even with Android's off. */
data class BatteryCard(val brand: String, val steps: List<String>)

/** SPEC 11.1 step 8. These settings differ by brand, and Android can't check them for us. */
object OemGuide {
    private val XIAOMI = BatteryCard(
        "Xiaomi",
        listOf(
            "Turn Autostart on for this app.",
            "Set Battery saver to No restrictions.",
            "Lock the app in the recent apps list.",
        ),
    )
    private val SAMSUNG = BatteryCard(
        "Samsung",
        listOf("Go to Battery, then Background usage limits.", "Add this app to Never sleeping apps."),
    )
    private val OPPO_FAMILY = BatteryCard(
        "Realme, Oppo and OnePlus",
        listOf("Turn on Allow background activity.", "Turn on Auto-launch."),
    )
    private val VIVO = BatteryCard(
        "Vivo",
        listOf("Go to Battery, then Background power consumption.", "Allow it for this app."),
    )

    fun cardFor(manufacturer: String): BatteryCard? = when (manufacturer.trim().lowercase()) {
        "xiaomi", "redmi", "poco" -> XIAOMI
        "samsung" -> SAMSUNG
        "realme", "oppo", "oneplus" -> OPPO_FAMILY
        "vivo", "iqoo" -> VIVO
        else -> null
    }
}

enum class OnboardingStep {
    WELCOME,
    GYM,
    SMS,
    SIM,
    PLAN_CHECK,
    NOTIFICATIONS,
    ALARMS,
    BATTERY,
    HIBERNATION,
    BACKUP_FOLDER,
    APP_LOCK,
    LOGO,
    TEST,
    DONE,
}

/** The first-run flow (SPEC 11.1). Every step can be skipped, and Health check nags later. */
object OnboardingRules {
    const val CAP_WITH_FREE_SMS = 90
    const val CAP_IF_UNSURE = 50
    private const val NO_PROMPT_MILLIS = 1_000L
    private const val TIRAMISU = 33
    private const val S = 31
    private const val R = 30

    /** Steps that can't apply to this phone are left out. */
    fun steps(activeSims: Int, sdk: Int): List<OnboardingStep> = OnboardingStep.entries.filter { step ->
        when (step) {
            OnboardingStep.SIM -> activeSims > 1
            OnboardingStep.NOTIFICATIONS -> sdk >= TIRAMISU
            OnboardingStep.ALARMS -> sdk >= S
            OnboardingStep.HIBERNATION -> sdk >= R
            else -> true
        }
    }

    /**
     * A sideloaded app's SMS permission can be refused with no dialog at all (SPEC 2, 11.1). The
     * only signs are that it came back at once, with no rationale, on an app from outside a store.
     */
    fun looksRestricted(granted: Boolean, showRationale: Boolean, elapsedMillis: Long, installedOutsideStore: Boolean): Boolean =
        !granted && !showRationale && elapsedMillis < NO_PROMPT_MILLIS && installedOutsideStore

    fun capForPlan(hasFreeSms: Boolean): Int = if (hasFreeSms) CAP_WITH_FREE_SMS else CAP_IF_UNSURE
}

/** What the Settings fields accept. */
object SettingsRules {
    private const val MIN_DAILY_CAP = 5
    private const val MAX_DAILY_CAP = 100

    fun dailyCapProblem(text: String): String? {
        val value = text.toIntOrNull()
        return if (value == null || value !in MIN_DAILY_CAP..MAX_DAILY_CAP) "Use a number from $MIN_DAILY_CAP to $MAX_DAILY_CAP" else null
    }

    fun trainerPhoneProblem(text: String): String? =
        if (text.isNotBlank() && PhoneNumbers.normalize(text) == null) "Number must be 10 digits" else null

    fun upiProblem(text: String): String? =
        if (text.isNotBlank() && !UpiLink.isValidVpa(text)) "That doesn't look like a UPI ID. It should look like name@bank." else null

    fun requiredProblem(text: String, what: String): String? = if (text.isBlank()) "$what can't be empty" else null

    /** "9:00 AM", from the stored hour and minute. */
    fun timeText(hour: Int, minute: Int): String = Dates.formatTime(Dates.millisAt(0, hour, minute))

    fun renewalRuleTitle(rule: RenewalRule): String = when (rule) {
        RenewalRule.CONTINUE_FROM_EXPIRY -> "Continue from the old expiry"
        RenewalRule.START_FROM_PAYMENT -> "Start from the payment date"
    }

    fun renewalRuleDetail(rule: RenewalRule): String = when (rule) {
        RenewalRule.CONTINUE_FROM_EXPIRY -> "The new period runs on from the old expiry date, even if they pay late."
        RenewalRule.START_FROM_PAYMENT -> "The new period starts on the day they pay."
    }
}
