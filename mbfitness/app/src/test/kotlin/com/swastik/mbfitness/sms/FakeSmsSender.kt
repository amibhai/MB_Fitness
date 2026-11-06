package com.swastik.mbfitness.sms

import com.swastik.mbfitness.domain.SmsSegments

/** Records what would have gone. It can't send anything, so tests can never text anyone. */
class FakeSmsSender : SmsSender {
    /** Every time the radio would have been used, including tries that failed. */
    val calls = mutableListOf<SmsRequest>()
    val accepted = mutableListOf<SmsRequest>()
    var respond: (SmsRequest) -> SendOutcome = { SendOutcome.Accepted(SmsSegments.count(it.body).parts) }

    override fun send(request: SmsRequest): SendOutcome {
        val outcome = respond(request)
        if (outcome !is SendOutcome.Blocked) calls += request
        if (outcome is SendOutcome.Accepted) accepted += request
        return outcome
    }
}
