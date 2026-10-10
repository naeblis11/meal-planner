package com.naeblis11.mealplanner.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class MainTabsFontScaleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shoppingIsNotClippedAtLargeFontScale() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 1.3f)) {
                MealPlannerTheme { MainTabs(MainTab.SHOPPING, {}) }
            }
        }
        compose.waitForIdle()
        val results = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText("Shopping").fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.single()
        assertFalse("the Shopping label is clipped", layout.hasVisualOverflow)
        assertEquals(1, layout.lineCount)
    }
}
