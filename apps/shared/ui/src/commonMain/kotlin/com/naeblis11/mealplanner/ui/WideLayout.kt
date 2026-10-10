package com.naeblis11.mealplanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.theme.MealColors

/** Material's "expanded" width class: from here up the app shows the rail and its wide screens (P3-R4). */
val WIDE_MIN_WIDTH: Dp = 840.dp

/**
 * Material's compact-height cutoff. A phone on its side can be over 840 dp wide but is about 400 dp high, and
 * phones always get the phone layout (P3-R4), so the wide layout needs this much height too.
 */
val WIDE_MIN_HEIGHT: Dp = 480.dp

fun isWide(width: Dp, height: Dp): Boolean = width >= WIDE_MIN_WIDTH && height >= WIDE_MIN_HEIGHT

/** True inside MealPlannerApp when isWide holds for its size; the screens read it at their destination. */
val LocalWideLayout: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** The rail's destinations, in order: the four tabs, then Settings. */
enum class RailItem(val route: String, val label: String) {
    RECIPES(Routes.RECIPES, "Recipes"),
    CALENDAR(Routes.CALENDAR, "Calendar"),
    PANTRY(Routes.PANTRY, "Pantry"),
    SHOPPING(Routes.SHOPPING, "Shopping"),
    SETTINGS(Routes.SETTINGS, "Settings"),
    ;

    companion object {
        /** The section a route belongs to, so the rail stays lit on a recipe page or the meal picker. */
        fun forRoute(route: String?): RailItem? = when (route) {
            null -> null
            Routes.CALENDAR, Routes.ASSIGN -> CALENDAR
            Routes.PANTRY -> PANTRY
            Routes.SHOPPING -> SHOPPING
            Routes.SETTINGS -> SETTINGS
            else -> RECIPES
        }
    }
}

val RAIL_WIDTH: Dp = 128.dp

/**
 * The wide layout's navigation rail: the server's top nav turned on its side (DESIGN.md). Words, not icons; the
 * current section is accent on its tint, like the bottom tabs. A hairline divides it from the page.
 */
@Composable
fun MainRail(selected: RailItem?, onSelect: (RailItem) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxHeight()) {
        Surface(color = MealColors.Paper, modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight()) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                    .padding(vertical = 16.dp)
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Meal Planner",
                    style = MaterialTheme.typography.titleMedium,
                    color = MealColors.Ink,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, bottom = 12.dp),
                )
                for (item in RailItem.entries) {
                    val isSelected = item == selected
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(item) })
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Surface(color = if (isSelected) MealColors.AccentTint else Color.Transparent, shape = RoundedCornerShape(50)) {
                            Text(
                                item.label,
                                color = if (isSelected) MealColors.AccentHover else MealColors.Muted,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        VerticalDivider(color = MealColors.Line)
    }
}
