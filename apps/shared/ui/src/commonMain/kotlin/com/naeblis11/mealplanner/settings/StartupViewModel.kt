package com.naeblis11.mealplanner.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The desktop's Start with Windows setting (P3-R3), behind an interface so shared code never sees the registry. */
interface StartupSwitch {
    /** False where Windows can't start this copy (development, the preview): the switch shows, off and greyed. */
    val available: Boolean

    /** Whether Windows starts Meal Planner at sign-in. Blocking (it asks the registry): call it off the main thread. */
    fun isOn(): Boolean

    /** Turns it on or off; false when Windows refused. Blocking. */
    fun setOn(on: Boolean): Boolean
}

data class StartupState(val available: Boolean, val on: Boolean, val busy: Boolean = false, val error: String? = null)

/** Settings' Start with Windows panel: reads the switch off the main thread and changes it one tap at a time. */
class StartupViewModel(
    private val switch: StartupSwitch,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow<StartupState?>(null)

    /** Null until the switch has been read. */
    val state: StateFlow<StartupState?> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.value = read() }
    }

    /** Ignored while a change is being made, and where the switch isn't available. */
    fun set(on: Boolean) {
        val current = _state.value ?: return
        if (!current.available || current.busy) return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val ok = withContext(ioDispatcher) { switch.setOn(on) }
            // What Windows has now, whatever the write did.
            _state.value = read().copy(error = if (ok) null else FAILED)
        }
    }

    private suspend fun read(): StartupState = withContext(ioDispatcher) {
        StartupState(available = switch.available, on = switch.available && switch.isOn())
    }

    companion object {
        const val FAILED = "Windows didn't take the change. Try again in a moment."
    }
}
