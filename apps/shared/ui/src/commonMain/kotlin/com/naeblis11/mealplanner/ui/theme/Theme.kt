package com.naeblis11.mealplanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One set of DESIGN.md's colour tokens: [Light] is its `colors`, [Dark] its `dark-colors` (owner, 2026-10-12).
 * The names mean the same in both: [paper] is the ground, [ink] the text on it, [accent] the one action colour.
 */
@Immutable
class MealPalette(
    val ink: Color,
    val paper: Color,
    val paperAlt: Color,
    val line: Color,
    val lineSoft: Color,
    val muted: Color,
    val muted2: Color,
    val accent: Color,
    val accentHover: Color,
    val accentBright: Color,
    val accentTint: Color,
    val danger: Color,
    val dangerTint: Color,
    val dangerHover: Color,
    val breakfastTint: Color,
    val breakfastInk: Color,
    val lunchTint: Color,
    val lunchInk: Color,
    val dinnerTint: Color,
    val dinnerInk: Color,
    val isDark: Boolean,
) {
    companion object {
        val Light = MealPalette(
            ink = Color(0xFF16211B),
            paper = Color(0xFFFFFFFF),
            paperAlt = Color(0xFFF4F7F5),
            line = Color(0xFFE2E8E3),
            lineSoft = Color(0xFFEDF1EE),
            muted = Color(0xFF5C6B62),
            muted2 = Color(0xFF8A978F),
            accent = Color(0xFF1C7A4D),
            accentHover = Color(0xFF145C3A),
            accentBright = Color(0xFF2F9463),
            accentTint = Color(0xFFE4F5EC),
            danger = Color(0xFFB3261E),
            dangerTint = Color(0xFFFBE9E7),
            dangerHover = Color(0xFF8F1E18),
            breakfastTint = Color(0xFFEAF5E8),
            breakfastInk = Color(0xFF4C7A3C),
            lunchTint = Color(0xFFE8F0F8),
            lunchInk = Color(0xFF3C6690),
            dinnerTint = Color(0xFFFBEEE0),
            dinnerInk = Color(0xFFA86A2C),
            isDark = false,
        )

        /**
         * The same kitchen after dark: a deep green-black ground, light ink, and the accent lifted so it reads on it.
         * [accentHover] and [dangerHover] go lighter rather than deeper, because they are text on the tints.
         */
        val Dark = MealPalette(
            ink = Color(0xFFE3EBE5),
            paper = Color(0xFF121A16),
            paperAlt = Color(0xFF1A241F),
            line = Color(0xFF2E3A33),
            lineSoft = Color(0xFF222C26),
            muted = Color(0xFFA2B1A8),
            muted2 = Color(0xFF7C8B82),
            accent = Color(0xFF4CB884),
            accentHover = Color(0xFF8AD6AD),
            accentBright = Color(0xFF62CC97),
            accentTint = Color(0xFF1C3A2B),
            danger = Color(0xFFF08A80),
            dangerTint = Color(0xFF3B1F1C),
            dangerHover = Color(0xFFFFB4AB),
            breakfastTint = Color(0xFF22331E),
            breakfastInk = Color(0xFF9FCB8B),
            lunchTint = Color(0xFF1C2836),
            lunchInk = Color(0xFF97BBE0),
            dinnerTint = Color(0xFF3A2A19),
            dinnerInk = Color(0xFFE6AB6A),
            isDark = true,
        )
    }
}

/** The palette [MealPlannerTheme] chose; [MealColors] reads it. */
val LocalMealPalette = staticCompositionLocalOf { MealPalette.Light }

/**
 * DESIGN.md's tokens, as the current theme has them (light or dark). Forest green is the only action colour; red is
 * only for destructive or "needs attention". Read in composition; outside it, use [MealPalette.Light] or [MealPalette.Dark].
 */
