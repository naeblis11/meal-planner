package com.naeblis11.mealplanner.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings' "Phones and PCs" as shown. [now] ticks once a second while a pairing window is open, for its countdown.
 * [removeAsk] is the device whose Remove is being confirmed.
 */
data class DevicesState(
    val devices: List<DeviceRow> = emptyList(),
    val offer: PairingView? = null,
    val status: SyncServerStatus = SyncServerStatus(SyncServerState.OFF, 0),
    val certificateNotice: String? = null,
    val opening: Boolean = false,
    val removeAsk: DeviceRow? = null,
    val error: String? = null,
    val now: Long = 0L,
)

/** What the section can ask for; all nothing by default (screen tests). */
class DeviceActions(
    val pair: () -> Unit = {},
    val cancelPairing: () -> Unit = {},
    val askRemove: (DeviceRow) -> Unit = {},
    val confirmRemove: () -> Unit = {},
    val cancelRemove: () -> Unit = {},
)

/**
 * Settings' "Phones and PCs" (LAN sync stage 2b, D10): Pair a device opens the window off the main thread (the
 * certificate may be made then), Remove asks first, and leaving Settings ([left]) closes a window left open, so the
 * sync port doesn't stay open for nobody.
 */
class PairingViewModel(
    private val controls: PairingControls,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private data class Local(val opening: Boolean = false, val removeAsk: DeviceRow? = null, val error: String? = null, val now: Long = 0L)

    private val local = MutableStateFlow(Local(now = clock()))

    // Settings was left (and not shown again) while a window was still opening: it closes as soon as it is open.
    @Volatile
    private var away = false

    val state: StateFlow<DevicesState> = combine(controls.devices, controls.offer, controls.status, controls.certificateNotice, local) { devices, offer, status, notice, mine ->
        // A newly seen window is timed from now, not from the ticker's last tick, so its first frame is right.
        val now = if (offer != null) maxOf(mine.now, clock()) else mine.now
        DevicesState(devices, offer, status, notice, mine.opening, mine.removeAsk, mine.error, now)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        DevicesState(controls.devices.value, controls.offer.value, controls.status.value, controls.certificateNotice.value, now = clock()),
    )

    init {
        // "Closes in m:ss": a tick a second while a window is open, none otherwise.
        viewModelScope.launch {
            controls.offer.collectLatest { open ->
                while (open != null) {
                    local.update { it.copy(now = clock()) }
                    delay(TICK_MILLIS)
                }
            }
        }
    }

    /** Pair a device. Ignored while a window is being opened. */
    fun pair() {
        if (local.value.opening) return
        local.update { it.copy(opening = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { controls.open() }
                if (away) controls.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(error = PAIRING_FAILED) }
            } finally {
                local.update { it.copy(opening = false, now = clock()) }
            }
        }
    }

    fun cancelPairing() = controls.close()

    fun askRemove(row: DeviceRow) = local.update { it.copy(removeAsk = row, error = null) }

    fun cancelRemove() = local.update { it.copy(removeAsk = null) }

    /** Remove, confirmed. */
    fun confirmRemove() {
        val row = local.value.removeAsk ?: return
        local.update { it.copy(removeAsk = null) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { controls.remove(row.deviceId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(error = REMOVE_FAILED) }
            }
        }
    }

    /** Settings is on screen: an old failure is not news. */
    fun shown() {
        away = false
        local.update { it.copy(error = null) }
    }

    /** Settings has gone: a pairing window left open closes with it (D10). */
    fun left() {
        away = true
        if (controls.offer.value != null) controls.close()
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}
