package com.swastik.mbfitness.ui.screens.member

import com.swastik.mbfitness.launchSafely
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.R
import com.swastik.mbfitness.domain.Aadhaar
import com.swastik.mbfitness.domain.Dates
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.AadhaarGrouping
import com.swastik.mbfitness.ui.components.ChoiceCard
import com.swastik.mbfitness.ui.components.ConfirmDialog
import com.swastik.mbfitness.ui.components.DateField
import com.swastik.mbfitness.ui.components.LinkButton
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.MbCheckbox
import com.swastik.mbfitness.ui.components.MbSegmented
import com.swastik.mbfitness.ui.components.MbSwitchRow
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.PrimaryButton
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.components.SecureWindow
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import kotlinx.coroutines.launch

/** SPEC 10.2 screen 7. [memberId] is null when adding. */
@Composable
fun MemberFormScreen(
    memberId: Long?,
    onBack: () -> Unit,
    onSaved: (MemberFormResult.Saved) -> Unit,
    onOpenMember: (Long) -> Unit,
) {
    val viewModel = appViewModel(key = "member-form-$memberId") { app ->
        MemberFormViewModel(memberId, app.members, app.settings, app.clock)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MbTheme.colors
    val pickContact = rememberLauncherForActivityResult(PickPhoneContact()) { uri ->
        if (uri != null) {
            scope.launchSafely(context) { readPickedContact(context, uri)?.let(viewModel::onContactPicked) }
        }
    }

    (state.result as? MemberFormResult.Saved)?.let { saved ->
        androidx.compose.runtime.LaunchedEffect(saved) { onSaved(saved) }
    }

    Column(Modifier.fillMaxSize()) {
        MbTopBar(if (state.isEdit) "Edit member" else "Add member", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.isEdit) {
                SecondaryButton("Pick from contacts", { pickContact.launch(Unit) }, Modifier.fillMaxWidth(), icon = R.drawable.ic_contacts)
            }
            MbTextField(
                state.name,
                viewModel::onNameChange,
                "Name",
                error = state.nameError,
                capitalization = KeyboardCapitalization.Words,
            )
            MbTextField(
                state.phone,
                viewModel::onPhoneChange,
                "Phone number",
                error = state.phoneError,
                keyboardType = KeyboardType.Phone,
            )
            state.phoneOwner?.let { owner ->
                LinkButton("Open ${owner.name}", { onOpenMember(owner.id) })
            }

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
            DateField("Join date", state.joinDate, { day -> day?.let(viewModel::onJoinDateChange) })
            if (state.joiningPaymentMoves) {
                Text("Their first payment moves to this date too.", style = MbType.Caption, color = colors.textSecondary)
            }

            if (!state.isEdit) PaidTodaySection(state, viewModel)

            DateField("Date of birth (optional)", state.dob, viewModel::onDobChange, allowClear = true)
            MbSwitchRow(state.isMinor, viewModel::onMinorChange, "Under 18")
            if (state.isMinor) {
                MbTextField(
                    state.guardianName,
                    viewModel::onGuardianNameChange,
                    "Guardian's name",
                    capitalization = KeyboardCapitalization.Words,
                )
                MbTextField(
                    state.guardianPhone,
                    viewModel::onGuardianPhoneChange,
                    "Guardian's phone",
                    error = state.guardianPhoneError,
                    keyboardType = KeyboardType.Phone,
                )
            }

            AadhaarSection(state, viewModel)

            if (!state.isEdit) {
                MbCheckbox(state.smsConsent, viewModel::onSmsConsentChange, "Member agrees to get fee reminders by SMS")
                if (!state.smsConsent) {
                    Text("No reminders will be sent to this member", style = MbType.Caption, color = colors.textSecondary)
                }
            }
            MbTextField(
                state.notes,
                viewModel::onNotesChange,
                "Notes (optional)",
                singleLine = false,
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrect = true,
                imeAction = ImeAction.Default,
            )
            PrimaryButton("Save", { viewModel.save() }, Modifier.fillMaxWidth(), enabled = state.loaded && !state.saving)
        }
    }

    state.duplicateOwner?.let { owner ->
        ConfirmDialog(
            title = "Same Aadhaar number",
            message = "This number is already on ${owner.name}. Check the digits, or save it anyway.",
            confirmLabel = "Save anyway",
            onConfirm = { viewModel.save(ignoreDuplicateAadhaar = true) },
            onDismiss = viewModel::onDuplicateDismissed,
            dismissLabel = "Check it",
        )
    }
}

/** Money paid at joining. It's dated the join date unless the payment date is changed. */
@Composable
private fun PaidTodaySection(state: MemberFormUiState, viewModel: MemberFormViewModel) {
    val colors = MbTheme.colors
    SectionLabel("Payment at joining")
    MbSegmented(
        PaidToday.entries,
        state.paidToday,
        label = {
            when (it) {
                PaidToday.FULL -> "Full"
                PaidToday.PART -> "Part"
                PaidToday.NOT_YET -> "Not yet"
            }
        },
        onSelect = viewModel::onPaidTodayChange,
    )
    if (state.paidToday == PaidToday.PART) {
        MbTextField(state.partAmount, viewModel::onPartAmountChange, "Amount received (₹)", keyboardType = KeyboardType.Number)
    }
    if (state.paidToday != PaidToday.NOT_YET) {
        MbSegmented(PaymentMode.entries, state.mode, PaymentMode::displayName, viewModel::onModeSelected)
        DateField("Payment date", state.paymentDate, { day -> day?.let(viewModel::onPaymentDateChange) }, latest = state.today)
    }
    state.validTill?.let { day ->
        Text("Valid till ${Dates.formatFull(day)}", style = MbType.Label, color = colors.textPrimary)
        if (state.validTillPassed) {
            Text(
                "That's already past. If they paid more recently, change the payment date.",
                style = MbType.Caption,
                color = colors.danger,
            )
        }
    }
}

/** Optional, with its own consent tick. Screenshots are blocked once digits are typed. */
@Composable
private fun AadhaarSection(state: MemberFormUiState, viewModel: MemberFormViewModel) {
    val colors = MbTheme.colors
    SectionLabel("Aadhaar")
    if (!state.showAadhaarField) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Aadhaar.masked(state.existingAadhaarLast4.orEmpty()),
                Modifier.weight(1f),
                style = MbType.BodyLarge,
                color = colors.textPrimary,
            )
            LinkButton("Change", viewModel::onChangeAadhaar)
        }
        return
    }
    if (state.aadhaarDigits.isNotEmpty()) SecureWindow()
    MbTextField(
        state.aadhaarDigits,
        viewModel::onAadhaarChange,
        "Aadhaar number (optional)",
        error = state.aadhaarError,
        keyboardType = KeyboardType.NumberPassword,
        visualTransformation = AadhaarGrouping,
    )
    if (state.showAadhaarConsent) {
        val who = if (state.isMinor) "Guardian" else "Member"
        MbCheckbox(
            state.aadhaarConsent,
            viewModel::onAadhaarConsentChange,
            "$who agreed to share Aadhaar for gym records. It is optional.",
        )
        state.aadhaarConsentError?.let { Text(it, style = MbType.Caption, color = colors.danger) }
    }
}
