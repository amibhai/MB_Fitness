package com.swastik.mbfitness.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.swastik.mbfitness.R
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

/** Filled dots for digits entered. The count is read out, never the digits. */
@Composable
fun PinDots(filled: Int, total: Int, modifier: Modifier = Modifier, color: Color = MbTheme.colors.accent) {
    val border = MbTheme.colors.textSecondary
    Row(
        modifier.clearAndSetSemantics { contentDescription = "$filled of $total digits entered" },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(total) { index ->
            val isFilled = index < filled
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (isFilled) color else Color.Transparent)
                    .border(BorderStroke(1.5.dp, if (isFilled) color else border), CircleShape),
            )
        }
    }
}

/** Big digits, usable with a thumb between sets. [cornerKey] fills the empty spot beside 0. */
@Composable
fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyColor: Color = MbTheme.colors.textPrimary,
    cornerKey: (@Composable () -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { digit -> DigitKey(digit, enabled, keyColor) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(KeySize), contentAlignment = Alignment.Center) { cornerKey?.invoke() }
            DigitKey('0', enabled, keyColor) { onDigit('0') }
            Box(
                Modifier
                    .size(KeySize)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, onClick = onBackspace)
                    .semantics { contentDescription = "Delete last digit" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_backspace), contentDescription = null, tint = keyColor)
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(KeySize)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(digit.toString(), style = MbType.Title, color = color)
    }
}

private val KeySize = 68.dp
