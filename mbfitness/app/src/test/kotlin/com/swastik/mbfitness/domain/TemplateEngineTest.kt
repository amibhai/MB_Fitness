package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateEngineTest {
    private val english = PackLanguage.ENGLISH
    private val hinglish = PackLanguage.HINGLISH
    private val values = TemplateEngine.sampleValues()

    private fun render(body: String, v: TemplateValues = values, language: PackLanguage = english) =
        TemplateEngine.render(body, v, language).text

    @Test
    fun fillsInEveryPlaceholder() {
        val body = "{name}|{full_name}|{amount}|{balance}|{plan}|{due_date}|{days_overdue}|{gym_name}|{upi_id}|{trainer_phone}|{receipt_no}"
        assertEquals(
            "Rahul|Rahul Sharma|1200|700|1 month|12 Dec|3 days|MB Fitness Club|gymowner@okbank|98765 43210|MBF-0042",
            render(body),
        )
    }

    @Test
    fun theFirstNameIsTheFirstWord() {
        assertEquals("Rahul", TemplateEngine.firstName("Rahul Kumar Sharma"))
        assertEquals("Rahul", TemplateEngine.firstName("  Rahul  "))
        assertEquals("", TemplateEngine.firstName("   "))
    }

    @Test
    fun aBalanceClauseIsDroppedWhenNothingIsOwed() {
        val body = "Paid.[[ Balance due: Rs {balance}.]] Thanks"
        assertEquals("Paid. Balance due: Rs 700. Thanks", render(body))
        assertEquals("Paid. Thanks", render(body, values.copy(balance = 0)))
        assertEquals("Paid. Thanks", render(body, values.copy(balance = -200)))
    }

    @Test
    fun aClauseWithoutBalanceIsAlwaysKept() {
        assertEquals("Hi there", render("Hi[[ there]]", values.copy(balance = 0)))
    }

    @Test
    fun anAdvanceNeverShowsAsANegativeBalance() {
        assertEquals("0", render("{balance}", values.copy(balance = -200)))
    }

    @Test
    fun daysOverdueCarriesItsUnitInBothLanguages() {
        fun days(n: Long, language: PackLanguage) = render("{days_overdue}", values.copy(daysOverdue = n), language)
        assertEquals("1 day", days(1, english))
        assertEquals("3 days", days(3, english))
        assertEquals("1 din", days(1, hinglish))
        assertEquals("3 din", days(3, hinglish))
    }

    @Test
    fun aMissingUpiIdRemovesTheClauseInsteadOfSendingItHalfEmpty() {
        val noUpi = values.copy(upiId = "")
        assertEquals("Fee: Rs 1200. Thank you!", render("Fee: Rs {amount}. UPI: {upi_id}. Thank you!", noUpi))
        assertEquals("Kindly pay soon.", render("Kindly pay soon. UPI: {upi_id}", noUpi))
        assertTrue(TemplateEngine.render("Fee. UPI: {upi_id}", noUpi, english).missing.isEmpty())
    }

    @Test
    fun anythingElseBlankIsReportedSoTheSmsCanBeHeldBack() {
        val result = TemplateEngine.render("Call {trainer_phone}. Pay {upi_id}", values.copy(trainerPhone = " ", upiId = ""), english)
        assertEquals(setOf("trainer_phone", "upi_id"), result.missing)
    }

    @Test
    fun aMissingAmountIsReported() {
        assertEquals(setOf("amount"), TemplateEngine.render("Rs {amount}", values.copy(amount = null), english).missing)
    }

    @Test
    fun anUnknownPlaceholderIsNeverSent() {
        val result = TemplateEngine.render("Hi {nickname}", values, english)
        assertEquals(setOf("nickname"), result.missing)
    }

    @Test
    fun validatorRefusesUnknownPlaceholders() {
        assertEquals(
            listOf(TemplateProblem.UnknownPlaceholder("nickname")),
            TemplateEngine.validate("Hi {nickname} and {nickname}"),
        )
    }

    /** SPEC 7.10: Aadhaar can never be put into an SMS, even by editing a template. */
    @Test
    fun anAadhaarPlaceholderIsRefused() {
        assertEquals(listOf(TemplateProblem.UnknownPlaceholder("aadhaar")), TemplateEngine.validate("Your ID {aadhaar}"))
        assertFalse(TemplateEngine.ALL_PLACEHOLDERS.any { "aadhaar" in it })
        assertFalse(TemplateEngine.BROADCAST_PLACEHOLDERS.any { "aadhaar" in it })
    }

    @Test
    fun broadcastsOnlyAllowTheirOwnPlaceholders() {
        val allowed = TemplateEngine.BROADCAST_PLACEHOLDERS
        assertTrue(TemplateEngine.validate("Hi {name}, {gym_name} is closed. Call {trainer_phone}", allowed).isEmpty())
        assertEquals(
            listOf(TemplateProblem.UnknownPlaceholder("amount")),
            TemplateEngine.validate("Pay Rs {amount}", allowed),
        )
    }

    @Test
    fun validatorRefusesLinks() {
        listOf(
            "Pay at http://x.co", "https://gym.example", "www.gym.example", "see bit.ly/abc", "visit gym.com/pay",
            "gym.in/pay", "wa.me/91", "upi://pay", "t.me/gym", "see gym.com today",
        ).forEach { assertTrue(it, TemplateProblem.ContainsLink in TemplateEngine.validate(it)) }
    }

    @Test
    fun ordinaryTextIsNotMistakenForALink() {
        listOf("Please pay soon. Thank you!", "Rs 1200. UPI: {upi_id}", "Call 98765 43210.", "Open at 6.30 am").forEach {
            assertTrue(it, TemplateEngine.validate(it).isEmpty())
        }
    }

    @Test
    fun validatorRefusesAnEmptyBody() {
        assertEquals(listOf(TemplateProblem.Empty), TemplateEngine.validate("   "))
    }

    @Test
    fun validatorRefusesMoreThanFourParts() {
        assertTrue(TemplateEngine.validate("a".repeat(612)).isEmpty())
        assertEquals(listOf(TemplateProblem.TooManyParts(5)), TemplateEngine.validate("a".repeat(613)))
    }

    @Test
    fun curlyQuotesBecomeStraight() {
        assertEquals("it's \"fine\" 'ok'", TemplateEngine.straightenQuotes("it’s “fine” ‘ok’"))
        assertEquals(SmsEncoding.GSM7, SmsSegments.count(TemplateEngine.straightenQuotes("it’s")).encoding)
    }

    @Test
    fun describesEachSchedule() {
        fun describe(kind: MessageKind, offset: Int = 0, every: Int? = null, max: Int? = null) =
            TemplateEngine.describeSchedule(TemplateConfig(kind, "x", offsetDays = offset, repeatEveryDays = every, maxRepeats = max))
        assertEquals("Sends: 3 days before expiry", describe(MessageKind.BEFORE_DUE, -3))
        assertEquals("Sends: 1 day before expiry", describe(MessageKind.BEFORE_DUE, -1))
        assertEquals("Sends: on the expiry day", describe(MessageKind.ON_DUE))
        assertEquals("Sends: 3 days after expiry", describe(MessageKind.OVERDUE_1, 3))
        assertEquals("Sends: 7 days after expiry, then every 7 days, up to 4 times", describe(MessageKind.OVERDUE_2, 7, 7, 4))
        assertEquals("Sends: 7 days after a part payment, then every 7 days, up to 3 times", describe(MessageKind.BALANCE_DUE, 7, 7, 3))
        assertEquals("Sends: right after a payment", describe(MessageKind.PAYMENT_THANKS))
        assertEquals("Sends: on their birthday", describe(MessageKind.BIRTHDAY))
        assertEquals("Sends: when you send it", describe(MessageKind.MANUAL))
    }

    @Test
    fun bothPacksAreCompleteValidAndCheap() {
        for (language in PackLanguage.entries) {
            val pack = TemplatePacks.defaults(language)
            assertEquals(MessageKind.TEMPLATED.toSet(), pack.map { it.kind }.toSet())
            for (template in pack) {
                val label = "$language ${template.kind}"
                assertTrue(label, TemplateEngine.validate(template.body, language = language).isEmpty())
                val rendered = TemplateEngine.render(template.body, values, language)
                assertTrue(label, rendered.missing.isEmpty())
                val count = SmsSegments.count(rendered.text)
                assertEquals(label, SmsEncoding.GSM7, count.encoding)
                assertTrue(label, count.parts <= SmsSegments.WARN_ABOVE_PARTS)
            }
        }
    }

    @Test
    fun thePacksKeepTheScheduleFromTheSpec() {
        val schedule = TemplatePacks.defaults(english).associateBy { it.kind }
        assertEquals(-3, schedule.getValue(MessageKind.BEFORE_DUE).offsetDays)
        assertEquals(0, schedule.getValue(MessageKind.ON_DUE).offsetDays)
        assertEquals(3, schedule.getValue(MessageKind.OVERDUE_1).offsetDays)
        assertEquals(7, schedule.getValue(MessageKind.OVERDUE_2).offsetDays)
        assertEquals(7, schedule.getValue(MessageKind.OVERDUE_2).repeatEveryDays)
        assertEquals(4, schedule.getValue(MessageKind.OVERDUE_2).maxRepeats)
        assertEquals(7, schedule.getValue(MessageKind.BALANCE_DUE).offsetDays)
        assertEquals(3, schedule.getValue(MessageKind.BALANCE_DUE).maxRepeats)
    }
}
