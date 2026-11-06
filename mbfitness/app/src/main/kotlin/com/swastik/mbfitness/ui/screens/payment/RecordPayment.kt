package com.swastik.mbfitness.ui.screens.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.data.BalanceRequest
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.PaymentSaved
import com.swastik.mbfitness.data.PlanEntity
import com.swastik.mbfitness.data.RenewalRequest
import com.swastik.mbfitness.data.SettingsStore
import com.swastik.mbfitness.domain.AfterPayment
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.MemberRules
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.domain.ScreenRules
import com.swastik.mbfitness.domain.TodayProvider
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.ChoiceCard
import com.swastik.mbfitness.ui.components.DueBadge
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.MbBanner
import com.swastik.mbfitness.ui.components.BannerTone
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbSegmented
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.PrimaryButton
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.screens.member.displayName
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import com.swastik.mbfitness.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PaymentTab { RENEWAL, BALANCE }

data class RecordPaymentUiState(
    val loaded: Boolean = false,
    val memberName: String = "",
    val tab: PaymentTab = PaymentTab.RENEWAL,
    val plans: List<PlanEntity> = emptyList(),
    val planId: Long? = null,
    val balance: Long = 0,
    val amountText: String = "",
    val mode: PaymentMode = PaymentMode.UPI,
    val newExpiry: Long? = null,
    val startsFresh: Boolean = false,
    val periodStart: Long? = null,
    val after: AfterPayment? = null,
    val upiReady: Boolean = false,
    val saving: Boolean = false,
    /** The figures are being worked out again. Save waits, so it can't record stale numbers. */
    val previewing: Boolean = true,
    val saved: PaymentSaved? = null,
) {
    val amount: Long? get() = ScreenRules.parseAmount(amountText)
    val canSave: Boolean
        get() = !saving && !previewing && amount != null &&
            if (tab == PaymentTab.BALANCE) (amount ?: 0) > 0 else planId != null
}

/** SPEC 7.3. Everything shown before saving comes from the same rules that do the saving. */
class RecordPaymentViewModel(
    private val memberId: Long,
    private val members: MemberRepository,
    private val settings: SettingsStore,
    private val clock: TodayProvider,
) : ViewModel() {
    private val state = MutableStateFlow(RecordPaymentUiState())
    val uiState: StateFlow<RecordPaymentUiState> = state.asStateFlow()
    private var amountEdited = false
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            val member = members.member(memberId) ?: return@launch
            val plans = members.observeActivePlans().first()
            val balance = members.balanceOf(memberId)
            val current = settings.current()
            state.update {
                it.copy(
                    loaded = true,
                    memberName = member.name,
                    plans = plans,
                    planId = plans.firstOrNull { plan -> plan.id == member.planId }?.id ?: plans.firstOrNull()?.id,
                    balance = balance,
                    upiReady = current.upiId.isNotBlank(),
                )
            }
            preview(resetAmount = true)
        }
    }

    fun onTabSelected(tab: PaymentTab) {
        amountEdited = false
        state.update { it.copy(tab = tab) }
        preview(resetAmount = true)
    }

    fun onPlanSelected(id: Long) {
        state.update { it.copy(planId = id) }
        preview(resetAmount = !amountEdited)
    }

    fun onAmountChange(text: String) {
        amountEdited = true
        state.update { it.copy(amountText = ScreenRules.amountInput(text)) }
        preview(resetAmount = false)
    }

    /** Only the latest counts, so a fast typist can't end up with figures from an old keystroke. */
    private fun preview(resetAmount: Boolean) {
        previewJob?.cancel()
        state.update { it.copy(previewing = true) }
        previewJob = viewModelScope.launch {
            refreshPreview(resetAmount)
            state.update { it.copy(previewing = false) }
        }
    }

    fun onModeSelected(mode: PaymentMode) = state.update { it.copy(mode = mode) }

    fun save() {
        val current = state.value
        val amount = current.amount ?: return
        if (!current.canSave) return
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            // If this fails, the message says to try again, so Save has to come back.
            try {
                val saved = when (current.tab) {
                    PaymentTab.RENEWAL -> members.recordRenewal(
                        RenewalRequest(memberId, requireNotNull(current.planId), amount, current.mode, clock.today()),
                    )
                    PaymentTab.BALANCE -> members.recordBalance(BalanceRequest(memberId, amount, current.mode, clock.today()))
                }
                state.update { it.copy(saved = saved) }
            } finally {
                state.update { it.copy(saving = false) }
            }
        }
    }

    private suspend fun refreshPreview(resetAmount: Boolean) {
        val current = state.value
        when (current.tab) {
            PaymentTab.RENEWAL -> {
                val planId = current.planId ?: return
                val preview = members.previewRenewal(memberId, planId, clock.today(), current.amount.takeUnless { resetAmount })
                state.update {
                    it.copy(
                        amountText = if (resetAmount) preview.defaultReceived.toString() else it.amountText,
                        newExpiry = preview.newExpiry,
                        periodStart = preview.periodStart,
                        startsFresh = preview.startsFresh,
                        after = preview.after,
                    )
                }
            }
            PaymentTab.BALANCE -> {
                val amount = if (resetAmount) MemberRules.due(current.balance) else current.amount ?: 0
                state.update {
                    it.copy(
                        amountText = if (resetAmount) amount.toString() else it.amountText,
                        newExpiry = null,
                        periodStart = null,
                        startsFresh = false,
                        after = MemberRules.afterPayment(current.balance, 0, amount),
                    )
                }
            }
        }
    }
}

