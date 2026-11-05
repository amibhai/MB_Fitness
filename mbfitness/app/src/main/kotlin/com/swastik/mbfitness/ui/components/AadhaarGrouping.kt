package com.swastik.mbfitness.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Shows typed digits as "1234 5678 9012" while the field keeps only the digits (SPEC 7.10). */
object AadhaarGrouping : VisualTransformation {
    private const val GROUP = 4

    override fun filter(text: AnnotatedString): TransformedText {
        val grouped = text.text.chunked(GROUP).joinToString(" ")
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                if (offset == 0) 0 else offset + (offset - 1) / GROUP

            override fun transformedToOriginal(offset: Int): Int =
                (offset - offset / (GROUP + 1)).coerceIn(0, text.length)
        }
        return TransformedText(AnnotatedString(grouped), mapping)
    }
}
