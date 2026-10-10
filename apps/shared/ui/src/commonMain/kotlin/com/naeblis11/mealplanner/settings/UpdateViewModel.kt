package com.naeblis11.mealplanner.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.update.UpdateControls
import com.naeblis11.mealplanner.update.UpdatePhase
import com.naeblis11.mealplanner.update.UpdateStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings' Updates section: the check's [status], when it last worked, and whether the PC's "this closes" question is up. */
data class UpdateUiState(val status: UpdateStatus, val lastCheckedText: String? = null, val confirming: Boolean = false)

/**
 * Settings' Updates section over the update check ([controls], which owns the work, so leaving Settings never cancels a
 * download). It adds what only the screen needs: on the PC, Install asks first, because the app closes for the installer
 * (spec "Updates": installing is always the user's choice); on the phone Android's own install screen asks. And the
 * last check's time, in [zone].
 */
class UpdateViewModel(
    private val controls: UpdateControls,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val confirming = MutableStateFlow(false)

    val state: StateFlow<UpdateUiState> = combine(controls.state, confirming) { status, asking -> ui(status, asking) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ui(controls.state.value, false))

    init {
        // A question belongs to one offer while idle: a check that finishes (or a new offer) never brings it back.
        var seen = controls.state.value.let { it.phase to it.offer }
        viewModelScope.launch {
            controls.state.map { it.phase to it.offer }.distinctUntilChanged().collect { now ->
                if (now != seen) confirming.value = false
                seen = now
            }
        }
    }

    fun checkNow() = controls.checkNow()

    fun setAutomatic(on: Boolean) = controls.setAutomatic(on)

    /** Install: on the PC, the question first; on the phone, straight to Android. Ignored with nothing offered or while busy. */
    fun install() {
        val status = controls.state.value
        if (status.offer == null || status.phase != UpdatePhase.IDLE) return
        if (status.installClosesApp) confirming.value = true else controls.install()
    }

    fun confirmInstall() {
        confirming.value = false
        val status = controls.state.value
        if (status.offer != null && status.phase == UpdatePhase.IDLE) controls.install()
    }

    fun cancelInstall() {
        confirming.value = false
    }

    fun openInstallPermission() = controls.openInstallPermission()

    private fun ui(status: UpdateStatus, asking: Boolean) = UpdateUiState(
        status = status,
        lastCheckedText = status.lastChecked?.let { lastCheckedText(it, zone) },
        confirming = asking && status.offer != null && status.phase == UpdatePhase.IDLE,
    )

    companion object {
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.ENGLISH)

        /** "6 Oct 2026 at 09:41". */
        fun lastCheckedText(millis: Long, zone: ZoneId): String = FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone))
    }
}
