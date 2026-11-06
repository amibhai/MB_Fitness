package com.swastik.mbfitness.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swastik.mbfitness.R
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.ui.theme.ChipColors
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType

/** The shared building blocks from SPEC 10.1. Touch targets are at least 48 dp everywhere. */

val CardShape = RoundedCornerShape(18.dp)
private val ButtonShape = RoundedCornerShape(14.dp)
private val ChipShape = RoundedCornerShape(999.dp)

val ScreenPadding = 16.dp
val ListGap = 8.dp

@Composable
fun MbCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MbTheme.colors
    val border = BorderStroke(1.dp, colors.border)
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(contentPadding), content = content)
    }
    if (onClick != null) {
        Surface(onClick, modifier, shape = CardShape, color = colors.surface, border = border, shadowElevation = 1.dp, content = inner)
    } else {
        Surface(modifier, shape = CardShape, color = colors.surface, border = border, shadowElevation = 1.dp, content = inner)
    }
}

/** The main action on a screen: 56 dp tall, and it grows if the text wraps at a large font. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val colors = MbTheme.colors
    Button(
        onClick,
        modifier.heightIn(min = 56.dp),
        enabled = enabled,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.button, contentColor = colors.onButton),
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val colors = MbTheme.colors
    OutlinedButton(
        onClick,
        modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = ButtonShape,
        border = BorderStroke(1.dp, colors.textSecondary),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        ButtonContent(text, icon)
    }
}

/** Destructive actions are red and always confirm first (SPEC 10.2). */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick,
        modifier.heightIn(min = 52.dp),
        enabled = enabled,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = com.swastik.mbfitness.ui.theme.BrandColors.Danger,
            contentColor = com.swastik.mbfitness.ui.theme.BrandColors.OnDanger,
        ),
    ) {
        ButtonContent(text, null)
    }
}

@Composable
private fun RowScope.ButtonContent(text: String, @DrawableRes icon: Int?) {
    if (icon != null) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MbType.Label, textAlign = TextAlign.Center)
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MbTheme.colors.button) {
    TextButton(onClick, modifier.heightIn(min = 48.dp)) {
        Text(text, style = MbType.Label, color = if (MbTheme.colors.isDark) MbTheme.colors.greeting else color)
    }
}

/** An icon button. [description] is required, because TalkBack reads it (SPEC 10.1). */
@Composable
fun MbIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MbTheme.colors.textPrimary,
) {
    IconButton(onClick, modifier.size(48.dp)) {
        Icon(painterResource(icon), description, tint = tint)
    }
}

/** The dark bar at the top of every screen. It draws behind the status bar. */
@Composable
fun MbTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = MbTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.header)
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            MbIconButton(R.drawable.ic_arrow_back, "Back", onBack, tint = colors.onHeader)
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .semantics { heading() },
            style = MbType.Title,
            color = colors.onHeader,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** A coloured pill. Used for status, and for anything else with a tone. */
@Composable
fun StatusChip(label: String, chipColors: ChipColors, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier
            .clip(ChipShape)
            .background(chipColors.background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MbType.Caption.copy(fontWeight = MbType.Label.fontWeight),
        color = chipColors.content,
        maxLines = 1,
    )
}

/** "₹700 due", read aloud as "700 rupees due". */
@Composable
fun DueBadge(amount: Long, modifier: Modifier = Modifier) {
    val spoken = "${Money.spoken(amount)} due"
    StatusChip(
        "${Money.format(amount)} due",
        MbTheme.colors.overdue,
        modifier.clearAndSetSemantics { contentDescription = spoken },
    )
}

/** "₹200 advance", read aloud as "200 rupees advance". */
@Composable
fun AdvanceBadge(amount: Long, modifier: Modifier = Modifier) {
    val spoken = "${Money.spoken(amount)} advance"
    StatusChip(
        "${Money.format(amount)} advance",
        MbTheme.colors.paid,
        modifier.clearAndSetSemantics { contentDescription = spoken },
    )
}

/** A number on Home. [spoken] overrides what TalkBack reads, for money. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, spoken: String? = null) {
    val colors = MbTheme.colors
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.border)) {
        Column(
            Modifier
                .padding(12.dp)
                .clearAndSetSemantics { contentDescription = "$label: ${spoken ?: value}" },
        ) {
            Text(value, style = MbType.BodyLarge, color = colors.textPrimary)
            Text(label, style = MbType.Caption, color = colors.textSecondary)
        }
    }
}

/** Initials on a soft circle. Decorative, because the name is always beside it. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier) {
    val colors = MbTheme.colors
    val words = name.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    val initials = words.take(2).joinToString("") { it.first().uppercase() }
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.avatarBackground)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = MbType.Label, color = colors.avatarText)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.semantics { heading() },
        style = MbType.SectionLabel,
        color = MbTheme.colors.textSecondary,
    )
}

/** For a screen with no top bar. The status bar's icons are white, and need something dark behind them. */
@Composable
fun DarkStatusBarStrip() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MbTheme.colors.header)
            .statusBarsPadding(),
    )
}

enum class BannerTone { DANGER, WARNING, INFO }

/** One banner at a time on Home, chosen by priority (SPEC 10.2 screen 3). */
@Composable
fun MbBanner(
    message: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MbTheme.colors
    val chip = when (tone) {
        BannerTone.DANGER -> colors.overdue
        BannerTone.WARNING -> colors.dueSoon
        BannerTone.INFO -> colors.inactive
    }
    val icon = when (tone) {
        BannerTone.DANGER -> R.drawable.ic_error
        BannerTone.WARNING -> R.drawable.ic_warning
        BannerTone.INFO -> R.drawable.ic_info
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = chip.background) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = chip.content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, Modifier.weight(1f), style = MbType.Caption.copy(fontWeight = MbType.Label.fontWeight), color = chip.content)
            if (actionLabel != null && onAction != null) {
                TextButton(onAction, Modifier.heightIn(min = 48.dp)) {
                    Text(actionLabel, style = MbType.Label, color = chip.content)
                }
            }
        }
    }
}

/** A friendly line and one action (SPEC 10.2). */
@Composable
fun EmptyState(line: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(line, style = MbType.Body, color = MbTheme.colors.textSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            SecondaryButton(actionLabel, onAction)
        }
    }
}
