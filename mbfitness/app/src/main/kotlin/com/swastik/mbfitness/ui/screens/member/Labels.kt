package com.swastik.mbfitness.ui.screens.member

import com.swastik.mbfitness.domain.PaymentMode

fun PaymentMode.displayName(): String = when (this) {
    PaymentMode.UPI -> "UPI"
    PaymentMode.CASH -> "Cash"
    PaymentMode.OTHER -> "Other"
}
