package com.swastik.mbfitness.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Every colour in the app comes from here or [StatusColors] (SPEC 10.1). */
object BrandColors {
    val BackgroundLight = Color(0xFFFAF7F2)
    val BackgroundDark = Color(0xFF141210)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceDark = Color(0xFF1F1C19)
    val BorderLight = Color(0xFFEEE9E3)
    val BorderDark = Color(0xFF38322C)

    val Header = Color(0xFF1C1917)
    val OnHeader = Color(0xFFFAFAF9)
    val OnHeaderMuted = Color(0xFFD6D3D1)
    val Greeting = Color(0xFFFB923C)

    val Accent = Color(0xFFE8590C)

    /** Darker than [Accent] so white text on it passes 4.5:1 (SPEC 10.1). */
    val Button = Color(0xFFC2410C)
    val OnButton = Color(0xFFFFFFFF)

    val TextPrimaryLight = Color(0xFF1C1917)
    val TextSecondaryLight = Color(0xFF57534E)
    val TextPrimaryDark = Color(0xFFFAFAF9)
    val TextSecondaryDark = Color(0xFFB5AFA8)

    val AvatarBackgroundLight = Color(0xFFFFEDD5)
    val AvatarTextLight = Color(0xFF9A3412)
    val AvatarBackgroundDark = Color(0xFF4A2A14)
    val AvatarTextDark = Color(0xFFFDBA74)

    val Danger = Color(0xFFB42318)
    val OnDanger = Color(0xFFFFFFFF)
}

@Immutable
data class ChipColors(val background: Color, val content: Color)

object StatusColors {
    val OverdueLight = ChipColors(Color(0xFFFEE4E2), Color(0xFFB42318))
    val DueSoonLight = ChipColors(Color(0xFFFEF0C7), Color(0xFF93370D))
    val PaidLight = ChipColors(Color(0xFFDCFCE7), Color(0xFF166534))
    val InactiveLight = ChipColors(Color(0xFFE7E5E4), Color(0xFF44403C))

    val OverdueDark = ChipColors(Color(0xFF4A1D1A), Color(0xFFFDA29B))
    val DueSoonDark = ChipColors(Color(0xFF4A2F0B), Color(0xFFFEC84B))
    val PaidDark = ChipColors(Color(0xFF0F3D22), Color(0xFF86EFAC))
    val InactiveDark = ChipColors(Color(0xFF3A3632), Color(0xFFD6D3D1))
}

/** The colours for the current light or dark mode. Read them through MbTheme.colors. */
@Immutable
data class MbColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val avatarBackground: Color,
    val avatarText: Color,
    val overdue: ChipColors,
    val dueSoon: ChipColors,
    val paid: ChipColors,
    val inactive: ChipColors,
) {
    val header get() = BrandColors.Header
    val onHeader get() = BrandColors.OnHeader
    val onHeaderMuted get() = BrandColors.OnHeaderMuted
    val greeting get() = BrandColors.Greeting
    val accent get() = BrandColors.Accent
    val button get() = BrandColors.Button
    val onButton get() = BrandColors.OnButton
    val danger get() = if (isDark) StatusColors.OverdueDark.content else BrandColors.Danger
}

val LightMbColors = MbColors(
    isDark = false,
    background = BrandColors.BackgroundLight,
    surface = BrandColors.SurfaceLight,
    border = BrandColors.BorderLight,
    textPrimary = BrandColors.TextPrimaryLight,
    textSecondary = BrandColors.TextSecondaryLight,
    avatarBackground = BrandColors.AvatarBackgroundLight,
    avatarText = BrandColors.AvatarTextLight,
    overdue = StatusColors.OverdueLight,
    dueSoon = StatusColors.DueSoonLight,
    paid = StatusColors.PaidLight,
    inactive = StatusColors.InactiveLight,
)

val DarkMbColors = MbColors(
    isDark = true,
    background = BrandColors.BackgroundDark,
    surface = BrandColors.SurfaceDark,
    border = BrandColors.BorderDark,
    textPrimary = BrandColors.TextPrimaryDark,
    textSecondary = BrandColors.TextSecondaryDark,
    avatarBackground = BrandColors.AvatarBackgroundDark,
    avatarText = BrandColors.AvatarTextDark,
    overdue = StatusColors.OverdueDark,
    dueSoon = StatusColors.DueSoonDark,
    paid = StatusColors.PaidDark,
    inactive = StatusColors.InactiveDark,
)

val LocalMbColors = staticCompositionLocalOf { LightMbColors }
