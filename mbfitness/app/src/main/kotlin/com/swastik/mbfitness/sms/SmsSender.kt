package com.swastik.mbfitness.sms

import com.swastik.mbfitness.domain.SendingRules

data class SmsRequest(
    val messageId: Long,
    val attempt: Int,
    val phone: String,
    val body: String,
    val memberName: String?,
    val subscriptionId: Int?,
)

enum class BlockReason(val message: String) {
    NO_PERMISSION("SMS permission is off"),
    NO_TRAINER_NUMBER("Test mode needs your phone number in Settings"),
}

sealed interface SendOutcome {
    /** The radio took it. Results arrive later, one part at a time. */
    data class Accepted(val parts: Int) : SendOutcome

    /** This message couldn't go. It counts as a try. */
    data class Failed(val reason: String) : SendOutcome

    /** Nothing can go until this is fixed. It doesn't count as a try. */
    data class Blocked(val reason: BlockReason) : SendOutcome
}

/** One SMS to the radio. Only OutboxWorker may call [send] (hard rule 3). */
fun interface SmsSender {
    fun send(request: SmsRequest): SendOutcome
}

/**
 * Sends everything to the owner's own number instead, marked so it's obvious (SPEC 8.11). Debug
 * builds always go through this (hard rule 9).
 */
class TestModeSender(private val delegate: SmsSender, private val trainerPhone: String) : SmsSender {
    override fun send(request: SmsRequest): SendOutcome {
        if (trainerPhone.isBlank()) return SendOutcome.Blocked(BlockReason.NO_TRAINER_NUMBER)
        return delegate.send(
            request.copy(phone = trainerPhone, body = SendingRules.testBody(request.memberName, request.body)),
        )
    }
}
