package com.swastik.mbfitness.domain

/** One editable template row (SPEC 6). */
data class TemplateConfig(
    val kind: MessageKind,
    val body: String,
    val enabled: Boolean = true,
    val offsetDays: Int = 0,
    val repeatEveryDays: Int? = null,
    val maxRepeats: Int? = null,
)

/** The two preset packs, word for word from SPEC 8.6, plus WELCOME (docs/DECISIONS.md, 1.0.2). */
object TemplatePacks {
    private val ENGLISH = mapOf(
        MessageKind.BEFORE_DUE to "Hi {name}, your {gym_name} membership expires on {due_date}. Fee: Rs {amount}. UPI: {upi_id}. Thank you!",
        MessageKind.ON_DUE to "Hi {name}, your {gym_name} fee of Rs {amount} is due today. UPI: {upi_id}. Please ignore if already paid.",
        MessageKind.OVERDUE_1 to "Hi {name}, your {gym_name} fee of Rs {amount} is {days_overdue} overdue. Kindly pay soon. UPI: {upi_id}",
        MessageKind.OVERDUE_2 to "Hi {name}, reminder: your {gym_name} fee is {days_overdue} pending. Please pay or call {trainer_phone}.",
        MessageKind.BALANCE_DUE to "Hi {name}, Rs {balance} is still pending on your {gym_name} fee. Please pay when you can. UPI: {upi_id}",
        MessageKind.PAYMENT_THANKS to "Thanks {name}! Received Rs {amount} (Receipt {receipt_no}). Valid till {due_date}.[[ Balance due: Rs {balance}.]] - {gym_name}",
        MessageKind.BIRTHDAY to "Happy Birthday {name}! Wishing you a strong and healthy year ahead. - {gym_name}",
        MessageKind.WELCOME to "Hi {name}, welcome to {gym_name}! This is the automated SMS reminder service of {gym_name}. Your fee reminders will come from this number.",
    )

    private val HINGLISH = mapOf(
        MessageKind.BEFORE_DUE to "Namaste {name} ji, aapki {gym_name} membership {due_date} ko khatam ho rahi hai. Fees: Rs {amount}. UPI: {upi_id}. Dhanyavaad!",
        MessageKind.ON_DUE to "Namaste {name} ji, aapki {gym_name} fees Rs {amount} aaj due hai. UPI: {upi_id}. Agar de di hai to ignore karein.",
        MessageKind.OVERDUE_1 to "Namaste {name} ji, aapki {gym_name} fees Rs {amount} {days_overdue} se pending hai. Kripya jaldi jama karein. UPI: {upi_id}",
        MessageKind.OVERDUE_2 to "{name} ji, aapki {gym_name} fees {days_overdue} se baaki hai. Kripya jama karein ya {trainer_phone} par call karein.",
        MessageKind.BALANCE_DUE to "{name} ji, aapki {gym_name} fees ke Rs {balance} abhi baaki hain. Jab ho sake jama kar dein. UPI: {upi_id}",
        MessageKind.PAYMENT_THANKS to "Dhanyavaad {name} ji! Rs {amount} mil gaye (Receipt {receipt_no}). Membership {due_date} tak valid.[[ Baaki: Rs {balance}.]] - {gym_name}",
        MessageKind.BIRTHDAY to "Janamdin ki hardik shubhkamnayein {name} ji! Swasth aur mazboot rahiye. - {gym_name}",
        MessageKind.WELCOME to "{name} ji, {gym_name} mein aapka swagat hai! Yeh {gym_name} ki automated SMS reminder service hai. Fees ke reminders isi number se aayenge.",
    )

    /** The default schedule (SPEC 4-C and 8.2). */
    fun defaults(language: PackLanguage): List<TemplateConfig> {
        val bodies = if (language == PackLanguage.ENGLISH) ENGLISH else HINGLISH
        return listOf(
            TemplateConfig(MessageKind.BEFORE_DUE, bodies.getValue(MessageKind.BEFORE_DUE), offsetDays = -3),
            TemplateConfig(MessageKind.ON_DUE, bodies.getValue(MessageKind.ON_DUE), offsetDays = 0),
            TemplateConfig(MessageKind.OVERDUE_1, bodies.getValue(MessageKind.OVERDUE_1), offsetDays = 3),
            TemplateConfig(MessageKind.OVERDUE_2, bodies.getValue(MessageKind.OVERDUE_2), offsetDays = 7, repeatEveryDays = 7, maxRepeats = 4),
            TemplateConfig(MessageKind.BALANCE_DUE, bodies.getValue(MessageKind.BALANCE_DUE), offsetDays = 7, repeatEveryDays = 7, maxRepeats = 3),
            TemplateConfig(MessageKind.PAYMENT_THANKS, bodies.getValue(MessageKind.PAYMENT_THANKS)),
            TemplateConfig(MessageKind.BIRTHDAY, bodies.getValue(MessageKind.BIRTHDAY)),
            TemplateConfig(MessageKind.WELCOME, bodies.getValue(MessageKind.WELCOME)),
        )
    }

    /**
     * Every templated kind: the saved ones as they are, and this pack's preset for any kind with
     * nothing saved. That covers a kind added in an update, and a backup made before it existed,
     * without a database migration. Saving it in the editor stores it like the others.
     */
    fun withMissing(saved: List<TemplateConfig>, language: PackLanguage): List<TemplateConfig> {
        val have = saved.map { it.kind }.toSet()
        return saved + defaults(language).filter { it.kind !in have }
    }

    /** One kind's preset, or null for a kind with no template. */
    fun preset(kind: MessageKind, language: PackLanguage): TemplateConfig? = defaults(language).firstOrNull { it.kind == kind }
}
