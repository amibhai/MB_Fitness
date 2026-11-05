package com.swastik.mbfitness.domain

/** What is stored on a member. The tabs on Home are derived from this (SPEC 7.2). */
enum class MemberState { ACTIVE, FROZEN, LEFT }

enum class PaymentKind { RENEWAL, BALANCE, OPENING }

enum class PaymentMode { UPI, CASH, OTHER }

/** SPEC 7.3. A continues from the old expiry, B starts from the payment date. */
enum class RenewalRule { CONTINUE_FROM_EXPIRY, START_FROM_PAYMENT }

enum class MemberEventType { JOINED, LEFT, REJOINED, FROZEN, UNFROZEN, DELETED, RESTORED }

enum class MessageStatus { QUEUED, SENDING, SENT, DELIVERED, FAILED, CANCELLED }

/** Which preset the templates came from. It also sets the wording of {days_overdue}. */
enum class PackLanguage { ENGLISH, HINGLISH }

/**
 * Every SMS the app can send. [priority] is the queue order from SPEC 8.4 (1 goes first).
 * [isPlanned] marks the kinds [ReminderPlanner] creates on its own.
 */
enum class MessageKind(val priority: Int, val isPlanned: Boolean) {
    BEFORE_DUE(priority = 4, isPlanned = true),
    ON_DUE(priority = 3, isPlanned = true),
    OVERDUE_1(priority = 2, isPlanned = true),
    OVERDUE_2(priority = 2, isPlanned = true),
    BALANCE_DUE(priority = 4, isPlanned = true),
    PAYMENT_THANKS(priority = 1, isPlanned = false),
    BIRTHDAY(priority = 5, isPlanned = true),
    BROADCAST(priority = 5, isPlanned = false),
    MANUAL(priority = 1, isPlanned = false),
    TEST(priority = 1, isPlanned = false),
    ;

    /** Reminders about a fee: their wording goes stale, and paying makes them false. */
    val isFeeReminder: Boolean
        get() = this == BEFORE_DUE || this == ON_DUE || this == OVERDUE_1 ||
            this == OVERDUE_2 || this == BALANCE_DUE

    companion object {
        /** The kinds that have an editable template row (SPEC 6). */
        val TEMPLATED: List<MessageKind> =
            listOf(BEFORE_DUE, ON_DUE, OVERDUE_1, OVERDUE_2, BALANCE_DUE, PAYMENT_THANKS, BIRTHDAY)
    }
}
