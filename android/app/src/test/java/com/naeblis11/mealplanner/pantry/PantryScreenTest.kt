package com.naeblis11.mealplanner.pantry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.naeblis11.mealplanner.data.PantryItemEntity
import com.naeblis11.mealplanner.domain.AisleGroup
import com.naeblis11.mealplanner.domain.GroceryCategories
import com.naeblis11.mealplanner.domain.PantryDates
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class PantryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val salt = PantryItemEntity(id = 1, name = "Salt", aisle = "Spices & Baking", addedOn = "2026-09-13")
    private val pepper = PantryItemEntity(id = 2, name = "Pepper", active = false, exactMatch = true)
    private val state = PantryState(listOf(AisleGroup("Spices & Baking", listOf(salt))), listOf(pepper))

    private var added: Pair<String, String>? = null
    private var toggled: Pair<PantryItemEntity, Boolean>? = null
    private var saved: Triple<PantryItemEntity, String, String>? = null
    private var listed: PantryItemEntity? = null
    private var deleted: PantryItemEntity? = null

    private fun show() = compose.setContent {
        MealPlannerTheme {
            PantryScreen(
                state = state, message = null, error = null, onMessageShown = {},
                onAdd = { n, a -> added = n to a }, onSetOnHand = { i, on -> toggled = i to on },
                onSave = { i, a, d -> saved = Triple(i, a, d) }, onToggleMatch = {},
                onAddToShoppingList = { listed = it }, onDelete = { deleted = it },
            )
        }
    }

    @Test
    fun listsWhatIsOnHandAndWhatHasRunOut() {
        show()
        compose.onNodeWithText("What you've already got on hand.").assertIsDisplayed()
        compose.onNodeWithText("Spices & Baking").assertIsDisplayed()
        compose.onNodeWithText("partial match \u00b7 Added Sep 13, 2026").assertIsDisplayed()
        compose.onNodeWithText("Removed").assertIsDisplayed()
        compose.onNode(hasText("Pepper") and isToggleable()).assertIsOff()
        compose.onNode(hasText("Salt") and isToggleable()).assertIsOn().performClick()
        assertEquals(salt to false, toggled)
    }

    @Test
    fun addsWithAChosenAisle() {
        show()
        compose.onNodeWithText("Add an ingredient").performTextInput("Milk")
        compose.onNodeWithText("Aisle (optional)").performTextInput("dairy")
        compose.onNodeWithText("Dairy & Eggs").performClick()
        compose.onNodeWithText("Add").performClick()
        assertEquals("Milk" to "Dairy & Eggs", added)
    }

    @Test
    fun tappingAnEmptyAisleFieldListsEveryAisle() {
        show()
        compose.onNodeWithText("Add an ingredient").performTextInput("Milk")
        compose.onNodeWithText("Aisle (optional)").performClick()
        // Every store aisle is offered before anything is typed ("Spices & Baking" also heads a group).
        for (aisle in GroceryCategories.AISLE_ORDER) {
            assertTrue(aisle, compose.onAllNodesWithText(aisle).fetchSemanticsNodes().isNotEmpty())
        }
        compose.onNodeWithText("Household").performClick()
        compose.onNodeWithText("Add").performClick()
        assertEquals("Milk" to "Household", added)
    }

    @Test
    fun editsAnItemAndPutsItOnTheList() {
        show()
        compose.onNodeWithContentDescription("Edit Salt").performClick()
        compose.onNodeWithText("Date added (YYYY-MM-DD)").performTextReplacement("2026-09-20")
        compose.onNodeWithText("Save").performClick()
        assertEquals(Triple(salt, "Spices & Baking", "2026-09-20"), saved)

        compose.onNodeWithContentDescription("Edit Salt").performClick()
        compose.onNodeWithText("Add to shopping list").performClick()
        assertEquals(salt, listed)
    }

    @Test
    fun aBadDateKeepsTheDialogOpenWithWhatWasTyped() {
        show()
        compose.onNodeWithContentDescription("Edit Salt").performClick()
        compose.onNodeWithText("Date added (YYYY-MM-DD)").performTextReplacement("01/09/2026")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("01/09/2026").assertIsDisplayed()
        compose.onNodeWithText(PantryDates.HINT).assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
        assertEquals(null, saved)
    }

    @Test
    fun deletingAsksFirst() {
        show()
        compose.onNodeWithContentDescription("Edit Pepper").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete Pepper?").assertIsDisplayed()
        compose.onNodeWithText("Yes, delete it").performClick()
        assertEquals(pepper, deleted)
    }
}
