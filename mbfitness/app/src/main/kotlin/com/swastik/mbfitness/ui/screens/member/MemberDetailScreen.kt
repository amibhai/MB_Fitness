package com.swastik.mbfitness.ui.screens.member

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.App
import com.swastik.mbfitness.R
import com.swastik.mbfitness.data.MemberEntity
import com.swastik.mbfitness.data.MessageEntity
import com.swastik.mbfitness.data.PaymentEntity
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.MemberState
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.domain.PaymentKind
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.ReceiptNumbers
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.AdvanceBadge
import com.swastik.mbfitness.ui.components.Avatar
import com.swastik.mbfitness.ui.components.ConfirmDialog
import com.swastik.mbfitness.ui.components.DangerButton
import com.swastik.mbfitness.ui.components.DueBadge
import com.swastik.mbfitness.ui.components.LinkButton
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbCheckbox
import com.swastik.mbfitness.ui.components.MbIconButton
import com.swastik.mbfitness.ui.components.MbSwitchRow
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.PrimaryButton
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.components.StatusChip
import com.swastik.mbfitness.ui.components.rememberSmsPermissionRequest
import com.swastik.mbfitness.ui.screens.home.statusColors
import com.swastik.mbfitness.ui.screens.home.statusLabel
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

private enum class DetailDialog { SEND_SMS, FREEZE, LEFT, DELETE, STOP_SMS, REMOVE_AADHAAR, PIN_FOR_REVEAL, NEED_PIN }

