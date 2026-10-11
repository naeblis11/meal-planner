package com.naeblis11.mealplanner.settings

/** Where the desktop's sync port is (LAN sync, stage 2b). OFF: closed, as it is with nothing paired and no pairing window. */
enum class SyncServerState { OFF, STARTING, LISTENING, PORT_IN_USE, FAILED }

data class SyncServerStatus(val state: SyncServerState, val port: Int)

/** Settings' line while another program holds the sync port (D10). */
fun syncPortInUseNotice(port: Int): String =
    "Port $port is in use by another program, so devices can't sync. Retrying every minute."
