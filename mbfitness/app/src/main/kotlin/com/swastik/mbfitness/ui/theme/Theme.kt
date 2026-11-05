package com.swastik.mbfitness.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.swastik.mbfitness.R

/** Bundled, so it works offline. Devanagari falls back to the system font by itself. */
val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.W400),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.W500),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.W600),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.W700),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.W800),
)

/** The type scale from SPEC 10.1. One family; hierarchy comes from weight and size. */
object MbType {
    val Display = TextStyle(fontFamily = PlusJakartaSans, fontWeight = FontWeight.W800, fontSize = 26.sp, lineHeight = 32.sp)
    val Title = TextStyle(fontFamily = PlusJakartaSans, fontWeight = FontWeight.W700, fontSize = 22.sp, lineHeight = 28.sp)
    val BodyLarge = TextStyle(fontFamily = PlusJakartaSans, fontWeight = FontWeight.W600, fontSize = 17.sp, lineHeight = 24.sp)
    val Body = TextStyle(fontFamily = PlusJakartaSans, fontWeight = FontWeight.W400, fontSize = 16.sp, lineHeight = 22.sp)
    val Label = TextStyle(fontFamily = PlusJakartaSans, fontWeight = FontWeight.W600, fontSize = 15.sp, lineHeight = 20.sp)
    val Caption = TextStyle(fontFamily = PlusJakartaSans, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 18.sp)
    val SectionLabel = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.W700,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.em,
    )
}

private val MbTypography = Typography(
    headlineMedium = MbType.Display,
    titleLarge = MbType.Title,
    titleMedium = MbType.BodyLarge,
    bodyLarge = MbType.Body,
    bodyMedium = MbType.Body,
    bodySmall = MbType.Caption,
    labelLarge = MbType.Label,
    labelMedium = MbType.Label,
    labelSmall = MbType.SectionLabel,
)

object MbTheme {
    val colors: MbColors
        @Composable
        @ReadOnlyComposable
        get() = LocalMbColors.current
}

@Composable
fun MbTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkMbColors else LightMbColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.button,
            onPrimary = colors.onButton,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.button,
            onPrimary = colors.onButton,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger,
        )
    }
    CompositionLocalProvider(LocalMbColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = MbTypography, content = content)
    }
}