/** SPEC 10.2 screen 4. */
@Composable
fun MemberDetailScreen(
    memberId: Long,
    onBack: () -> Unit,
    onRecordPayment: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onPaymentClick: (Long) -> Unit,
    onSetPin: () -> Unit,
) {
    val viewModel = appViewModel(key = "member-$memberId") { app ->
        MemberDetailViewModel(memberId, app.members, app.messages, app.outboxKicker, app.settings, app.clock)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    var dialog by remember { mutableStateOf<DetailDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    // Leaving the app hides the number at once, so it never waits in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.hideAadhaar() }

    val member = state.member
    if (state.loaded && (member == null || member.deletedAt != null)) {
        Column(Modifier.fillMaxSize()) {
            MbTopBar("Member", onBack = onBack)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        MbTopBar(
            member?.name.orEmpty(),
            onBack = onBack,
            actions = {
                Box {
                    MbIconButton(R.drawable.ic_more_vert, "More options", { menuOpen = true }, tint = MbTheme.colors.onHeader)
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                menuOpen = false
                                onEdit(memberId)
                            },
                        )
                        if (member?.status == MemberState.LEFT) {
                            DropdownMenuItem(
                                text = { Text("Rejoin") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.rejoin()
                                    onRecordPayment(memberId)
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Mark as Left") },
                                onClick = {
                                    menuOpen = false
                                    dialog = DetailDialog.LEFT
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete permanently", color = MbTheme.colors.danger) },
                            onClick = {
                                menuOpen = false
                                dialog = DetailDialog.DELETE
                            },
                        )
                    }
                }
            },
        )
        if (member == null) return@Column
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileCard(member, state)
            ActionButtons(
                member,
                viewModel,
                onRecordPayment = { onRecordPayment(memberId) },
                onSendSms = { dialog = DetailDialog.SEND_SMS },
                onFreeze = { dialog = DetailDialog.FREEZE },
            )
            SmsCard(member, viewModel, onStopSms = { dialog = DetailDialog.STOP_SMS })
            AadhaarCard(
                state.aadhaar,
                onAdd = { onEdit(memberId) },
                onShow = { dialog = if (state.pinSet) DetailDialog.PIN_FOR_REVEAL else DetailDialog.NEED_PIN },
                onHide = viewModel::hideAadhaar,
                onReenter = { onEdit(memberId) },
                onRemove = { dialog = DetailDialog.REMOVE_AADHAAR },
            )
            PaymentHistory(state.payments, onPaymentClick)
            MessageHistory(state.messages)
        }
    }

    when (dialog) {
        DetailDialog.SEND_SMS -> member?.let { person ->
            val app = LocalContext.current.applicationContext as App
            val requestPermission = rememberSmsPermissionRequest()
            SendSmsDialog(
                memberName = person.name,
                hasConsent = person.smsConsent,
                hasLeft = person.status == MemberState.LEFT,
                gymName = state.gymName,
                onSend = { text ->
                    dialog = null
                    if (!app.canSendSms()) requestPermission()
                    viewModel.sendSms(text, person.phone) { snackbar.show("SMS is on its way") }
                },
                onDismiss = { dialog = null },
            )
        }
        DetailDialog.FREEZE -> ConfirmDialog(
            title = "Freeze membership?",
            message = "Reminders stop while frozen. Expiry moves forward by the days frozen.",
            confirmLabel = "Freeze",
            onConfirm = {
                dialog = null
                viewModel.freeze()
            },
            onDismiss = { dialog = null },
        )
        DetailDialog.LEFT -> MarkLeftDialog(
            hasAadhaar = member?.aadhaarLast4 != null,
            onConfirm = { removeAadhaar ->
                dialog = null
                viewModel.markLeft(removeAadhaar)
            },
            onDismiss = { dialog = null },
        )
        DetailDialog.DELETE -> DeleteDialog(
            name = member?.name.orEmpty(),
            onConfirm = {
                dialog = null
                viewModel.delete {
                    snackbar.show("Moved to the recycle bin for 30 days")
                    onBack()
                }
            },
            onDismiss = { dialog = null },
        )
        DetailDialog.STOP_SMS -> ConfirmDialog(
            title = "Stop all SMS?",
            message = "This member won't get any more SMS from the app, including anything already waiting to go.",
            confirmLabel = "Stop SMS",
            danger = true,
            onConfirm = {
                dialog = null
                viewModel.stopSms()
            },
            onDismiss = { dialog = null },
        )
        DetailDialog.REMOVE_AADHAAR -> ConfirmDialog(
            title = "Remove Aadhaar?",
            message = "The number is erased from this phone. You can add it again later.",
            confirmLabel = "Remove",
            danger = true,
            onConfirm = {
                dialog = null
                viewModel.removeAadhaar()
            },
            onDismiss = { dialog = null },
        )
        DetailDialog.PIN_FOR_REVEAL -> com.swastik.mbfitness.ui.screens.lock.PinConfirmDialog(
            title = "Show Aadhaar number",
            onConfirmed = {
                dialog = null
                viewModel.revealAadhaar()
            },
            onDismiss = { dialog = null },
        )
        DetailDialog.NEED_PIN -> ConfirmDialog(
            title = "Set an app PIN first",
            message = "Aadhaar numbers can only be shown after a PIN or fingerprint check.",
            confirmLabel = "Set PIN",
            onConfirm = {
                dialog = null
                onSetPin()
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun ProfileCard(member: MemberEntity, state: MemberDetailUiState) {
    val colors = MbTheme.colors
    MbCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(member.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(member.name, style = MbType.BodyLarge, color = colors.textPrimary)
                Text(PhoneNumbers.display(member.phone), style = MbType.Caption, color = colors.textSecondary)
            }
            StatusChip(statusLabel(state.tab, member.status), statusColors(state.tab, colors))
        }
        Spacer(Modifier.padding(top = 12.dp))
        DetailLine("Plan", state.planName)
        DetailLine("Valid till", Dates.formatFull(member.expiryDate))
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Balance", Modifier.weight(1f), style = MbType.Body, color = colors.textSecondary)
            when {
                state.due > 0 -> DueBadge(state.due)
                state.advance > 0 -> AdvanceBadge(state.advance)
                else -> Text("Nothing due", style = MbType.Label, color = colors.textPrimary)
            }
        }
        member.dob?.let { DetailLine("Date of birth", Dates.formatFull(it)) }
        if (member.isMinor) {
            DetailLine("Under 18", member.guardianName ?: "Guardian not added")
            member.guardianPhone?.let { DetailLine("Guardian phone", PhoneNumbers.display(it)) }
        }
        if (member.notes.isNotBlank()) {
            Text(member.notes, Modifier.padding(top = 8.dp), style = MbType.Body, color = colors.textSecondary)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val colors = MbTheme.colors
    Row(Modifier.padding(top = 4.dp)) {
        Text(label, Modifier.weight(1f), style = MbType.Body, color = colors.textSecondary)
        Text(value, Modifier.weight(1.4f), style = MbType.Label, color = colors.textPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun ActionButtons(
    member: MemberEntity,
    viewModel: MemberDetailViewModel,
    onRecordPayment: () -> Unit,
    onSendSms: () -> Unit,
    onFreeze: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    PrimaryButton("Record payment", onRecordPayment, Modifier.fillMaxWidth(), icon = R.drawable.ic_payments)
    Row(horizontalArrangement = Arrangement.spacedBy(ListGap)) {
        SecondaryButton(
            "Call",
            {
                // Opens the dialer with the number filled in, so it needs no permission.
                context.startActivity(Intent(Intent.ACTION_DIAL, "tel:${member.phone}".toUri()))
            },
            Modifier.weight(1f),
            icon = R.drawable.ic_call,
        )
        SecondaryButton("Send SMS", onSendSms, Modifier.weight(1f), icon = R.drawable.ic_sms)
    }
    when (member.status) {
        MemberState.ACTIVE -> SecondaryButton("Freeze", onFreeze, Modifier.fillMaxWidth(), icon = R.drawable.ic_ac_unit)
        MemberState.FROZEN -> SecondaryButton(
            "Unfreeze",
            {
                viewModel.unfreeze { change ->
                    change?.let { snackbar.show("Valid till ${Dates.formatShort(it.after)} now") }
                }
            },
            Modifier.fillMaxWidth(),
            icon = R.drawable.ic_ac_unit,
        )
        MemberState.LEFT -> Unit
    }
}

@Composable
private fun SmsCard(member: MemberEntity, viewModel: MemberDetailViewModel, onStopSms: () -> Unit) {
    val colors = MbTheme.colors
    MbCard(Modifier.fillMaxWidth()) {
        SectionLabel("SMS")
        MbSwitchRow(
            member.smsConsent,
            viewModel::setSmsConsent,
            "Agreed to fee reminders",
            help = member.consentDate?.let { "Since ${Dates.formatFull(it)}" } ?: "No reminders will be sent to this member",
        )
        MbSwitchRow(member.remindersEnabled, viewModel::setRemindersEnabled, "Reminders on")
        if (member.smsConsent) {
            LinkButton("Stop SMS", onStopSms, color = colors.danger)
        }
    }
}

@Composable
private fun AadhaarCard(
    row: AadhaarRow,
    onAdd: () -> Unit,
    onShow: () -> Unit,
    onHide: () -> Unit,
    onReenter: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MbTheme.colors
    MbCard(Modifier.fillMaxWidth()) {
        SectionLabel("Aadhaar")
        Spacer(Modifier.padding(top = 4.dp))
        when (row) {
            AadhaarRow.None -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Not added", Modifier.weight(1f), style = MbType.Body, color = colors.textSecondary)
                LinkButton("Add", onAdd)
            }
            is AadhaarRow.Masked -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    com.swastik.mbfitness.domain.Aadhaar.masked(row.last4),
                    Modifier.weight(1f),
                    style = MbType.BodyLarge,
                    color = colors.textPrimary,
                )
                LinkButton("Show", onShow)
                LinkButton("Remove", onRemove, color = colors.danger)
            }
            is AadhaarRow.Revealed -> {
                // Blocks screenshots and the recent-apps preview while the number is on screen.
                com.swastik.mbfitness.ui.components.SecureWindow()
                Text(row.grouped, style = MbType.Title, color = colors.textPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hides in ${row.secondsLeft} s", Modifier.weight(1f), style = MbType.Caption, color = colors.textSecondary)
                    LinkButton("Hide", onHide)
                }
            }
            is AadhaarRow.Unavailable -> {
                Text(
                    com.swastik.mbfitness.domain.Aadhaar.masked(row.last4),
                    style = MbType.BodyLarge,
                    color = colors.textPrimary,
                )
                Text(
                    "Full number not available on this phone. Re-enter it.",
                    style = MbType.Caption,
                    color = colors.danger,
                )
                Row {
                    LinkButton("Re-enter", onReenter)
                    LinkButton("Remove", onRemove, color = colors.danger)
                }
            }
        }
    }
}

@Composable
private fun PaymentHistory(payments: List<PaymentEntity>, onClick: (Long) -> Unit) {
    val colors = MbTheme.colors
    SectionLabel("Payments")
    if (payments.isEmpty()) {
        Text("No payments yet.", style = MbType.Body, color = colors.textSecondary)
    }
    payments.forEach { payment ->
        MbCard(Modifier.fillMaxWidth(), onClick = { onClick(payment.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(paymentTitle(payment), style = MbType.Label, color = colors.textPrimary)
                    Text(
                        listOfNotNull(Dates.formatFull(payment.paidOn), payment.receiptNo?.let(ReceiptNumbers::format)).joinToString(" · "),
                        style = MbType.Caption,
                        color = colors.textSecondary,
                    )
                }
                if (payment.editedAt != null) StatusChip("Edited", colors.inactive)
            }
        }
    }
}

fun paymentTitle(payment: PaymentEntity): String = when (payment.kind) {
    PaymentKind.OPENING -> "Opening balance ${Money.format(payment.charged)}"
    PaymentKind.RENEWAL, PaymentKind.BALANCE -> "${Money.format(payment.paid)} · ${payment.mode.displayName()}"
}

@Composable
private fun MessageHistory(messages: List<MessageEntity>) {
    if (messages.isEmpty()) return
    val colors = MbTheme.colors
    SectionLabel("Messages")
    messages.forEach { message ->
        MbCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            Text(message.body, style = MbType.Caption, color = colors.textPrimary)
            Text(
                "${message.status.name.lowercase().replaceFirstChar { it.uppercase() }} · ${Dates.formatFull(Dates.dayOf(message.sentAt ?: message.createdAt))}",
                Modifier.padding(top = 4.dp),
                style = MbType.Caption,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun MarkLeftDialog(hasAadhaar: Boolean, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val colors = MbTheme.colors
    var removeAadhaar by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Mark as Left?", style = MbType.Title, color = colors.textPrimary) },
        text = {
            Column {
                Text("Reminders stop. Their history stays, and they can rejoin later.", style = MbType.Body, color = colors.textSecondary)
                if (hasAadhaar) {
                    MbCheckbox(removeAadhaar, { removeAadhaar = it }, "Also remove Aadhaar", Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(removeAadhaar) }) { Text("Mark as Left", style = MbType.Label) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", style = MbType.Label, color = colors.textSecondary) } },
    )
}

/** You have to type the member's name, so this can't happen by accident (SPEC 7.7). */
@Composable
private fun DeleteDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = MbTheme.colors
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Delete $name?", style = MbType.Title, color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "They go to the recycle bin for 30 days, then everything about them is erased for good. Type their name to confirm.",
                    style = MbType.Body,
                    color = colors.textSecondary,
                )
                MbTextField(typed, { typed = it }, "Member's name")
            }
        },
        confirmButton = {
            DangerButton("Delete", onConfirm, enabled = typed.trim().equals(name.trim(), ignoreCase = true))
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", style = MbType.Label, color = colors.textSecondary) } },
    )
}
