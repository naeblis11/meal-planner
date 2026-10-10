package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.data.ShoppingItemEntity
import com.naeblis11.mealplanner.domain.AisleGroup
import com.naeblis11.mealplanner.shopping.ShoppingScreen
import com.naeblis11.mealplanner.shopping.ShoppingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** P3-R4: wide, the shopping list's aisles in two or three columns; narrow, one, as on the phone. */
class ShoppingWideTest {
    @get:Rule
    val compose = createComposeRule()

    private val onion = ShoppingItemEntity(id = 1, name = "Onion", aisle = "Produce")
    private val carrot = ShoppingItemEntity(id = 2, name = "Carrot", aisle = "Produce")
    private val milk = ShoppingItemEntity(id = 3, name = "Milk", aisle = "Dairy & Eggs")
    private val bread = ShoppingItemEntity(id = 4, name = "Bread", aisle = "Bakery")
    private val state = ShoppingState(
        listOf(AisleGroup("Produce", listOf(carrot, onion)), AisleGroup("Dairy & Eggs", listOf(milk)), AisleGroup("Bakery", listOf(bread))),
        emptyList(),
    )
    private var checked: Pair<ShoppingItemEntity, Boolean>? = null
    private var removed: ShoppingItemEntity? = null

    private fun show(width: Dp, wide: Boolean, shown: ShoppingState = state) = compose.showAt(width) {
        ShoppingScreen(
            state = shown,
            message = null,
            error = null,
            adding = false,
            onMessageShown = {},
            onCheck = { item, on -> checked = item to on },
            onAddThisWeek = {},
            onAddItem = { _, _, _, _ -> },
            onSetAisle = { _, _ -> },
            onRemove = { removed = it },
            onClear = {},
            wide = wide,
        )
    }

    private fun left(text: String) = compose.onNodeWithText(text).getBoundsInRoot().left

    private fun top(text: String) = compose.onNodeWithText(text).getBoundsInRoot().top

    @Test
    fun aWideWindowPutsTheAislesInTwoColumns() {
        show(1000.dp, wide = true)
        assertTrue(left("Produce") < left("Dairy & Eggs"))
        assertEquals(left("Dairy & Eggs"), left("Bakery"))
        assertTrue(top("Dairy & Eggs") < top("Bakery"))
    }

    @Test
    fun aVeryWideWindowUsesThreeColumns() {
        show(1200.dp, wide = true)
        assertTrue(left("Produce") < left("Dairy & Eggs"))
        assertTrue(left("Dairy & Eggs") < left("Bakery"))
        assertEquals(top("Produce"), top("Bakery"))
    }

    @Test
    fun aNarrowWindowKeepsOneColumn() {
        show(400.dp, wide = false)
        assertEquals(left("Produce"), left("Dairy & Eggs"))
        assertEquals(left("Produce"), left("Bakery"))
    }

    @Test
    fun aRowInTheWideListStillTicks() {
        show(1000.dp, wide = true)
        compose.onNode(hasText("Milk") and isToggleable()).assertIsOff().click()
        assertEquals(milk to true, checked)
    }

    @Test
    fun theWideListShowsWhatIsAlreadyInTheKitchen() {
        val salt = ShoppingItemEntity(id = 5, name = "Salt", aisle = "Spices", inPantry = true)
        show(1000.dp, wide = true, shown = ShoppingState(state.needToBuy, listOf(salt)))
        compose.onNodeWithText("Already in My Kitchen").assertExists()
        compose.onNodeWithText("Salt").assertExists()
        compose.onNodeWithText("Clear shopping list").assertExists()
    }

    @Test
    fun aWideRowCanBeEditedAndRemoved() {
        show(1000.dp, wide = true)
        compose.onNodeWithContentDescription("Edit Milk").click()
        compose.onNodeWithText("Remove from list").click()
        assertEquals(milk, removed)
    }
}
