package com.naeblis11.mealplanner.plan

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.calendar.CalendarChoice
import com.naeblis11.mealplanner.calendar.CalendarInfo
import com.naeblis11.mealplanner.calendar.CalendarMessages
import com.naeblis11.mealplanner.calendar.CalendarSync
import com.naeblis11.mealplanner.calendar.FakeCalendarGateway
import com.naeblis11.mealplanner.calendar.SendOutcome
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MealPlanSendTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = FakeCalendarGateway()
    private val today = LocalDate.of(2026, 10, 1)
    private val monday = LocalDate.of(2026, 9, 28)
    private lateinit var db: AppDatabase
    private lateinit var recipes: RecipeRepository
    private lateinit var plans: MealPlanRepository
    private lateinit var shopping: ShoppingRepository
    private lateinit var choice: CalendarChoice
    private lateinit var sync: CalendarSync
    private val created = mutableListOf<MealPlanViewModel>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        recipes = RecipeRepository(db, Files.createTempDirectory("images").toFile(), dispatcher = Dispatchers.Unconfined)
        plans = MealPlanRepository(db, Dispatchers.Unconfined)
        shopping = ShoppingRepository(db, Dispatchers.Unconfined)
        choice = CalendarChoice(context.getSharedPreferences("meal-plan-send-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        sync = CalendarSync(db, gateway, choice, Dispatchers.Unconfined) { ZoneId.of("America/Toronto") }
    }

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    private fun vm(send: suspend (LocalDate) -> SendOutcome = sync::sendWeek) =
        MealPlanViewModel(plans, shopping, SavedStateHandle(), { today }, sendWeek = send, calendarPicks = choice.picks).also { created += it }

    @Suppress("UNCHECKED_CAST")
    private suspend fun recipe(name: String): Long = recipes.save(
        RecipeYaml.load("recipe_name: $name\ningredients:\n- Rice:\n    amounts:\n    - amount: 1\n      unit: cup\nsteps:\n- step: Cook.\n") as YamlMap,
    )

    @Test
    fun sendsTheWeekShownAndSaysWhatChanged() = runTest {
        choice.choose(gateway.calendars[0])
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        val vm = vm()

        vm.sendWeekToCalendar()
        val first = vm.message.first { it != null }!!
        assertEquals("Calendar updated: 1 added.", first.text)
        assertEquals(listOf("Dinner: Soup"), gateway.titles())
        vm.messageShown(first)
        vm.sending.first { !it }

        vm.sendWeekToCalendar()
        val second = vm.message.first { it != null }!!
        assertEquals("Your calendar was already up to date.", second.text)
        assertNotEquals(first.id, second.id)
        assertEquals(null, vm.sendProblem.value)
    }

    @Test
    fun withNoCalendarChosenItAsksForOne() = runTest {
        val vm = vm()
        vm.sendWeekToCalendar()
        assertEquals(SendProblem.NotSetUp, vm.sendProblem.first { it != null })
        assertEquals(CalendarMessages.NOT_SET_UP, vm.sendProblem.value!!.text)
        assertEquals(emptyList<String>(), gateway.writes)
    }

    @Test
    fun aRefusedPermissionIsExplainedUntilTheAppSettingsAreClosed() = runTest {
        choice.choose(gateway.calendars[0])
        gateway.permission = false
        val vm = vm()
        vm.sendWeekToCalendar()
        assertEquals(SendProblem.PermissionDenied, vm.sendProblem.first { it != null })
        vm.appSettingsClosed()
        assertEquals(null, vm.sendProblem.value)
    }

    @Test
    fun aCalendarThatIsGoneAsksForAnother() = runTest {
        choice.choose(CalendarInfo(99L, "Gone", ""))
        val vm = vm()
        vm.sendWeekToCalendar()
        assertEquals(SendProblem.CalendarGone, vm.sendProblem.first { it != null })
        vm.appSettingsClosed() // only a permission problem is cleared by coming back from app settings
        assertEquals(SendProblem.CalendarGone, vm.sendProblem.value)
    }

    @Test
    fun failuresAreListedBesideTheCounts() = runTest {
        choice.choose(gateway.calendars[0])
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        plans.assign(monday.plusDays(1), "Lunch", recipe("Stew"), null)
        gateway.failTitles += "Lunch: Stew"
        val vm = vm()

        vm.sendWeekToCalendar()
        assertEquals("Calendar updated: 1 added.", vm.message.first { it != null }!!.text)
        assertEquals(
            SendProblem.Failed("1 meal could not be sent: Lunch on Tuesday, Sep 29. Send the week again to try it again."),
            vm.sendProblem.value,
        )
    }

    @Test
    fun aSecondTapWhileSendingIsIgnored() = runTest {
        val gate = CompletableDeferred<SendOutcome>()
        var calls = 0
        val vm = vm { calls++; gate.await() }

        vm.sendWeekToCalendar()
        vm.sendWeekToCalendar()
        assertEquals(1, calls)
        assertTrue(vm.sending.value)

        gate.complete(SendOutcome.Sent(0, 0, 0, 0, emptyList()))
        vm.sending.first { !it }
        vm.sendWeekToCalendar()
        assertEquals(2, calls)
    }

    @Test
    fun aSendThatThrowsSaysSoAndFreesTheButton() = runTest {
        val vm = vm { throw IllegalStateException("provider crashed") }
        vm.sendWeekToCalendar()
        assertEquals(SendProblem.Failed(CalendarMessages.SEND_FAILED), vm.sendProblem.first { it != null })
        assertFalse(vm.sending.value)
    }

    @Test
    fun theNextSendClearsTheLastProblem() = runTest {
        val vm = vm()
        vm.sendWeekToCalendar()
        vm.sendProblem.first { it != null }
        choice.choose(gateway.calendars[0])
        vm.sendWeekToCalendar()
        vm.message.first { it != null }
        assertEquals(null, vm.sendProblem.value)
    }

    @Test
    fun aProviderThatDoesNotAnswerIsAFailureNotACrash() = runTest {
        choice.choose(gateway.calendars[0])
        val silent = object : com.naeblis11.mealplanner.calendar.CalendarGateway by gateway {
            override fun calendar(id: Long): CalendarInfo? = throw IllegalStateException("The calendar did not answer.")
        }
        val silentSync = CalendarSync(db, silent, choice, Dispatchers.Unconfined) { ZoneId.of("America/Toronto") }
        val vm = vm(silentSync::sendWeek)
        vm.sendWeekToCalendar()
        assertEquals(SendProblem.Failed(CalendarMessages.SEND_FAILED), vm.sendProblem.first { it != null })
        assertFalse(vm.sending.value)
        assertEquals(emptyList<String>(), gateway.writes)
    }

    @Test
    fun aFailedSendReleasesTheGuardSoALaterTapSends() = runTest {
        val gate = CompletableDeferred<SendOutcome>()
        var calls = 0
        val vm = vm { calls++; gate.await() }
        vm.sendWeekToCalendar()
        vm.sendWeekToCalendar()
        assertEquals(1, calls)
        gate.completeExceptionally(IllegalStateException("provider crashed"))
        vm.sending.first { !it }
        assertEquals(SendProblem.Failed(CalendarMessages.SEND_FAILED), vm.sendProblem.value)
        vm.sendWeekToCalendar()
        assertEquals(2, calls)
    }

    @Test
    fun aCancelledSendReleasesTheGuardAndShowsNoProblem() = runTest {
        val gate = CompletableDeferred<SendOutcome>()
        var calls = 0
        val vm = vm { calls++; gate.await() }
        vm.sendWeekToCalendar()
        assertTrue(vm.sending.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.cancel() }
        assertFalse(vm.sending.value)
        assertEquals(null, vm.sendProblem.value)
        vm.sendWeekToCalendar()
        assertEquals(2, calls)
    }

    @Test
    fun sendsTheWeekOnScreenNotThisWeek() = runTest {
        val sent = mutableListOf<LocalDate>()
        val vm = vm { sent += it; SendOutcome.Sent(0, 0, 0, 0, emptyList()) }
        vm.nextWeek()
        vm.sendWeekToCalendar()
        vm.sending.first { !it }
        assertEquals(listOf(monday.plusWeeks(1)), sent)
    }

    @Test
    fun choosingACalendarClearsTheSetUpBanner() = runTest {
        val vm = vm()
        vm.sendWeekToCalendar()
        assertEquals(SendProblem.NotSetUp, vm.sendProblem.first { it != null })
        choice.choose(gateway.calendars[0])
        assertEquals(null, vm.sendProblem.value)
    }

    @Test
    fun choosingAnotherCalendarClearsTheGoneBanner() = runTest {
        choice.choose(CalendarInfo(99L, "Gone", ""))
        val vm = vm()
        vm.sendWeekToCalendar()
        assertEquals(SendProblem.CalendarGone, vm.sendProblem.first { it != null })
        choice.choose(gateway.calendars[0])
        assertEquals(null, vm.sendProblem.value)
    }

    @Test
    fun choosingTheSameCalendarAgainClearsTheGoneBanner() = runTest {
        val family = gateway.calendars.removeAt(0)
        choice.choose(family)
        val vm = vm()
        vm.sendWeekToCalendar()
        assertEquals(SendProblem.CalendarGone, vm.sendProblem.first { it != null })
        // It came back after a sync, and the user picks it again in Settings.
        gateway.calendars += family
        choice.choose(family)
        assertEquals(null, vm.sendProblem.value)
    }

    @Test
    fun choosingACalendarKeepsAPermissionBanner() = runTest {
        choice.choose(gateway.calendars[0])
        gateway.permission = false
        val vm = vm()
        vm.sendWeekToCalendar()
        assertEquals(SendProblem.PermissionDenied, vm.sendProblem.first { it != null })
        choice.choose(CalendarInfo(2L, "Other", ""))
        assertEquals(SendProblem.PermissionDenied, vm.sendProblem.value)
    }
}
