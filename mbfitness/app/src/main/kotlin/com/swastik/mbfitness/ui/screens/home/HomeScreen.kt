package com.swastik.mbfitness.ui.screens.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.R
import com.swastik.mbfitness.domain.MemberState
import com.swastik.mbfitness.domain.MemberTab
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.AdvanceBadge
import com.swastik.mbfitness.ui.components.Avatar
import com.swastik.mbfitness.ui.components.BannerTone
import com.swastik.mbfitness.ui.components.ConfirmDialog
import com.swastik.mbfitness.ui.components.DueBadge
import com.swastik.mbfitness.ui.components.EmptyState
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.MbBanner
import com.swastik.mbfitness.ui.components.MbIconButton
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.StatusChip
import com.swastik.mbfitness.ui.theme.ChipColors
import com.swastik.mbfitness.ui.theme.MbColors
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

fun tabLabel(tab: MemberTab): String = when (tab) {
    MemberTab.OVERDUE -> "Overdue"
    MemberTab.DUE_SOON -> "Due soon"
    MemberTab.PAID -> "Paid"
    MemberTab.FROZEN_LEFT -> "Frozen/Left"
}

fun statusLabel(tab: MemberTab, state: MemberState): String = when (tab) {
    MemberTab.FROZEN_LEFT -> if (state == MemberState.FROZEN) "Frozen" else "Left"
    else -> tabLabel(tab)
}

fun statusColors(tab: MemberTab, colors: MbColors): ChipColors = when (tab) {
    MemberTab.OVERDUE -> colors.overdue
    MemberTab.DUE_SOON -> colors.dueSoon
    MemberTab.PAID -> colors.paid
    MemberTab.FROZEN_LEFT -> colors.inactive
}

private fun emptyLine(tab: MemberTab): String = when (tab) {
    MemberTab.OVERDUE -> "No overdue members. Nice!"
    MemberTab.DUE_SOON -> "Nobody is due soon."
    MemberTab.PAID -> "No paid-up members yet."
    MemberTab.FROZEN_LEFT -> "Nobody is frozen or has left."
}

/** SPEC 10.2 screen 3. */
@Composable
fun HomeScreen(onMemberClick: (Long) -> Unit, onAddMember: () -> Unit) {
    val viewModel = appViewModel { app -> HomeViewModel(app.members, app.settings, app.clock) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmLeft by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbar.current
    // Keeps Add member reachable while a snackbar shows, for adding several members in a row.
    val fabLift by animateDpAsState(
        when {
            snackbar.current == null -> 0.dp
            snackbar.current?.actions.isNullOrEmpty() -> 72.dp
            else -> 120.dp
        },
        tween(200),
        label = "fabLift",
    )

    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.selection.isEmpty()) {
                HomeHeader(state.settings, state.today, state.testMode)
            } else {
                MbTopBar(
                    "${state.selection.size} selected",
                    onBack = viewModel::clearSelection,
                    actions = {
                        MbIconButton(
                            R.drawable.ic_logout,
                            "Mark selected as Left",
                            { confirmLeft = true },
                            tint = MbTheme.colors.onHeader,
                        )
                    },
                )
            }
            MemberList(state, viewModel, onMemberClick)
        }
        if (state.selection.isEmpty()) {
            ExtendedFloatingActionButton(
                onClick = onAddMember,
                // This button hides its label from TalkBack, so it needs its own description.
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(start = ScreenPadding, end = ScreenPadding, top = ScreenPadding, bottom = ScreenPadding + fabLift)
                    .semantics { contentDescription = "Add member" },
                containerColor = MbTheme.colors.button,
                contentColor = MbTheme.colors.onButton,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text("Add member", style = MbType.Label) },
            )
        }
    }

    if (confirmLeft) {
        val count = state.selection.size
        ConfirmDialog(
            title = "Mark as Left?",
            message = "$count ${if (count == 1) "member" else "members"} will stop getting reminders. Their history stays.",
            confirmLabel = "Mark as Left",
            onConfirm = {
                confirmLeft = false
                viewModel.markSelectedAsLeft()
            },
            onDismiss = { confirmLeft = false },
        )
    }
}

