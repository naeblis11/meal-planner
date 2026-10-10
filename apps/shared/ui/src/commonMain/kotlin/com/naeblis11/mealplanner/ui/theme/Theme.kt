package com.naeblis11.mealplanner.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** DESIGN.md's tokens. Forest green is the only action colour; red is only for destructive or "needs attention". */
object MealColors {
    val Ink = Color(0xFF16211B)
    val Paper = Color(0xFFFFFFFF)
    val PaperAlt = Color(0xFFF4F7F5)
    val Line = Color(0xFFE2E8E3)
    val LineSoft = Color(0xFFEDF1EE)
    val Muted = Color(0xFF5C6B62)
    val Muted2 = Color(0xFF8A978F)
    val Accent = Color(0xFF1C7A4D)
    val AccentHover = Color(0xFF145C3A)
    val AccentBright = Color(0xFF2F9463)
    val AccentTint = Color(0xFFE4F5EC)
    val Danger = Color(0xFFB3261E)
    val DangerTint = Color(0xFFFBE9E7)
    val DangerHover = Color(0xFF8F1E18)

    // The meal-plan slot headers only: DESIGN.md's one exception to the single-accent rule.
    val BreakfastTint = Color(0xFFEAF5E8)
    val BreakfastInk = Color(0xFF4C7A3C)
    val LunchTint = Color(0xFFE8F0F8)
    val LunchInk = Color(0xFF3C6690)
    val DinnerTint = Color(0xFFFBEEE0)
    val DinnerInk = Color(0xFFA86A2C)
}

/** DESIGN.md's spacing tokens (rem x 16 dp): between rows, between sections, and around a band of content. */
object MealSpacing {
    val Row = 11.2.dp
    val Section = 20.dp
    val Band = 24.dp
}

private val Colors = lightColorScheme(
    primary = MealColors.Accent,
    onPrimary = MealColors.Paper,
    primaryContainer = MealColors.AccentTint,
    onPrimaryContainer = MealColors.AccentHover,
    secondary = MealColors.Muted,
    onSecondary = MealColors.Paper,
    background = MealColors.Paper,
    onBackground = MealColors.Ink,
    surface = MealColors.Paper,
    onSurface = MealColors.Ink,
    surfaceVariant = MealColors.PaperAlt,
    onSurfaceVariant = MealColors.Muted,
    outline = MealColors.Line,
    outlineVariant = MealColors.LineSoft,
    error = MealColors.Danger,
    onError = MealColors.Paper,
    errorContainer = MealColors.DangerTint,
    onErrorContainer = MealColors.Danger,
)

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

@Composable
fun MealPlannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, shapes = MealShapes, typography = MealTypography, content = content)
}
