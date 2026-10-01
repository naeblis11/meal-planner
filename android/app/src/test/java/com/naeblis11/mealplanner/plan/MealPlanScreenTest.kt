package com.naeblis11.mealplanner.plan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.naeblis11.mealplanner.calendar.CalendarMessages
import com.naeblis11.mealplanner.data.PlannedMealRow
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h6000dp")
class MealPlanScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val monday = LocalDate.of(2026, 9, 28)
    private val week = WeekViews.build(
        monday,
        listOf(PlannedMealRow("2026-09-28", "Dinner", 4, "6", "Soup", null)),
        today = LocalDate.of(2026, 10, 1),
    )

    private class Taps {
        var opened = 0L
        var next = 0
        var added = 0
        var sent = 0
        var settings = 0
        var appSettings = 0
        var assigned: Pair<LocalDate, String>? = null
        var removed: Pair<LocalDate, String>? = null
    }

    private fun show(adding: Boolean = false, sending: Boolean = false, problem: SendProblem? = null): Taps {
        val taps = Taps()
        compose.setContent {
            MealPlannerTheme {
                MealPlanScreen(
                    week = week,
                    message = null,
                    error = null,
                    adding = adding,
                    sending = sending,
                    sendProblem = problem,
                    onMessageShown = {},
                    onPrevious = {},
                    onNext = { taps.next++ },
                    onThisWeek = {},
                    onAddToShoppingList = { taps.added++ },
                    onSend = { taps.sent++ },
                    onOpenSettings = { taps.settings++ },
                    onOpenAppSettings = { taps.appSettings++ },
                    onOpenRecipe = { taps.opened = it },
                    onAssign = { d, s -> taps.assigned = d to s },
                    onRemove = { d, s -> taps.removed = d to s },
                )
            }
        }
        return taps
    }

    @Test
    fun showsTheWeekAndItsMeals() {
        val taps = show()
        compose.onNodeWithText("Plan your meals for the week.").assertIsDisplayed()
        compose.onNodeWithText("Sep 28 \u2013 Oct 4").assertIsDisplayed()
        compose.onNodeWithText("Monday, Sep 28").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("for 6").assertIsDisplayed()

        compose.onNodeWithText("Soup").performClick()
        assertEquals(4L, taps.opened)
        compose.onNodeWithContentDescription("Add recipe for Breakfast on Monday, Sep 28").performClick()
        assertEquals(monday to "Breakfast", taps.assigned)
        compose.onNodeWithContentDescription("Change Dinner on Monday, Sep 28").performClick()
        assertEquals(monday to "Dinner", taps.assigned)
        compose.onNodeWithContentDescription("Remove Dinner on Monday, Sep 28").performClick()
        assertEquals(monday to "Dinner", taps.removed)
        compose.onNodeWithText("Next").performClick()
        assertEquals(1, taps.next)
        compose.onNodeWithText("Add this week's meals to the shopping list").performClick()
        assertEquals(1, taps.added)
        compose.onNodeWithText("Send this week to Google Calendar").performClick()
        assertEquals(1, taps.sent)
    }

    @Test
    fun addingIsOffWhileAnAddRuns() {
        show(adding = true)
        compose.onNodeWithText("Add this week's meals to the shopping list").assertIsNotEnabled()
    }

    @Test
    fun sendingIsOffWhileASendRuns() {
        show(sending = true)
        compose.onNodeWithText("Sending to your calendar...").assertIsNotEnabled()
    }

    @Test
    fun notSetUpLeadsToSettings() {
        val taps = show(problem = SendProblem.NotSetUp)
        compose.onNodeWithText(CalendarMessages.NOT_SET_UP).assertIsDisplayed()
        compose.onNodeWithText("Open Settings").performClick()
        assertEquals(1, taps.settings)
    }

    @Test
    fun aGoneCalendarLeadsToSettings() {
        val taps = show(problem = SendProblem.CalendarGone)
        compose.onNodeWithText(CalendarMessages.CALENDAR_GONE).assertIsDisplayed()
        compose.onNodeWithText("Open Settings").performClick()
        assertEquals(1, taps.settings)
    }

    @Test
    fun aRefusedPermissionLeadsToTheAppSettings() {
        val taps = show(problem = SendProblem.PermissionDenied)
        compose.onNodeWithText(CalendarMessages.PERMISSION_DENIED).assertIsDisplayed()
        compose.onNodeWithText("Open app settings").performClick()
        assertEquals(1, taps.appSettings)
    }

    @Test
    fun failuresAreShownWithoutAButton() {
        show(problem = SendProblem.Failed("1 meal could not be sent: Lunch on Tuesday, Sep 29. Send the week again to try it again."))
        compose.onNodeWithText("1 meal could not be sent: Lunch on Tuesday, Sep 29. Send the week again to try it again.").assertIsDisplayed()
        compose.onNodeWithText("Open Settings").assertDoesNotExist()
        compose.onNodeWithText("Open app settings").assertDoesNotExist()
    }
}