@Composable
private fun MemberList(state: HomeUiState, viewModel: HomeViewModel, onMemberClick: (Long) -> Unit) {
    val selecting = state.selection.isNotEmpty()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = ScreenPadding, bottom = FabClearance),
        verticalArrangement = Arrangement.spacedBy(ListGap),
    ) {
        item(key = "tiles", contentType = "tiles") {
            StatTiles(state.collectedThisMonth, state.pending, state.activeCount)
        }
        item(key = "search", contentType = "search") {
            MbTextField(
                state.query,
                viewModel::onQueryChange,
                "Search name or number",
                leadingIcon = R.drawable.ic_search,
                trailing = if (state.query.isNotEmpty()) {
                    { MbIconButton(R.drawable.ic_close, "Clear search", { viewModel.onQueryChange("") }) }
                } else {
                    null
                },
            )
        }
        (state.banner as? HomeBanner.LongOverdue)?.takeIf { !selecting }?.let { banner ->
            item(key = "banner", contentType = "banner") {
                val count = banner.memberIds.size
                MbBanner(
                    "$count ${if (count == 1) "member is" else "members are"} 60+ days overdue. Mark as Left?",
                    BannerTone.WARNING,
                    actionLabel = "Review",
                    onAction = { viewModel.onReviewLongOverdue(banner.memberIds) },
                )
            }
        }
        if (state.query.isBlank()) {
            item(key = "tabs", contentType = "tabs") {
                TabRow(state, viewModel::onTabSelected)
            }
        }
        if (state.loaded && state.items.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyState(
                    when {
                        state.query.isNotBlank() -> "Nobody matches that."
                        state.totalMembers == 0 -> "No members yet. Add your first one."
                        else -> emptyLine(state.tab)
                    },
                )
            }
        }
        items(state.items, key = { it.id }, contentType = { "member" }) { item ->
            MemberRowCard(
                item,
                selected = item.id in state.selection,
                onClick = { if (selecting) viewModel.onToggleSelected(item.id) else onMemberClick(item.id) },
                onLongClick = { viewModel.onLongPress(item.id) },
            )
        }
    }
}

@Composable
private fun TabRow(state: HomeUiState, onSelect: (MemberTab) -> Unit) {
    val colors = MbTheme.colors
    // Equal widths, so all four counts are always on screen without scrolling.
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MemberTab.entries.forEach { tab ->
            val selected = state.tab == tab
            val content = if (selected) colors.onButton else colors.inactive.content
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) colors.button else colors.inactive.background)
                    .selectable(selected, role = Role.Tab) { onSelect(tab) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text((state.counts[tab] ?: 0).toString(), style = MbType.BodyLarge, color = content)
                Text(tabLabel(tab), style = MbType.Caption, color = content, textAlign = TextAlign.Center)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemberRowCard(item: MemberListItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MbTheme.colors
    Surface(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .semantics {
                onClick(label = if (selected) "Unselect" else "Open") {
                    onClick()
                    true
                }
                onLongClick(label = "Select") {
                    onLongClick()
                    true
                }
            },
        shape = RoundedCornerShape(18.dp),
        color = if (selected) colors.avatarBackground else colors.surface,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.border),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_check_circle), contentDescription = "Selected", tint = colors.accent)
                }
            } else {
                Avatar(item.name)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MbType.BodyLarge, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle, style = MbType.Caption, color = colors.textSecondary)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusChip(statusLabel(item.tab, item.state), statusColors(item.tab, colors))
                if (item.due > 0) DueBadge(item.due)
                if (item.advance > 0) AdvanceBadge(item.advance)
            }
        }
    }
}

/** Keeps the last row clear of the Add member button. */
private val FabClearance = 96.dp
