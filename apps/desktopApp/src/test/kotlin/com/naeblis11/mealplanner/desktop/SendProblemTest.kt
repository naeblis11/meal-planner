package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.calendar.GoogleMessages
import com.naeblis11.mealplanner.calendar.SendOutcome
import com.naeblis11.mealplanner.plan.MealPlanScreen
import com.naeblis11.mealplanner.plan.MealPlanViewModel
import com.naeblis11.mealplanner.plan.SendProblem
import com.naeblis11.mealplanner.plan.WeekViews
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** P5-R5: what the Google send can't do itself is said in the Calendar's banner, with the way to Settings. */
class SendProblemTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-send-problem").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })
    private val created = mutableListOf<MealPlanViewModel>()

    private fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        app.close()
        dir.deleteRecursively()
    }

    private fun viewModel(outcome: SendOutcome, picks: MutableStateFlow<Int> = MutableStateFlow(0)) =
        MealPlanViewModel(app.container.plans, app.container.shopping, sendWeek = { outcome }, calendarPicks = picks).also { created += it }

    @Test
    fun somethingOnlySettingsFixesIsShownInItsWordsUntilAPick() = runBlocking {
        val picks = MutableStateFlow(0)
        val vm = viewModel(SendOutcome.NeedsSettings(GoogleMessages.SIGN_IN_AGAIN), picks)
        vm.sendWeekToCalendar()
        assertEquals(SendProblem.NeedsSettings(GoogleMessages.SIGN_IN_AGAIN), withTimeout(5_000) { vm.sendProblem.first { it != null } })
        // Signing in again and choosing the calendar in Settings clears it.
        picks.value = 1
        assertNull(withTimeout(5_000) { vm.sendProblem.first { it == null } })
    }

    @Test
    fun aSendThatStoppedSaysWhy() = runBlocking {
        val vm = viewModel(SendOutcome.Failed(GoogleMessages.UNREACHABLE))
        vm.sendWeekToCalendar()
        assertEquals(SendProblem.Failed(GoogleMessages.UNREACHABLE), withTimeout(5_000) { vm.sendProblem.first { it != null } })
    }

    @Test
    fun theBannerOffersSettings() {
        var opened = 0
        compose.showAt(600.dp) {
            MealPlanScreen(
                week = WeekViews.build(LocalDate.of(2026, 10, 5), emptyList(), today = LocalDate.of(2026, 10, 7)),
                message = null,
                error = null,
                adding = false,
                sending = false,
                sendProblem = SendProblem.NeedsSettings(GoogleMessages.SIGN_IN),
                onMessageShown = {},
                onPrevious = {},
                onNext = {},
                onThisWeek = {},
                onAddToShoppingList = {},
                onSend = {},
                onOpenSettings = { opened++ },
                onOpenAppSettings = {},
                onOpenRecipe = {},
                onAssign = { _, _ -> },
                onRemove = { _, _ -> },
            )
        }
        compose.onNodeWithText(GoogleMessages.SIGN_IN).assertExists()
        compose.onNodeWithText("Open Settings").click()
        assertEquals(1, opened)
    }
}
