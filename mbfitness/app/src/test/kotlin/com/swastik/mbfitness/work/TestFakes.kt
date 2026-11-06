package com.swastik.mbfitness.work

import com.swastik.mbfitness.domain.SummaryText

class FakeKicker : OutboxKicker {
    var kicks = 0
    val wakes = mutableListOf<Long>()

    override fun kick() {
        kicks++
    }

    override fun wakeAt(millis: Long) {
        wakes += millis
    }
}

class FakeNotices : Notices {
    val summaries = mutableListOf<SummaryText>()
    val problems = mutableListOf<ProblemKind>()

    override fun morningSummary(text: SummaryText) {
        summaries += text
    }

    override fun problem(kind: ProblemKind) {
        problems += kind
    }
}
