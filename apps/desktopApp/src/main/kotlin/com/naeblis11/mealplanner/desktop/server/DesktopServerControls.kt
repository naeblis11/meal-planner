package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.settings.PendingReveal
import com.naeblis11.mealplanner.settings.ServerControls
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings' view of the built-in server (P4-R6): Create a token (or Make a new token) saves it, then the server
 * rebinds onto the home network. createToken blocks (the rebind waits for a bind under way, then binds, each up to
 * KtorEngine.START_TIMEOUT_MILLIS): AlexaViewModel calls it off the UI thread. It lives as long as the app, so the
 * token it made waits in [pendingReveal] for a Settings to show it, even one opened after the create began.
 */
class DesktopServerControls(
    private val server: AppServer,
    private val token: ApiToken,
    private val clipboard: (String) -> Unit = ::toSystemClipboard,
    private val readClipboard: () -> String? = ::fromSystemClipboard,
    private val log: (String) -> Unit = { System.err.println(it) },
) : ServerControls {
    override val status: StateFlow<ServerStatus> get() = server.status

    override val pendingReveal = PendingReveal()

    // One at a time, so a token saved is always the one the server rebinds with.
    @Synchronized
    override fun createToken(): String {
        val made = try {
            token.create()
        } catch (e: Exception) {
            // The class only, never the message.
            log("Meal Planner: couldn't save the Alexa token: ${e.javaClass.simpleName}")
            throw e
        }
        try {
            server.rebind()
        } finally {
            // Saved, so it is in use whatever the rebind did: it must reach the screen.
            pendingReveal.offer(made)
        }
        return made
    }

    override fun copy(text: String) = clipboard(text)

    override fun clearClipboard(ifHolding: String) {
        try {
            if (readClipboard() == ifHolding) clipboard("")
        } catch (e: Exception) {
            // Best effort: another program may hold the clipboard.
        }
    }
}

/** Windows' clipboard. Throws IllegalStateException while another program holds it. */
internal fun toSystemClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

/** The text on Windows' clipboard; null when it holds something else. */
internal fun fromSystemClipboard(): String? {
    val board = Toolkit.getDefaultToolkit().systemClipboard
    return if (board.isDataFlavorAvailable(DataFlavor.stringFlavor)) board.getData(DataFlavor.stringFlavor) as? String else null
}

/** The tray's "port in use" message (P4-R1): said once a run, the first time the server finds the port taken. */
class PortNotice {
    private var said = false

    @Synchronized
    fun take(status: ServerStatus): Boolean {
        if (said || status.state != ServerState.PORT_IN_USE) return false
        said = true
        return true
    }
}
