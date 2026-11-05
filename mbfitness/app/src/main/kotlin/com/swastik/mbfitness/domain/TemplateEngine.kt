package com.swastik.mbfitness.domain

/** The values a template can use. A blank value is treated as missing, never sent. */
data class TemplateValues(
    val fullName: String = "",
    val amount: Long? = null,
    val balance: Long = 0,
    val plan: String = "",
    val dueDate: String = "",
    val daysOverdue: Long = 0,
    val gymName: String = "",
    val upiId: String = "",
    val trainerPhone: String = "",
    val receiptNo: String = "",
)

/** [missing] lists placeholders with no value, so the caller can hold the SMS back. */
data class RenderResult(val text: String, val missing: Set<String>)

sealed interface TemplateProblem {
    data object Empty : TemplateProblem
    data class UnknownPlaceholder(val name: String) : TemplateProblem
    data object ContainsLink : TemplateProblem
    data class TooManyParts(val parts: Int) : TemplateProblem
}

/** Placeholders, the [[…]] balance clause and the editor's content rules (SPEC 8.6, 8.7). */
object TemplateEngine {
    val ALL_PLACEHOLDERS = setOf(
        "name", "full_name", "amount", "balance", "plan", "due_date",
        "days_overdue", "gym_name", "upi_id", "trainer_phone", "receipt_no",
    )
    val BROADCAST_PLACEHOLDERS = setOf("name", "gym_name", "trainer_phone")

    private val PLACEHOLDER = Regex("""\{([a-zA-Z_]+)\}""")
    private val CLAUSE = Regex("""\[\[(.*?)]]""", RegexOption.DOT_MATCHES_ALL)
    private val UPI_CLAUSE = Regex("""\s*UPI:\s*\{upi_id\}\.?""")
    private val LINK = Regex(
        """(?i)(https?|://|www\.|bit\.ly|tinyurl|wa\.me|t\.me/|\.(com|in|net|org|co|ly|io|me|app|link)(/|\b))""",
    )
    private val QUOTES = mapOf(
        '\u2018' to '\'', '\u2019' to '\'', '\u201A' to '\'', '\u201B' to '\'',
        '\u201C' to '"', '\u201D' to '"', '\u201E' to '"', '\u201F' to '"',
    )

    fun firstName(fullName: String): String = fullName.trim().substringBefore(' ').trim()

    /**
     * Fills in a template. A [[…]] clause holding {balance} is dropped when nothing is owed.
     * With no UPI ID set, a "UPI: {upi_id}" clause is removed rather than sent half-empty.
     * Anything else with no value is reported in [RenderResult.missing].
     */
    fun render(body: String, values: TemplateValues, language: PackLanguage): RenderResult {
        val due = MemberRules.due(values.balance)
        var text = CLAUSE.replace(body) { match ->
            val clause = match.groupValues[1]
            if ("{balance}" in clause && due == 0L) "" else clause
        }
        if (values.upiId.isBlank()) text = UPI_CLAUSE.replace(text, "")

        val missing = linkedSetOf<String>()
        val rendered = PLACEHOLDER.replace(text) { match ->
            val name = match.groupValues[1]
            val value = valueOf(name, values, due, language)
            if (value.isNullOrBlank()) {
                missing += name
                match.value
            } else {
                value
            }
        }
        return RenderResult(rendered, missing)
    }

    /** Everything that stops a template from being saved (SPEC 8.6, 8.7). */
    fun validate(
        body: String,
        allowed: Set<String> = ALL_PLACEHOLDERS,
        language: PackLanguage = PackLanguage.ENGLISH,
    ): List<TemplateProblem> {
        if (body.isBlank()) return listOf(TemplateProblem.Empty)
        val problems = mutableListOf<TemplateProblem>()
        PLACEHOLDER.findAll(body)
            .map { it.groupValues[1] }
            .distinct()
            .filter { it !in allowed }
            .forEach { problems += TemplateProblem.UnknownPlaceholder(it) }
        if (LINK.containsMatchIn(body)) problems += TemplateProblem.ContainsLink
        val parts = SmsSegments.count(render(body, sampleValues(), language).text).parts
        if (parts > SmsSegments.BLOCK_ABOVE_PARTS) problems += TemplateProblem.TooManyParts(parts)
        return problems
    }

    /** Curly quotes silently force Unicode and shrink each part to 70 (SPEC 8.7). */
    fun straightenQuotes(text: String): String = text.map { QUOTES[it] ?: it }.joinToString("")

    /** Believable values for the live preview and for counting parts. */
    fun sampleValues(): TemplateValues = TemplateValues(
        fullName = "Rahul Sharma",
        amount = 1200,
        balance = 700,
        plan = "1 month",
        dueDate = "12 Dec",
        daysOverdue = 3,
        gymName = "MB Fitness Club",
        upiId = "gymowner@okbank",
        trainerPhone = "98765 43210",
        receiptNo = "MBF-0042",
    )

    /** {days_overdue} carries its own unit: "1 day", "3 days", "1 din", "3 din" (SPEC 8.6). */
    fun daysOverdueText(days: Long, language: PackLanguage): String = when (language) {
        PackLanguage.ENGLISH -> if (days == 1L) "1 day" else "$days days"
        PackLanguage.HINGLISH -> "$days din"
    }

    private fun valueOf(name: String, values: TemplateValues, due: Long, language: PackLanguage): String? =
        when (name) {
            "name" -> firstName(values.fullName)
            "full_name" -> values.fullName.trim()
            "amount" -> values.amount?.toString()
            "balance" -> due.toString()
            "plan" -> values.plan
            "due_date" -> values.dueDate
            "days_overdue" -> daysOverdueText(values.daysOverdue, language)
            "gym_name" -> values.gymName
            "upi_id" -> values.upiId
            "trainer_phone" -> values.trainerPhone
            "receipt_no" -> values.receiptNo
            else -> null
        }

    /** "Sends: 3 days before expiry", shown on the Templates screen (SPEC 10.2 screen 13). */
    fun describeSchedule(config: TemplateConfig): String {
        val days = Math.abs(config.offsetDays)
        val distance = if (days == 1) "1 day" else "$days days"
        val first = when (config.kind) {
            MessageKind.BEFORE_DUE -> "$distance before expiry"
            MessageKind.ON_DUE -> "on the expiry day"
            MessageKind.OVERDUE_1, MessageKind.OVERDUE_2 -> "$distance after expiry"
            MessageKind.BALANCE_DUE -> "$distance after a part payment"
            MessageKind.PAYMENT_THANKS -> "right after a payment"
            MessageKind.BIRTHDAY -> "on their birthday"
            MessageKind.BROADCAST, MessageKind.MANUAL, MessageKind.TEST -> "when you send it"
        }
        val every = config.repeatEveryDays
        val max = config.maxRepeats
        val repeat = if (every != null && max != null) ", then every $every days, up to $max times" else ""
        return "Sends: $first$repeat"
    }
}
