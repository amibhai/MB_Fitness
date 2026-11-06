package com.swastik.mbfitness.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.swastik.mbfitness.R
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
private val ControlShape = RoundedCornerShape(14.dp)

/** A text field with the app's colours. [error] replaces [help] and marks the field. */
@Composable
fun MbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    help: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Next,
    singleLine: Boolean = true,
    autoCorrect: Boolean = false,
    textStyle: TextStyle = MbType.Body,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    @DrawableRes leadingIcon: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MbTheme.colors
    OutlinedTextField(
        value,
        onValueChange,
        modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        textStyle = textStyle,
        isError = error != null,
        supportingText = (error ?: help)?.let { text -> { Text(text, style = MbType.Caption) } },
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            autoCorrectEnabled = autoCorrect,
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        visualTransformation = visualTransformation,
        leadingIcon = leadingIcon?.let { icon -> { Icon(painterResource(icon), contentDescription = null) } },
        trailingIcon = trailing,
        shape = ControlShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.textSecondary,
            focusedLabelColor = if (colors.isDark) colors.greeting else colors.button,
            unfocusedLabelColor = colors.textSecondary,
            cursorColor = colors.accent,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
        ),
    )
}

/** The same field, for when something needs the cursor: placeholders go in where it is. */
@Composable
fun MbTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    help: String? = null,
) {
    val colors = MbTheme.colors
    OutlinedTextField(
        value,
        onValueChange,
        modifier.fillMaxWidth(),
        label = { Text(label) },
        minLines = 3,
        textStyle = MbType.Body,
        isError = error != null,
        supportingText = (error ?: help)?.let { text -> { Text(text, style = MbType.Caption) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
        shape = ControlShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.textSecondary,
            focusedLabelColor = if (colors.isDark) colors.greeting else colors.button,
            unfocusedLabelColor = colors.textSecondary,
            cursorColor = colors.accent,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
        ),
    )
}

/** Puts [text] where the cursor is, with spaces either side only where they're needed. */
fun TextFieldValue.inserting(text: String): TextFieldValue {
    val before = this.text.substring(0, selection.min)
    val after = this.text.substring(selection.max)
    val lead = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
    val trail = if (after.isEmpty() || after.first().isWhitespace()) "" else " "
    val inserted = lead + text + trail
    return copy(text = before + inserted + after, selection = TextRange(before.length + inserted.length))
}

/** One choice among a few, like an audience or a template. */
@Composable
fun MbChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MbTheme.colors
    Text(
        label,
        modifier
            .clip(ControlShape)
            .background(if (selected) colors.button else colors.inactive.background)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        style = MbType.Label,
        color = if (selected) colors.onButton else colors.inactive.content,
    )
}

/** On or off, independently of the others, like the days of the week. */
@Composable
fun MbToggleChip(label: String, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MbTheme.colors
    Text(
        label,
        modifier
            .clip(ControlShape)
            .background(if (checked) colors.button else colors.inactive.background)
            .toggleable(checked, role = Role.Checkbox, onValueChange = { onClick() })
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        style = MbType.Label,
        color = if (checked) colors.onButton else colors.inactive.content,
    )
}

/** Does something when tapped, like inserting a placeholder. */
@Composable
fun MbActionChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MbTheme.colors
    Text(
        label,
        modifier
            .clip(ControlShape)
            .border(1.dp, colors.textSecondary, ControlShape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        style = MbType.Caption,
        color = colors.textPrimary,
    )
}

/** Shows a message the way the member would see it. */
@Composable
fun SmsBubble(text: String, modifier: Modifier = Modifier) {
    val colors = MbTheme.colors
    Text(
        text,
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp))
            .background(colors.avatarBackground)
            .padding(12.dp),
        style = MbType.Body,
        color = colors.textPrimary,
    )
}

/** Pick-one buttons, like UPI / Cash / Other. They read as a radio group to TalkBack. */
@Composable
fun <T> MbSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MbTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(colors.inactive.background)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Text(
                label(option),
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isSelected) colors.button else colors.inactive.background)
                    .selectable(isSelected, role = Role.RadioButton) { onSelect(option) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 4.dp, vertical = 14.dp),
                style = MbType.Label,
                color = if (isSelected) colors.onButton else colors.inactive.content,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A whole-row checkbox, so the label is part of the touch target. */
@Composable
fun MbCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    val colors = MbTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(ControlShape)
            .toggleable(checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = colors.button))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MbType.Body, color = colors.textPrimary)
    }
}

/** A whole-row switch. */
@Composable
fun MbSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    help: String? = null,
) {
    val colors = MbTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MbType.Label, color = colors.textPrimary)
            if (help != null) Text(help, style = MbType.Caption, color = colors.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.button))
    }
}

/** A tappable choice card, such as a plan. */
@Composable
fun ChoiceCard(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val colors = MbTheme.colors
    val border = if (selected) BorderStroke(2.dp, colors.accent) else BorderStroke(1.dp, colors.border)
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ControlShape,
        color = if (selected) colors.avatarBackground else colors.surface,
        border = border,
    ) {
        Row(
            Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MbType.BodyLarge, color = colors.textPrimary)
                if (subtitle != null) Text(subtitle, style = MbType.Caption, color = colors.textSecondary)
            }
            if (trailing != null) Text(trailing, style = MbType.BodyLarge, color = colors.textPrimary)
        }
    }
}

/**
 * A date as a tappable row. The picker works in UTC millis, so converting to and from epoch
 * days this way can't shift the date by a time zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, day: Long?, onPick: (Long?) -> Unit, modifier: Modifier = Modifier, allowClear: Boolean = false) {
    val colors = MbTheme.colors
    var open by remember { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        modifier = modifier.fillMaxWidth(),
        shape = ControlShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.textSecondary),
    ) {
        Row(
            Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MbType.Caption, color = colors.textSecondary)
                Text(day?.let(Dates::formatFull) ?: "Not set", style = MbType.Body, color = colors.textPrimary)
            }
            Icon(painterResource(R.drawable.ic_calendar_month), contentDescription = null, tint = colors.textSecondary)
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day?.let { it * MILLIS_PER_DAY })
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onPick(it / MILLIS_PER_DAY) }
                        open = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                Row {
                    if (allowClear) {
                        TextButton(
                            onClick = {
                                onPick(null)
                                open = false
                            },
                        ) { Text("Clear") }
                    }
                    TextButton(onClick = { open = false }) { Text("Cancel") }
                }
            },
        ) {
            DatePicker(state)
        }
    }
}

/** A plain confirmation. [danger] makes the action red (SPEC 10.2). */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    dismissLabel: String = "Cancel",
) {
    val colors = MbTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        title = { Text(title, style = MbType.Title) },
        text = { Text(message, style = MbType.Body) },
        confirmButton = {
            TextButton(onConfirm, Modifier.heightIn(min = 48.dp)) {
                Text(confirmLabel, style = MbType.Label, color = if (danger) colors.danger else if (colors.isDark) colors.greeting else colors.button)
            }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) {
                Text(dismissLabel, style = MbType.Label, color = colors.textSecondary)
            }
        },
    )
}
