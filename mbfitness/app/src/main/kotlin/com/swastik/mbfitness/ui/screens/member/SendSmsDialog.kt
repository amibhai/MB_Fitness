package com.swastik.mbfitness.ui.screens.member

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.SenderLine
import com.swastik.mbfitness.domain.SmsSegments
import com.swastik.mbfitness.domain.TemplateEngine
import com.swastik.mbfitness.domain.TemplateProblem
import com.swastik.mbfitness.domain.TemplateRules
import com.swastik.mbfitness.ui.components.ConfirmDialog
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.SmsBubble
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

/**
 * A one-off SMS to one member. Links are refused and the gym's name goes on the end, the same as
 * every other SMS. Someone who hasn't agreed, or has left, gets a warning first (SPEC 8.9).
 */
@Composable
fun SendSmsDialog(
    memberName: String,
    hasConsent: Boolean,
    hasLeft: Boolean,
    gymName: String,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MbTheme.colors
    var text by rememberSaveable { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    val straight = TemplateEngine.straightenQuotes(text)
    val final = SenderLine.add(straight, gymName)
    val problem = TemplateEngine.validate(straight, TemplateRules.placeholdersFor(MessageKind.MANUAL))
        .firstOrNull { it != TemplateProblem.Empty }
    val parts = SmsSegments.count(final)
    val canSend = straight.isNotBlank() && problem == null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textPrimary,
        title = { Text("SMS to $memberName", style = MbType.Title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MbTextField(
                    text,
                    { text = it },
                    "Message",
                    error = problem?.let(TemplateRules::describe),
                    help = if (straight.isNotBlank()) SmsSegments.describe(parts) else null,
                    capitalization = KeyboardCapitalization.Sentences,
                    singleLine = false,
                    autoCorrect = true,
                )
                if (straight.isNotBlank()) {
                    Text("They'll get:", Modifier.padding(top = 8.dp, bottom = 4.dp), style = MbType.Caption, color = colors.textSecondary)
                    SmsBubble(final, Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (hasConsent && !hasLeft) onSend(final) else confirming = true },
                Modifier.heightIn(min = 48.dp),
                enabled = canSend,
            ) {
                Text(
                    "Send",
                    style = MbType.Label,
                    color = when {
                        !canSend -> colors.textSecondary
                        colors.isDark -> colors.greeting
                        else -> colors.button
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) {
                Text("Cancel", style = MbType.Label, color = colors.textSecondary)
            }
        },
    )

    if (confirming) {
        ConfirmDialog(
            title = "Send anyway?",
            message = if (hasLeft) {
                "Messages to ex-members can be reported as spam and get your number blocked."
            } else {
                "$memberName hasn't agreed to get SMS from the gym."
            },
            confirmLabel = "Send anyway",
            danger = true,
            onConfirm = {
                confirming = false
                onSend(final)
            },
            onDismiss = { confirming = false },
        )
    }
}
