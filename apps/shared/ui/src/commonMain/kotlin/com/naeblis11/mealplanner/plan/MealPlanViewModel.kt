package com.naeblis11.mealplanner.plan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.calendar.CalendarMessages
import com.naeblis11.mealplanner.calendar.SendOutcome
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.domain.Week
import com.naeblis11.mealplanner.shopping.ShoppingMessages
import com.naeblis11.mealplanner.ui.UiMessage
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What stopped "Send this week", shown in the Calendar's banner until the next send. */
sealed interface SendProblem {
    val text: String

    data object NotSetUp : SendProblem {
        override val text = CalendarMessages.NOT_SET_UP
    }

    data object PermissionDenied : SendProblem {
        override val text = CalendarMessages.PERMISSION_DENIED
    }

    data object CalendarGone : SendProblem {
        override val text = CalendarMessages.CALENDAR_GONE
    }

    /** Desktop (Google): something only Settings fixes, in [text]'s words: sign in, sign in again, or choose again. */
    data class NeedsSettings(override val text: String) : SendProblem

    data class Failed(override val text: String) : SendProblem
}

/** The meal plan week: the Pi's /calendar, plus "Send this week to Google Calendar". */
class MealPlanViewModel(
    private val plans: MealPlanRepository,
    private val shopping: ShoppingRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val today: () -> LocalDate = LocalDate::now,
    /** Makes the chosen calendar match a week (CalendarSync.sendWeek); a seam for tests. */
    private val sendWeek: suspend (LocalDate) -> SendOutcome = { SendOutcome.NotSetUp },
    /** Counts calendar picks in Settings (CalendarChoice.picks); any pick, even of the same calendar, clears a "choose a calendar" banner. */
    private val calendarPicks: StateFlow<Int> = MutableStateFlow(0),
    /** The shopping-list write; a seam so tests can hold an add open. */
    private val addWeek: suspend (LocalDate) -> Int = shopping::addWeek,
) : ViewModel() {
    private val _weekStart = MutableStateFlow(
        savedState.get<String>(WEEK_KEY)?.let { runCatching { Week.start(LocalDate.parse(it)) }.getOrNull() } ?: Week.start(today()),
    )

    /** The Monday of the week on screen; kept across process death. */
    val weekStart: StateFlow<LocalDate> = _weekStart.asStateFlow()
    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** True while any week is being added to the shopping list (here or on the list); the button is off meanwhile. */
    val adding: StateFlow<Boolean> = shopping.adding

    private val _sending = MutableStateFlow(false)

    /** True while a week is being sent to the calendar; the button is off and a tap is ignored meanwhile. */
    val sending: StateFlow<Boolean> = _sending.asStateFlow()
    private val _sendProblem = MutableStateFlow<SendProblem?>(null)
    val sendProblem: StateFlow<SendProblem?> = _sendProblem.asStateFlow()

    init {
        // This ViewModel outlives a trip to Settings on the back stack, so a banner asking for
        // a calendar must go once one is picked, even the same one again (it had gone and came back).
        viewModelScope.launch {
            calendarPicks.drop(1).collect {
                _sendProblem.compareAndSet(SendProblem.NotSetUp, null)
                _sendProblem.compareAndSet(SendProblem.CalendarGone, null)
                _sendProblem.update { if (it is SendProblem.NeedsSettings) null else it }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val week: StateFlow<WeekView?> = _weekStart
        .flatMapLatest { start ->
            plans.observeWeek(start).map { meals ->
                if (_error.value == CANT_SHOW) _error.value = null
                WeekViews.build(start, meals, today())
            }
        }
        .retryWhen { _, attempt ->
            _error.value = CANT_SHOW
            delay(1_000L * (attempt + 1).coerceAtMost(5L))
            true
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun previousWeek() = showWeek(_weekStart.value.minusWeeks(1))

    fun nextWeek() = showWeek(_weekStart.value.plusWeeks(1))

    fun thisWeek() = showWeek(Week.start(today()))

    private fun showWeek(start: LocalDate) {
        _weekStart.value = start
        savedState[WEEK_KEY] = start.toString()
    }

    /** Takes the meal out of its slot (no confirmation, as on the Pi). */
    fun remove(date: LocalDate, slot: String) {
        viewModelScope.launch {
            try {
                plans.unassign(date, slot)
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "That meal could not be removed."
            }
        }
    }

    /**
     * Adds the week on screen to the shopping list. The list is additive, so a tap
     * while an add is running (from this screen or the Shopping list) is ignored rather
     * than doubling every amount.
     */
    fun addWeekToShoppingList() {
        if (!shopping.tryStartAdd()) return
        val start = _weekStart.value
        viewModelScope.launch {
            try {
                _message.value = UiMessage(ShoppingMessages.addedWeek(addWeek(start)))
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = ShoppingMessages.UPDATE_FAILED
            }
        }.invokeOnCompletion { shopping.finishAdd() } // Also when cancelled before it started.
    }

    /**
     * Makes the chosen calendar match the week on screen. A tap while a send runs is
     * ignored. The counts go to the snackbar; a problem (or the meals that failed) stays
     * in the banner until the next send.
     */
    fun sendWeekToCalendar() {
        if (!_sending.compareAndSet(false, true)) return
        val start = _weekStart.value
        _sendProblem.value = null
        viewModelScope.launch {
            try {
                when (val outcome = sendWeek(start)) {
                    SendOutcome.NotSetUp -> _sendProblem.value = SendProblem.NotSetUp
                    SendOutcome.PermissionDenied -> _sendProblem.value = SendProblem.PermissionDenied
                    SendOutcome.CalendarGone -> _sendProblem.value = SendProblem.CalendarGone
                    is SendOutcome.NeedsSettings -> _sendProblem.value = SendProblem.NeedsSettings(outcome.text)
                    is SendOutcome.Failed -> _sendProblem.value = SendProblem.Failed(outcome.text)
                    is SendOutcome.Sent -> {
                        // The banner first, so it is already up when the snackbar's counts appear.
                        if (outcome.failures.isNotEmpty()) _sendProblem.value = SendProblem.Failed(CalendarMessages.failures(outcome.failures))
                        CalendarMessages.summary(outcome)?.let { _message.value = UiMessage(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sendProblem.value = SendProblem.Failed(CalendarMessages.SEND_FAILED)
            }
        }.invokeOnCompletion { _sending.value = false } // Also when cancelled before it started.
    }

    /** Back from this app's system settings page: a permission banner goes; the next send checks again. */
    fun appSettingsClosed() {
        _sendProblem.compareAndSet(SendProblem.PermissionDenied, null)
    }

    /** The screen showed [shown]; a newer message that arrived meanwhile (even with the same words) is kept for its turn. */
    fun messageShown(shown: UiMessage) {
        _message.compareAndSet(shown, null)
    }

    companion object {
        const val WEEK_KEY = "week"
        private const val CANT_SHOW = "The meal plan can't be shown right now."
    }
}
