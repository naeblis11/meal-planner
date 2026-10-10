package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.data.PlannedMealRow
import com.naeblis11.mealplanner.plan.MealPlanScreen
import com.naeblis11.mealplanner.plan.WeekView
import com.naeblis11.mealplanner.plan.WeekViews
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** P3-R4: wide, the whole week as seven columns of three meals; narrow, the phone's list of days. */
class MealPlanWideTest {
    @get:Rule
    val compose = createComposeRule()

    private val week = WeekViews.build(
        LocalDate.of(2026, 10, 5),
        listOf(PlannedMealRow("2026-10-07", "Dinner", 4, "6", "Soup", null)),
        today = LocalDate.of(2026, 10, 7),
    )
    private var removed: Pair<LocalDate, String>? = null

    private var assigned: Pair<LocalDate, String>? = null

    private fun show(width: Dp, wide: Boolean, shown: WeekView = week) = compose.showAt(width) {
        MealPlanScreen(
            week = shown,
            message = null,
            error = null,
            adding = false,
            sending = false,
            sendProblem = null,
            onMessageShown = {},
            onPrevious = {},
            onNext = {},
            onThisWeek = {},
            onAddToShoppingList = {},
            onSend = {},
            onOpenSettings = {},
            onOpenAppSettings = {},
            onOpenRecipe = {},
            onAssign = { date, slot -> assigned = date to slot },
            onRemove = { date, slot -> removed = date to slot },
            wide = wide,
        )
    }

    @Test
    fun aWideWindowShowsTheWholeWeekInSevenColumns() {
        show(900.dp, wide = true)
        val monday = compose.onNodeWithText("Monday").getBoundsInRoot()
        val sunday = compose.onNodeWithText("Sunday").getBoundsInRoot()
        assertEquals(monday.top, sunday.top)
        assertTrue(sunday.left > monday.left)
        compose.onAllNodesWithText("Add recipe").assertCountEquals(20)
        compose.onAllNodesWithText("Oct 7 \u00b7 Today").assertCountEquals(1)
    }

    @Test
    fun todayIsMarkedWithoutPushingItsMealsDown() {
        // Owner, 2026-10-09: today (Wednesday) says so on its date line, so its meals line up with every other day's.
        show(900.dp, wide = true)
        val tuesday = compose.onNodeWithContentDescription("Add recipe for Breakfast on Tuesday, Oct 6").getBoundsInRoot()
        val today = compose.onNodeWithContentDescription("Add recipe for Breakfast on Wednesday, Oct 7").getBoundsInRoot()
        assertEquals(tuesday.top.value, today.top.value, 0.5f)
        val tuesdayDinner = compose.onNodeWithContentDescription("Add recipe for Dinner on Tuesday, Oct 6").getBoundsInRoot()
        val todayDinner = compose.onNodeWithContentDescription("Remove Dinner on Wednesday, Oct 7").getBoundsInRoot()
        assertTrue("today's dinner starts with Tuesday's", todayDinner.top >= tuesdayDinner.top)
    }

    @Test
    fun aPlannedMealInTheGridCanBeRemoved() {
        show(900.dp, wide = true)
        compose.onNodeWithText("Soup").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove Dinner on Wednesday, Oct 7").click()
        assertEquals(LocalDate.of(2026, 10, 7) to "Dinner", removed)
    }

    @Test
    fun anEmptySlotInTheGridAssignsItsOwnDateAndSlot() {
        show(900.dp, wide = true)
        compose.onNodeWithContentDescription("Add recipe for Lunch on Tuesday, Oct 6").click()
        assertEquals(LocalDate.of(2026, 10, 6) to "Lunch", assigned)
    }

    @Test
    fun aDayOfThreeMealsWithPhotosIsShort() {
        // Owner, 2026-10-09: three planned meals fit without scrolling. Each card's photo is a small square beside the
        // name, not a picture across the column, and Change and Remove sit side by side where they fit.
        val full = WeekViews.build(
            LocalDate.of(2026, 10, 5),
            listOf("Breakfast", "Lunch", "Dinner").mapIndexed { i, slot -> PlannedMealRow("2026-10-07", slot, 10L + i, "4", "Soup $i", "soup$i.jpg") },
            today = LocalDate.of(2026, 10, 7),
        )
        show(1280.dp, wide = true, shown = full)
        val top = compose.onNodeWithContentDescription("Change Breakfast on Wednesday, Oct 7").getBoundsInRoot()
        val bottom = compose.onNodeWithContentDescription("Remove Dinner on Wednesday, Oct 7").getBoundsInRoot()
        val change = compose.onNodeWithContentDescription("Change Dinner on Wednesday, Oct 7").getBoundsInRoot()
        assertEquals("side by side", change.top.value, bottom.top.value, 0.5f)
        assertTrue("three meals span ${bottom.bottom - top.top}", bottom.bottom - top.top < 400.dp)
    }

    @Test
    fun aLongRecipeNameInAColumnStopsAtThreeLines() {
        val long = "Slow roasted pork shoulder with caramelised onions, apples and a very long list of spices"
        val longWeek = WeekViews.build(
            LocalDate.of(2026, 10, 5),
            listOf(PlannedMealRow("2026-10-07", "Dinner", 4, "6", long, null)),
            today = LocalDate.of(2026, 10, 7),
        )
        show(900.dp, wide = true, shown = longWeek)
        // titleSmall is 20sp tall per line at density 1: three lines at most, not the five or six the name needs.
        // The unmerged node is the Text itself, not the clickable cell around it.
        val bounds = compose.onNodeWithText(long, useUnmergedTree = true).getBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("name is $height tall", height <= 64.dp)
    }

    @Test
    fun aNarrowWindowKeepsTheListOfDays() {
        show(400.dp, wide = false)
        compose.onNodeWithText("Monday, Oct 5").assertIsDisplayed()
        compose.onAllNodesWithText("Sunday, Oct 11").assertCountEquals(0)
        compose.onAllNodesWithText("Sunday").assertCountEquals(0)
    }
}
