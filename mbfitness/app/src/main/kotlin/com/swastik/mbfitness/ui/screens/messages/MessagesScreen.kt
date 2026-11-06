package com.swastik.mbfitness.ui.screens.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.App
import com.swastik.mbfitness.R
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.BannerTone
import com.swastik.mbfitness.ui.components.ConfirmDialog
import com.swastik.mbfitness.ui.components.EmptyState
import com.swastik.mbfitness.ui.components.LinkButton
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.MbBanner
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbSegmented
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.StatusChip
import com.swastik.mbfitness.ui.components.rememberSmsPermissionRequest
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

private fun emptyLine(tab: MessagesTab): String = when (tab) {
    MessagesTab.QUEUED -> "Nothing waiting to send."
    MessagesTab.SENT -> "No SMS sent yet."
    MessagesTab.FAILED -> "Nothing failed. Nice!"
}

/** SPEC 10.2 screen 11. */
@Composable
fun MessagesScreen(onBroadcast: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val viewModel = appViewModel { container ->
        MessagesViewModel(container.messages, container.members, container.dailyRunner, container.outboxKicker, container.settings, container.clock)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    var canSend by remember { mutableStateOf(app.canSendSms()) }
    var cancellingGroup by remember { mutableStateOf<MessageEntry.Group?>(null) }
    val requestPermission = rememberSmsPermissionRequest { canSend = app.canSendSms() }

    LifecycleResumeEffect(Unit) {
        canSend = app.canSendSms()
        viewModel.onResume()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Messages")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            if (!canSend) {
                item(key = "permission") {
                    MbBanner(
                        "SMS permission is off. Nothing can go until it's back on.",
                        BannerTone.DANGER,
                        actionLabel = "Turn on",
                        onAction = requestPermission,
                    )
                }
            }
            if (state.testMode) {
                item(key = "test") {
                    MbBanner("Test mode is on. Every SMS goes only to your own number.", BannerTone.INFO)
                }
            }
            item(key = "actions") {
                // The same height, even when one label wraps at a large font.
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(ListGap)) {
                    SecondaryButton(
                        "Broadcast",
                        onBroadcast,
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = R.drawable.ic_campaign,
                    )
                    SecondaryButton(
                        "Run reminders now",
                        { viewModel.onRunReminders { snackbar.show(it) } },
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = R.drawable.ic_send,
                    )
                }
            }
            item(key = "tabs") {
                MbSegmented(
                    MessagesTab.entries,
                    state.tab,
                    { tab ->
                        val count = state.counts[tab] ?: 0
                        if (count > 0) "${tab.label} $count" else tab.label
                    },
                    viewModel::onTabSelected,
                )
            }
            if (state.loaded && state.entries.isEmpty()) {
                item(key = "empty") { EmptyState(emptyLine(state.tab)) }
            }
            items(
                state.entries,
                key = { entry ->
                    when (entry) {
                        is MessageEntry.Single -> "m${entry.item.id}"
                        is MessageEntry.Group -> "b${entry.broadcastId}"
                    }
                },
            ) { entry ->
                when (entry) {
                    is MessageEntry.Single -> MessageCard(entry.item, viewModel)
                    is MessageEntry.Group -> GroupCard(entry, viewModel, onCancelGroup = { cancellingGroup = entry })
                }
            }
        }
    }

    RunPromptDialog(state.prompt, viewModel)
    cancellingGroup?.let { group ->
        val waiting = group.items.count { it.canCancel }
        ConfirmDialog(
            title = "Cancel this broadcast?",
            message = if (waiting == 1) "The 1 message still waiting won't go." else "The $waiting messages still waiting won't go.",
            confirmLabel = "Cancel them",
            danger = true,
            dismissLabel = "Keep",
            onConfirm = {
                cancellingGroup = null
                viewModel.cancelGroup(group.broadcastId) { snackbar.show(it) }
            },
            onDismiss = { cancellingGroup = null },
        )
    }
}

