package com.swastik.mbfitness.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.swastik.mbfitness.domain.UpiLink
import com.swastik.mbfitness.ui.screens.payment.qrMatrix
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reads the code back with a real QR reader, so what a UPI app scans is what we meant. */
class UpiQrTest {
    private fun decode(content: String): String {
        val matrix = qrMatrix(content)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix.get(index % matrix.width, index / matrix.width)) 0 else 0xFFFFFF
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun theCodeHoldsTheExactPaymentLink() {
        val link = UpiLink.build("gymowner@okbank", "Jeetu Bhaiya", 1200, UpiLink.note("Rahul Sharma"))
        assertEquals(link, decode(link))
        assertEquals("upi://pay?pa=gymowner@okbank&pn=Jeetu%20Bhaiya&am=1200.00&cu=INR&tn=MB%20Fitness%20-%20Rahul", decode(link))
    }

    @Test
    fun aLongLinkStillFits() {
        val link = UpiLink.build("a.very.long.upi.identifier.for.a.gym@somebankname", "M".repeat(60), 9_999_999, "MB Fitness - " + "N".repeat(40))
        assertEquals(link, decode(link))
    }
}
