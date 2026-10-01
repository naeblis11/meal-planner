package com.naeblis11.mealplanner.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.calendar.CalendarChoice
import com.naeblis11.mealplanner.calendar.CalendarGateway
import com.naeblis11.mealplanner.calendar.CalendarInfo
import com.naeblis11.mealplanner.calendar.CalendarMessages
import com.naeblis11.mealplanner.calendar.ChosenCalendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The calendar list in Settings, while the user picks one. */
sealed interface CalendarPicker {
    data object Closed : CalendarPicker

    data object Loading : CalendarPicker

    data class Choosing(val calendars: List<CalendarInfo>) : CalendarPicker
}

data class CalendarSetupState(
    val chosen: ChosenCalendar? = null,
    val picker: CalendarPicker = CalendarPicker.Closed,
    /** The user refused the permission: explain, and offer this app's system settings page. */
    val permissionDenied: Boolean = false,
    val error: String? = null,
)

/** Settings' Google Calendar panel: permission, the list of writable calendars, the choice. */
class CalendarSetupViewModel(
    private val gateway: CalendarGateway,
    private val choice: CalendarChoice,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val local = MutableStateFlow(CalendarSetupState())

    val state: StateFlow<CalendarSetupState> = combine(choice.chosen, local) { chosen, state -> state.copy(chosen = chosen) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, local.value.copy(chosen = choice.chosen.value))

    /** Whether the system dialog is needed before the list (cheap: a permission check, no provider call). */
    fun hasPermission(): Boolean = gateway.hasPermission()

    /** The system permission dialog's answer. */
    fun permissionResult(granted: Boolean) {
        if (granted) {
            loadCalendars()
        } else {
            local.update { it.copy(permissionDenied = true, picker = CalendarPicker.Closed) }
        }
    }

    /** Reads the writable calendars and shows them to choose from; ignored while a read is running. */
    fun loadCalendars() {
        if (local.value.picker == CalendarPicker.Loading) return
        local.update { it.copy(picker = CalendarPicker.Loading, permissionDenied = false, error = null) }
        viewModelScope.launch {
            try {
                val calendars = withContext(ioDispatcher) { gateway.writableCalendars() }
                local.update { it.copy(picker = CalendarPicker.Choosing(calendars)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                local.update { it.copy(picker = CalendarPicker.Closed, permissionDenied = true) }
            } catch (e: Exception) {
                local.update { it.copy(picker = CalendarPicker.Closed, error = CalendarMessages.CANT_READ_CALENDARS) }
            }
        }
    }

    fun choose(calendar: CalendarInfo) {
        choice.choose(calendar)
        local.update { it.copy(picker = CalendarPicker.Closed, error = null) }
    }

    fun cancelChoosing() {
        local.update { it.copy(picker = CalendarPicker.Closed) }
    }

    /** Back from this app's system settings page: the explanation goes once the permission is there. */
    fun returnedFromAppSettings() {
        if (gateway.hasPermission()) local.update { it.copy(permissionDenied = false) }
    }
}