@Composable
private fun RunPromptDialog(prompt: RunPrompt?, viewModel: MessagesViewModel) {
    val snackbar = LocalSnackbar.current
    when (prompt) {
        null -> Unit
        is RunPrompt.QuietDay -> ConfirmDialog(
            title = "Today is a quiet day",
            message = "${prompt.reason}. Reminders normally wait for the next normal day. Send them today anyway?",
            confirmLabel = "Send anyway",
            dismissLabel = "Wait",
            onConfirm = { viewModel.onRunOnQuietDay { snackbar.show(it) } },
            onDismiss = viewModel::dismissPrompt,
        )
        is RunPrompt.Preview -> {
            val more = prompt.count - prompt.lines.size
            val message = buildString {
                append(prompt.lines.joinToString("\n"))
                if (more > 0) append("\nand $more more")
                prompt.heldNote?.let { append("\n\n").append(it) }
            }
            ConfirmDialog(
                title = if (prompt.count == 1) "Send 1 reminder?" else "Send ${prompt.count} reminders?",
                message = message,
                confirmLabel = "Send",
                onConfirm = { viewModel.onConfirmRun { snackbar.show(it) } },
                onDismiss = viewModel::dismissPrompt,
            )
        }
    }
}

@Composable
private fun MessageCard(item: MessageItem, viewModel: MessagesViewModel) {
    val colors = MbTheme.colors
    var open by remember(item.id) { mutableStateOf(false) }
    MbCard(Modifier.fillMaxWidth(), onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.who, Modifier.weight(1f), style = MbType.BodyLarge, color = colors.textPrimary)
            StatusChip(item.kind, colors.inactive)
        }
        Text(
            item.body,
            Modifier.padding(top = 4.dp),
            style = MbType.Body,
            color = colors.textSecondary,
            maxLines = if (open) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        StatusLine(item)
        Actions(item, viewModel)
    }
}

@Composable
private fun StatusLine(item: MessageItem) {
    val colors = MbTheme.colors
    Text(
        item.status,
        Modifier.padding(top = 4.dp),
        style = MbType.Caption,
        color = if (item.isFailed) colors.danger else colors.textSecondary,
    )
}

@Composable
private fun Actions(item: MessageItem, viewModel: MessagesViewModel) {
    val snackbar = LocalSnackbar.current
    if (!item.isFailed && !item.canCancel) return
    Row(horizontalArrangement = Arrangement.spacedBy(ListGap)) {
        if (item.isFailed) LinkButton("Retry", { viewModel.retry(item.id) { snackbar.show(it) } })
        if (item.canCancel) LinkButton("Cancel", { viewModel.cancel(item.id) }, color = MbTheme.colors.danger)
    }
}

@Composable
private fun GroupCard(group: MessageEntry.Group, viewModel: MessagesViewModel, onCancelGroup: () -> Unit) {
    val colors = MbTheme.colors
    val snackbar = LocalSnackbar.current
    val count = group.items.size
    MbCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (group.expanded) "Collapse" else "Expand") { viewModel.onToggleGroup(group.broadcastId) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (count == 1) "Broadcast · 1 person" else "Broadcast · $count people", style = MbType.BodyLarge, color = colors.textPrimary)
                Text(group.items.first().status, style = MbType.Caption, color = if (group.items.first().isFailed) colors.danger else colors.textSecondary)
            }
            Icon(
                painterResource(if (group.expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = null,
                tint = colors.textSecondary,
            )
        }
        Text(
            group.items.first().body,
            Modifier.padding(top = 4.dp),
            style = MbType.Body,
            color = colors.textSecondary,
            maxLines = COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ListGap)) {
            if (group.items.any { it.isFailed }) {
                LinkButton("Retry all", { viewModel.retryGroup(group.broadcastId) { snackbar.show(it) } })
            }
            if (group.items.any { it.canCancel }) {
                LinkButton("Cancel all", onCancelGroup, color = colors.danger)
            }
        }
        if (group.expanded) {
            group.items.forEach { item ->
                Column(Modifier.padding(top = 8.dp)) {
                    Text(item.who, style = MbType.Label, color = colors.textPrimary)
                    StatusLine(item)
                    Actions(item, viewModel)
                }
            }
        }
    }
}

private const val COLLAPSED_LINES = 3
