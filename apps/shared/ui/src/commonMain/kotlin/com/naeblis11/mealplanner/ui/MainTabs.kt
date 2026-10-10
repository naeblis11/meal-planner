package com.naeblis11.mealplanner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naeblis11.mealplanner.ui.theme.MealColors

/** The four top-level screens, in the Pi's top-nav order (Shopping ends under the right thumb). */
enum class MainTab(val route: String, val label: String) {
    RECIPES(Routes.RECIPES, "Recipes"),
    CALENDAR(Routes.CALENDAR, "Calendar"),
    PANTRY(Routes.PANTRY, "Pantry"),
    SHOPPING(Routes.SHOPPING, "Shopping"),
    ;

    companion object {
        fun forRoute(route: String?): MainTab? = entries.firstOrNull { it.route == route }
    }
}

/**
 * The bottom bar: words, not icons (DESIGN.md), each tab a full-height touch target.
 * The selected tab is accent on its tint. Pads itself above the system navigation bar.
 */
@Composable
fun MainTabs(selected: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MealColors.Paper, modifier = modifier) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            HorizontalDivider(color = MealColors.Line)
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                for (tab in MainTab.entries) {
                    val isSelected = tab == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .height(64.dp)
                            .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(color = if (isSelected) MealColors.AccentTint else Color.Transparent, shape = RoundedCornerShape(50)) {
                            Text(
                                tab.label,
                                color = if (isSelected) MealColors.AccentHover else MealColors.Muted,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                // Shrinks, never clips, when the system font is large.
                                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