object MealColors {
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.ink
    val Paper: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.paper
    val PaperAlt: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.paperAlt
    val Line: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.line
    val LineSoft: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.lineSoft
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.muted
    val Muted2: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.muted2
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.accent
    val AccentHover: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.accentHover
    val AccentBright: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.accentBright
    val AccentTint: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.accentTint
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.danger
    val DangerTint: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.dangerTint
    val DangerHover: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.dangerHover

    // The meal-plan slot headers only: DESIGN.md's one exception to the single-accent rule.
    val BreakfastTint: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.breakfastTint
    val BreakfastInk: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.breakfastInk
    val LunchTint: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.lunchTint
    val LunchInk: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.lunchInk
    val DinnerTint: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.dinnerTint
    val DinnerInk: Color @Composable @ReadOnlyComposable get() = LocalMealPalette.current.dinnerInk
}

/** DESIGN.md's spacing tokens (rem x 16 dp): between rows, between sections, and around a band of content. */
object MealSpacing {
    val Row = 11.2.dp
    val Section = 20.dp
    val Band = 24.dp
}

/**
 * Material's colour roles from one of DESIGN.md's palettes. The light scheme is exactly the one the app has always
 * had; the dark one also maps Material's tonal surfaces (menus, dialogs, the tab bar) onto the palette's two grounds,
 * since Material's own dark surfaces are a purple-grey that DESIGN.md has no place for.
 */
fun colorSchemeFor(p: MealPalette): ColorScheme {
    if (!p.isDark) {
        return lightColorScheme(
            primary = p.accent,
            onPrimary = p.paper,
            primaryContainer = p.accentTint,
            onPrimaryContainer = p.accentHover,
            secondary = p.muted,
            onSecondary = p.paper,
            background = p.paper,
            onBackground = p.ink,
            surface = p.paper,
            onSurface = p.ink,
            surfaceVariant = p.paperAlt,
            onSurfaceVariant = p.muted,
            outline = p.line,
            outlineVariant = p.lineSoft,
            error = p.danger,
            onError = p.paper,
            errorContainer = p.dangerTint,
            onErrorContainer = p.danger,
        )
    }
    return darkColorScheme(
        primary = p.accent,
        onPrimary = p.paper,
        primaryContainer = p.accentTint,
        onPrimaryContainer = p.accentHover,
        secondary = p.muted,
        onSecondary = p.paper,
        secondaryContainer = p.accentTint,
        onSecondaryContainer = p.accentHover,
        tertiary = p.accentBright,
        onTertiary = p.paper,
        background = p.paper,
        onBackground = p.ink,
        surface = p.paper,
        onSurface = p.ink,
        surfaceVariant = p.paperAlt,
        onSurfaceVariant = p.muted,
        surfaceTint = p.paper,
        surfaceContainerLowest = p.paper,
        surfaceContainerLow = p.paper,
        surfaceContainer = p.paperAlt,
        surfaceContainerHigh = p.paperAlt,
        surfaceContainerHighest = p.paperAlt,
        surfaceBright = p.paperAlt,
        surfaceDim = p.paper,
        inverseSurface = p.ink,
        inverseOnSurface = p.paper,
        inversePrimary = p.accentHover,
        outline = p.line,
        outlineVariant = p.lineSoft,
        error = p.danger,
        onError = p.paper,
        errorContainer = p.dangerTint,
        onErrorContainer = p.dangerHover,
        scrim = Color.Black,
    )
}

private val MealShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
)

private val Base = Typography()

// One face (the system font); hierarchy from size, weight and tight tracking.
private val MealTypography = Typography(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Bold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Light, dark, or whichever the system is set to (owner, 2026-10-12; Settings > Appearance). */
@Composable
fun MealPlannerTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) MealPalette.Dark else MealPalette.Light
    CompositionLocalProvider(LocalMealPalette provides palette) {
        MaterialTheme(colorScheme = colorSchemeFor(palette), shapes = MealShapes, typography = MealTypography, content = content)
    }
}
