package com.swastik.mbfitness.ui.screens.payment

import com.swastik.mbfitness.launchSafely
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.ExpiryChange
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.PaymentEdit
import com.swastik.mbfitness.data.PaymentEntity
import com.swastik.mbfitness.data.PaymentVersion
import com.swastik.mbfitness.data.paymentHistory
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.domain.PaymentKind
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.domain.ReceiptNumbers
import com.swastik.mbfitness.domain.ScreenRules
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.ConfirmDialog
import com.swastik.mbfitness.ui.components.DateField
import com.swastik.mbfitness.ui.components.DangerButton
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbSegmented
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.PrimaryButton
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.components.SnackAction
import com.swastik.mbfitness.ui.screens.member.displayName
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PaymentForm(
    val charged: String,
    val paid: String,
    val paidOn: Long,
    val mode: PaymentMode,
    val note: String,
    val openingExpiry: Long?,
)

data class PaymentDetailUiState(
    val payment: PaymentEntity? = null,
    val history: List<PaymentVersion> = emptyList(),
    val form: PaymentForm? = null,
    val deleteEffect: ExpiryChange? = null,
)

/** Edit and delete, with the old values kept and the effect shown first (SPEC 7.5). */
class PaymentDetailViewModel(
    private val paymentId: Long,
    private val members: MemberRepository,
) : ViewModel() {
    private val form = MutableStateFlow<PaymentForm?>(null)
    private val deleteEffect = MutableStateFlow<ExpiryChange?>(null)

    val uiState: StateFlow<PaymentDetailUiState> =
        combine(members.observePayment(paymentId), form, deleteEffect) { payment, editing, effect ->
            PaymentDetailUiState(
                payment = payment,
                history = payment?.let { paymentHistory(it.editHistory) }.orEmpty(),
                form = editing,
                deleteEffect = effect,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PaymentDetailUiState())

    fun startEditing() {
        val payment = uiState.value.payment ?: return
        form.value = PaymentForm(
            charged = payment.charged.toString(),
            paid = payment.paid.toString(),
            paidOn = payment.paidOn,
            mode = payment.mode,
            note = payment.note,
            openingExpiry = payment.periodEnd.takeIf { payment.kind == PaymentKind.OPENING },
        )
    }

    fun cancelEditing() {
        form.value = null
    }

    fun onChargedChange(text: String) = form.update { it?.copy(charged = ScreenRules.amountInput(text)) }

    fun onPaidChange(text: String) = form.update { it?.copy(paid = ScreenRules.amountInput(text)) }

    fun onDateChange(day: Long) = form.update { it?.copy(paidOn = day) }

    fun onModeChange(mode: PaymentMode) = form.update { it?.copy(mode = mode) }

    fun onNoteChange(text: String) = form.update { it?.copy(note = text) }

    fun onOpeningExpiryChange(day: Long) = form.update { it?.copy(openingExpiry = day) }

    fun saveEdit(onDone: (ExpiryChange) -> Unit) {
        val editing = form.value ?: return
        val charged = ScreenRules.parseAmount(editing.charged) ?: return
        val paid = ScreenRules.parseAmount(editing.paid) ?: return
        viewModelScope.launch {
            val change = members.editPayment(
                paymentId,
                PaymentEdit(charged, paid, editing.paidOn, editing.mode, editing.note.trim(), openingExpiry = editing.openingExpiry),
            )
            form.value = null
            onDone(change)
        }
    }

    fun askToDelete() {
        viewModelScope.launch { deleteEffect.value = members.previewDeletePayment(paymentId) }
    }

    fun dismissDelete() {
        deleteEffect.value = null
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            members.deletePayment(paymentId)
            deleteEffect.value = null
            onDone()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private fun effectText(change: ExpiryChange): String =
    if (change.before == change.after) {
        "Expiry stays ${Dates.formatShort(change.after)}."
    } else {
        "Expiry changes from ${Dates.formatShort(change.before)} to ${Dates.formatShort(change.after)}."
    }

/** Tapped from a member's payment history (SPEC 10.2 screen 4). */
@Composable
fun PaymentDetailScreen(paymentId: Long, onBack: () -> Unit) {
    val viewModel = appViewModel(key = "payment-$paymentId") { app -> PaymentDetailViewModel(paymentId, app.members) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = (context.applicationContext as com.swastik.mbfitness.App).members
    val appScope = rememberCoroutineScope()
    val colors = MbTheme.colors
    val payment = state.payment

    Column(Modifier.fillMaxSize()) {
        MbTopBar(payment?.receiptNo?.let(ReceiptNumbers::format) ?: "Payment", onBack = onBack)
        if (payment == null) return@Column
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val form = state.form
            if (form == null) {
                Summary(payment)
                Row(horizontalArrangement = Arrangement.spacedBy(ListGap)) {
                    SecondaryButton("Edit", viewModel::startEditing, Modifier.weight(1f))
                    if (payment.deletedAt == null) DangerButton("Delete", viewModel::askToDelete, Modifier.weight(1f))
                }
            } else {
                EditForm(payment, form, viewModel) { change ->
                    snackbar.show("Saved. ${effectText(change)}")
                }
            }
            if (state.history.isNotEmpty()) {
                SectionLabel("Earlier versions")
                state.history.asReversed().forEach { version ->
                    MbCard(Modifier.fillMaxWidth()) {
                        Text(
                            "${Money.format(version.paid)} · ${version.mode.displayName()} · ${Dates.formatShort(version.paidOn)}",
                            style = MbType.Label,
                            color = colors.textPrimary,
                        )
                        Text(
                            "Charged ${Money.format(version.charged)}. Changed on ${Dates.formatFull(Dates.dayOf(version.replacedAt))}.",
                            style = MbType.Caption,
                            color = colors.textSecondary,
                        )
                        if (version.note.isNotBlank()) Text(version.note, style = MbType.Caption, color = colors.textSecondary)
                    }
                }
            }
        }
    }

    state.deleteEffect?.let { effect ->
        ConfirmDialog(
            title = "Delete this payment?",
            message = effectText(effect),
            confirmLabel = "Delete",
            danger = true,
            onConfirm = {
                viewModel.delete {
                    snackbar.show(
                        "Payment deleted",
                        listOf(SnackAction("Undo") { appScope.launchSafely(context) { repository.restorePayment(paymentId) } }),
                    )
                    onBack()
                }
            },
            onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun Summary(payment: PaymentEntity) {
    val colors = MbTheme.colors
    MbCard(Modifier.fillMaxWidth()) {
        Text(com.swastik.mbfitness.ui.screens.member.paymentTitle(payment), style = MbType.BodyLarge, color = colors.textPrimary)
        Text("Date: ${Dates.formatFull(payment.paidOn)}", style = MbType.Body, color = colors.textSecondary)
        if (payment.kind != PaymentKind.OPENING) {
            Text("Charged: ${Money.format(payment.charged)}", style = MbType.Body, color = colors.textSecondary)
        }
        val start = payment.periodStart
        val end = payment.periodEnd
        if (payment.kind == PaymentKind.RENEWAL && start != null && end != null) {
            Text("Period: ${Dates.formatShort(start)} to ${Dates.formatFull(end)}", style = MbType.Body, color = colors.textSecondary)
        }
        if (payment.kind == PaymentKind.OPENING && end != null) {
            Text("Expiry from import: ${Dates.formatFull(end)}", style = MbType.Body, color = colors.textSecondary)
        }
        if (payment.note.isNotBlank()) Text(payment.note, style = MbType.Body, color = colors.textSecondary)
        payment.editedAt?.let {
            Text("Edited on ${Dates.formatFull(Dates.dayOf(it))}", style = MbType.Caption, color = colors.textSecondary)
        }
    }
}

@Composable
private fun EditForm(
    payment: PaymentEntity,
    form: PaymentForm,
    viewModel: PaymentDetailViewModel,
    onSaved: (ExpiryChange) -> Unit,
) {
    val isOpening = payment.kind == PaymentKind.OPENING
    MbTextField(
        form.charged,
        viewModel::onChargedChange,
        if (isOpening) "Balance at start (₹)" else "Amount charged (₹)",
        keyboardType = KeyboardType.Number,
    )
    if (!isOpening) {
        MbTextField(form.paid, viewModel::onPaidChange, "Amount received (₹)", keyboardType = KeyboardType.Number)
        MbSegmented(PaymentMode.entries, form.mode, PaymentMode::displayName, viewModel::onModeChange)
    }
    DateField("Date", form.paidOn, { day -> day?.let(viewModel::onDateChange) })
    form.openingExpiry?.let { expiry ->
        DateField("Expiry", expiry, { day -> day?.let(viewModel::onOpeningExpiryChange) })
    }
    MbTextField(
        form.note,
        viewModel::onNoteChange,
        "Note (optional)",
        singleLine = false,
        capitalization = KeyboardCapitalization.Sentences,
        autoCorrect = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(ListGap)) {
        SecondaryButton("Cancel", viewModel::cancelEditing, Modifier.weight(1f))
        PrimaryButton(
            "Save",
            { viewModel.saveEdit(onSaved) },
            Modifier.weight(1f),
            enabled = ScreenRules.parseAmount(form.charged) != null && ScreenRules.parseAmount(form.paid) != null,
        )
    }
}