/** SPEC 10.2 screen 5. */
@Composable
fun RecordPaymentScreen(
    memberId: Long,
    onBack: () -> Unit,
    onSaved: (PaymentSaved) -> Unit,
    onShowQr: (memberName: String, amount: Long) -> Unit,
) {
    val viewModel = appViewModel(key = "pay-$memberId") { app ->
        RecordPaymentViewModel(memberId, app.members, app.settings, app.clock)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = MbTheme.colors

    LaunchedEffect(state.saved) { state.saved?.let(onSaved) }

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Record payment", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.memberName, style = MbType.Title, color = colors.textPrimary)
            if (state.balance > 0) DueBadge(state.balance)
            MbSegmented(
                PaymentTab.entries,
                state.tab,
                label = { if (it == PaymentTab.RENEWAL) "Renewal" else "Pay balance" },
                onSelect = viewModel::onTabSelected,
            )
            if (state.tab == PaymentTab.RENEWAL) {
                SectionLabel("Plan")
                Column(verticalArrangement = Arrangement.spacedBy(ListGap)) {
                    state.plans.forEach { plan ->
                        ChoiceCard(
                            plan.name,
                            subtitle = null,
                            selected = plan.id == state.planId,
                            onClick = { viewModel.onPlanSelected(plan.id) },
                            trailing = Money.format(plan.price),
                        )
                    }
                }
            }
            MbTextField(
                state.amountText,
                viewModel::onAmountChange,
                "Amount received (₹)",
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                textStyle = MbType.Display,
            )
            SectionLabel("Paid by")
            MbSegmented(PaymentMode.entries, state.mode, PaymentMode::displayName, viewModel::onModeSelected)
            Summary(state)
            if (state.mode == PaymentMode.UPI) {
                if (state.upiReady) {
                    SecondaryButton(
                        "Show UPI QR",
                        { state.amount?.let { onShowQr(state.memberName, it) } },
                        Modifier.fillMaxWidth(),
                        enabled = (state.amount ?: 0) > 0,
                        icon = R.drawable.ic_qr_code_2,
                    )
                } else {
                    MbBanner("Add your UPI ID in Settings to show a QR code.", BannerTone.INFO)
                }
            }
            PrimaryButton("Save", viewModel::save, Modifier.fillMaxWidth(), enabled = state.canSave)
        }
    }
}

@Composable
private fun Summary(state: RecordPaymentUiState) {
    val colors = MbTheme.colors
    val after = state.after ?: return
    MbCard(Modifier.fillMaxWidth()) {
        state.newExpiry?.let { expiry ->
            Text("Valid till ${Dates.formatFull(expiry)}", style = MbType.BodyLarge, color = colors.textPrimary)
            state.periodStart?.let { start ->
                Text(
                    if (state.startsFresh) "Starts from ${Dates.formatShort(start)}" else "Continues from ${Dates.formatShort(start)}",
                    style = MbType.Caption,
                    color = colors.textSecondary,
                )
            }
        }
        Text(
            ScreenRules.describe(after),
            Modifier.padding(top = 4.dp),
            style = MbType.Label,
            color = when (after) {
                AfterPayment.FullyPaid -> colors.paid.content
                is AfterPayment.Remaining -> colors.dueSoon.content
                is AfterPayment.Extra -> colors.overdue.content
            },
        )
    }
}
