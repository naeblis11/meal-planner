package com.naeblis11.mealplanner.settings

import kotlinx.coroutines.flow.StateFlow

/** A paired phone or PC as Settings lists it; [lastSync] is when it last synced, null for never. */
data class DeviceRow(val deviceId: String, val name: String, val kind: String, val lastSync: Long?)

/** A QR code's modules, [size] by [size]; [isDark] is a dark module at column [x], row [y]. */
class QrModules(val size: Int, private val dark: BooleanArray) {
    init {
        require(size > 0 && dark.size == size * size) { "A QR code is size x size modules." }
    }

    fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]
}

/**
 * An open pairing window as Settings shows it: the 6-digit [code], when it closes, the PC's home-network [addresses],
 * and the QR code ([qrText], drawn from [qr]). Both are null when the PC has no home-network address; the code is still
 * shown. [toString] never shows the code.
 */
data class PairingView(val code: String, val expiresAt: Long, val addresses: List<String>, val qrText: String?, val qr: QrModules?) {
    override fun toString(): String = "PairingView(expiresAt=$expiresAt, addresses=$addresses)"
}

/** Settings' "Phones and PCs" (LAN sync stage 2b): the desktop's DesktopSync, behind an interface; null on Android. */
interface PairingControls {
    val devices: StateFlow<List<DeviceRow>>

    /** The open pairing window, or null. */
    val offer: StateFlow<PairingView?>

    val status: StateFlow<SyncServerStatus>

    /** "This PC's sync certificate was remade; pair your devices again." once that happened this run; else null. */
    val certificateNotice: StateFlow<String?>

    /**
     * Opens a 5-minute pairing window with a new code (making the certificate on first use). Blocking work: call it off
     * the main thread. Throws when the window can't be opened (the certificate can't be made); nothing is open then.
     */
    suspend fun open()

    /** Closes the pairing window. Never blocks. */
    fun close()

    /**
     * Unpairs [deviceId]: it keeps its data, and its next sync is told it was removed (401 `unpaired`): the sync port
     * stays open for 30 days after the latest removal, even with nothing left paired, so the device can hear it. Two
     * exceptions for stage 2c: while this PC yields to an older household's PC the port is closed, so the device finds
     * it unreachable; and `not_master` (409) reaches a device only when the yield starts while its connection is open.
     */
    suspend fun remove(deviceId: String)
}
