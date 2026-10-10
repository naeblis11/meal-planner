package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.data.PantryItemEntity
import com.naeblis11.mealplanner.domain.AisleGroup
import com.naeblis11.mealplanner.pantry.PantryScreen
import com.naeblis11.mealplanner.pantry.PantryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** P3-R4: wide, the pantry in two columns; narrow, one, as on the phone. */
class PantryWideTest {
    @get:Rule
    val compose = createComposeRule()

    private val milk = PantryItemEntity(id = 1, name = "Milk", aisle = "Dairy & Eggs")
    private val rice = PantryItemEntity(id = 2, name = "Rice", aisle = "Dry Goods & Pasta")
    private val state = PantryState(listOf(AisleGroup("Dairy & Eggs", listOf(milk)), AisleGroup("Dry Goods & Pasta", listOf(rice))), emptyList())

    private var onHand: Pair<PantryItemEntity, Boolean>? = null

    private fun show(width: Dp, wide: Boolean, shown: PantryState = state) = compose.showAt(width) {
        PantryScreen(
            state = shown,
            message = null,
            error = null,
            onMessageShown = {},
            onAdd = { _, _ -> },
            onSetOnHand = { item, on -> onHand = item to on },
            onSave = { _, _, _ -> },
            onToggleMatch = {},
            onAddToShoppingList = {},
            onDelete = {},
            wide = wide,
        )
    }

    private fun bounds(text: String) = compose.onNodeWithText(text).getBoundsInRoot()

    @Test
    fun aWideWindowPutsThePantryInTwoColumns() {
        show(1000.dp, wide = true)
        assertTrue(bounds("Dairy & Eggs").left < bounds("Dry Goods & Pasta").left)
        assertEquals(bounds("Dairy & Eggs").top, bounds("Dry Goods & Pasta").top)
    }

    @Test
    fun theRemovedCardPutsAnItemBackOnHand() {
        val flour = PantryItemEntity(id = 3, name = "Flour", aisle = "Baking", active = false)
        show(1000.dp, wide = true, shown = PantryState(state.onHand, listOf(flour)))
        compose.onNodeWithText("Removed").assertExists()
        compose.onNode(hasText("Flour") and isToggleable()).assertIsOff().click()
        assertEquals(flour to true, onHand)
    }

    @Test
    fun aNarrowWindowKeepsOneColumn() {
        show(400.dp, wide = false)
        assertEquals(bounds("Dairy & Eggs").left, bounds("Dry Goods & Pasta").left)
        assertTrue(bounds("Dairy & Eggs").top < bounds("Dry Goods & Pasta").top)
    }
}
