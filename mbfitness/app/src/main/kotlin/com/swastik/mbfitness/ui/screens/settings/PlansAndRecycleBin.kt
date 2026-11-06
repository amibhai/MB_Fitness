package com.swastik.mbfitness.ui.screens.settings

import com.swastik.mbfitness.launchSafely
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.App
import com.swastik.mbfitness.R
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.PlanEntity
import com.swastik.mbfitness.data.RestoreResult
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.domain.ScreenRules
import com.swastik.mbfitness.ui.components.EmptyState
import com.swastik.mbfitness.ui.components.LinkButton
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.StatusChip
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

private const val MIN_MONTHS = 1
private const val MAX_MONTHS = 24

/** SPEC 10.2 screen 15. Retired plans stay on old payments, so plans are never deleted. */
@Composable
fun PlansScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val members = (context.applicationContext as App).members
    val plans by members.observePlans().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val colors = MbTheme.colors
    var editing by remember { mutableStateOf<PlanEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            MbTopBar("Plans and prices", onBack = onBack)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = ScreenPadding, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(ListGap),
            ) {
                items(plans, key = { it.id }) { plan ->
                    MbCard(Modifier.fillMaxWidth(), onClick = { editing = plan }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plan.name, style = MbType.BodyLarge, color = colors.textPrimary)
                                val months = if (plan.months == 1) "1 month" else "${plan.months} months"
                                Text("$months · ${Money.format(plan.price)}", style = MbType.Caption, color = colors.textSecondary)
                            }
                            if (!plan.isActive) StatusChip("Retired", colors.inactive)
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            // This button hides its label from TalkBack, so it needs its own description.
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(ScreenPadding)
                .semantics { contentDescription = "Add plan" },
            containerColor = colors.button,
            contentColor = colors.onButton,
            icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            text = { Text("Add plan", style = MbType.Label) },
        )
    }

    if (adding) {
        PlanDialog(
            plan = null,
            onSave = { name, months, price ->
                adding = false
                scope.launchSafely(context) { members.addPlan(name, months, price) }
            },
            onToggleActive = {},
            onDismiss = { adding = false },
        )
    }
    editing?.let { plan ->
        PlanDialog(
            plan = plan,
            onSave = { name, months, price ->
                editing = null
                scope.launchSafely(context) { members.updatePlan(plan.copy(name = name.trim(), months = months, price = price)) }
            },
            onToggleActive = {
                editing = null
                scope.launchSafely(context) { members.setPlanActive(plan.id, !plan.isActive) }
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun PlanDialog(
    plan: PlanEntity?,
    onSave: (String, Int, Long) -> Unit,
    onToggleActive: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MbTheme.colors
    var name by remember { mutableStateOf(plan?.name.orEmpty()) }
    var months by remember { mutableStateOf(plan?.months?.toString().orEmpty()) }
    var price by remember { mutableStateOf(plan?.price?.toString().orEmpty()) }
    val monthsValue = months.toIntOrNull()?.takeIf { it in MIN_MONTHS..MAX_MONTHS }
    val priceValue = ScreenRules.parseAmount(price)
    val valid = name.isNotBlank() && monthsValue != null && priceValue != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(if (plan == null) "Add plan" else "Edit plan", style = MbType.Title, color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MbTextField(name, { name = it }, "Name", capitalization = KeyboardCapitalization.Sentences)
                MbTextField(
                    months,
                    { months = it.filter(Char::isDigit).take(2) },
                    "Months ($MIN_MONTHS to $MAX_MONTHS)",
                    keyboardType = KeyboardType.Number,
                    error = if (months.isNotEmpty() && monthsValue == null) "Must be $MIN_MONTHS to $MAX_MONTHS" else null,
                )
                MbTextField(price, { price = ScreenRules.amountInput(it) }, "Price (₹)", keyboardType = KeyboardType.Number)
                if (plan != null) {
                    Text("Changing a plan never changes old payments.", style = MbType.Caption, color = colors.textSecondary)
                    SecondaryButton(if (plan.isActive) "Retire plan" else "Bring plan back", onToggleActive, Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (monthsValue != null && priceValue != null) onSave(name, monthsValue, priceValue) },
                enabled = valid,
            ) { Text("Save", style = MbType.Label) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", style = MbType.Label, color = colors.textSecondary) } },
    )
}

/** SPEC 10.2 screen 16. */
@Composable
fun RecycleBinScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val members = app.members
    val rows by members.observeRecycleBin().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val colors = MbTheme.colors

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Recycle bin", onBack = onBack)
        if (rows.isEmpty()) {
            EmptyState("The recycle bin is empty.")
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            items(rows, key = { it.id }) { row ->
                val daysLeft = ScreenRules.recycleBinDaysLeft(row.deletedAt ?: 0, app.clock.nowMillis(), MemberRepository.RECYCLE_BIN_DAYS)
                MbCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.name, style = MbType.BodyLarge, color = colors.textPrimary)
                            Text(
                                if (daysLeft == 1L) "1 day left" else "$daysLeft days left",
                                style = MbType.Caption,
                                color = colors.textSecondary,
                            )
                        }
                        LinkButton(
                            "Restore",
                            {
                                scope.launchSafely(context) {
                                    when (val result = members.restoreMember(row.id)) {
                                        RestoreResult.Restored -> snackbar.show("${row.name} restored")
                                        is RestoreResult.PhoneTaken -> snackbar.show("Can't restore: ${result.existing.name} has this number now")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
