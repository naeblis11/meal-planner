package com.naeblis11.mealplanner.settings

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.app.CalendarChoice
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.calendar.CalendarChoice
import com.naeblis11.mealplanner.calendar.CalendarGateway
import com.naeblis11.mealplanner.calendar.CalendarInfo
import com.naeblis11.mealplanner.calendar.CalendarMessages
import com.naeblis11.mealplanner.calendar.ChosenCalendar
import com.naeblis11.mealplanner.calendar.FakeCalendarGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarSetupViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("calendar-setup-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val gateway = FakeCalendarGateway()
    private val choice = CalendarChoice(prefs)
    private val created = mutableListOf<CalendarSetupViewModel>()

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
    }

    private fun vm() = CalendarSetupViewModel(gateway, choice, Dispatchers.Unconfined).also { created += it }

    @Test
    fun listsTheWritableCalendarsAndKeepsTheChoice() = runTest {
        gateway.calendars += CalendarInfo(2L, "Work", "work@example.com")
        val vm = vm()
        assertEquals(null, vm.state.value.chosen)

        vm.loadCalendars()
        val picker = vm.state.first { it.picker is CalendarPicker.Choosing }.picker as CalendarPicker.Choosing
        assertEquals(listOf("Family (family@example.com)", "Work (work@example.com)"), picker.calendars.map { it.label })

        vm.choose(picker.calendars[1])
        val state = vm.state.first { it.chosen != null }
        assertEquals(ChosenCalendar(2L, "Work (work@example.com)"), state.chosen)
        assertEquals(CalendarPicker.Closed, state.picker)
        assertEquals(2L, CalendarChoice(prefs).chosen.value!!.id) // kept for next time
    }

    @Test
    fun aGrantedPermissionGoesStraightToTheList() = runTest {
        val vm = vm()
        assertTrue(vm.hasPermission())
        vm.permissionResult(true)
        vm.state.first { it.picker is CalendarPicker.Choosing }
    }

    @Test
    fun aRefusedPermissionIsExplainedUntilItIsAllowed() = runTest {
        gateway.permission = false
        val vm = vm()
        assertFalse(vm.hasPermission())
        vm.permissionResult(false)
        assertTrue(vm.state.first { it.permissionDenied }.permissionDenied)

        vm.returnedFromAppSettings() // still not allowed
        assertTrue(vm.state.value.permissionDenied)

        gateway.permission = true
        vm.returnedFromAppSettings()
        vm.state.first { !it.permissionDenied }
    }

    @Test
    fun permissionLostWhileListingIsExplained() = runTest {
        gateway.permission = false
        val vm = vm()
        vm.loadCalendars()
        val state = vm.state.first { it.permissionDenied }
        assertEquals(CalendarPicker.Closed, state.picker)
    }

    @Test
    fun aPhoneWithoutWritableCalendarsShowsAnEmptyListAndCancelCloses() = runTest {
        gateway.calendars.clear()
        val vm = vm()
        vm.loadCalendars()
        assertEquals(CalendarPicker.Choosing(emptyList()), vm.state.first { it.picker is CalendarPicker.Choosing }.picker)
        vm.cancelChoosing()
        vm.state.first { it.picker == CalendarPicker.Closed }
        assertEquals(null, vm.state.value.error)
        assertEquals(null, vm.state.value.chosen)
    }

    @Test
    fun anUnreadableCalendarListSaysSo() = runTest {
        val broken = object : CalendarGateway by gateway {
            override fun writableCalendars(): List<CalendarInfo> = throw IllegalStateException("provider crashed")
        }
        val vm = CalendarSetupViewModel(broken, choice, Dispatchers.Unconfined).also { created += it }
        vm.loadCalendars()
        assertEquals(CalendarMessages.CANT_READ_CALENDARS, vm.state.first { it.error != null }.error)
    }
}