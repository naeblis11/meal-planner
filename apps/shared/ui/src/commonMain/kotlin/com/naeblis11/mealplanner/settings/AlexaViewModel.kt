package com.naeblis11.mealplanner.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The desktop's built-in server for Settings (P4-R6), behind an interface so shared code never sees Ktor; null on Android. */
interface ServerControls {
    val status: StateFlow<ServerStatus>

    /**
     * A token made but not yet on screen. It lives with the controls, which outlive Settings, so a Settings left or
     * closed while the token was being made can't lose it: the open Settings, or the next one, shows it once.
     */
    val pendingReveal: PendingReveal

    /**
     * Makes the Alexa token (a new one replaces the one in use), saves it in the secrets file, moves the server onto
     * the home network, then offers it to [pendingReveal]. Blocking; throws when it can't be saved.
     */
    fun createToken(): String

    /** Puts [text] on the clipboard; throws when the clipboard can't be had (another program holds it). */
    fun copy(text: String)

    /** Empties the clipboard if it still holds [ifHolding]; leaves anything copied since. */
    fun clearClipboard(ifHolding: String)
}

/** A token waiting to be shown (ServerControls.pendingReveal): offered once made, taken once by the Settings that shows it. */
class PendingReveal {
    private var token: String? = null
    private val _waiting = MutableStateFlow(false)

    /** Whether a token is waiting; the token itself is never in a flow. */
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    @Synchronized
    fun offer(made: String) {
        token = made
        _waiting.value = true
    }

    /** The waiting token, once; null when there is none. */
    @Synchronized
    fun take(): String? {
        val waitingToken = token
        token = null
        _waiting.value = false
        return waitingToken
    }

    override fun toString(): String = "PendingReveal(waiting=${_waiting.value})"
}

/**
 * Settings' "Chrome extension and Alexa" panel. [newToken] is shown once, right after it was made; [busy] while it is
 * being made and the server moves onto the home network. [toString] never shows the token.
 */
data class AlexaState(
    val status: ServerStatus,
    val newToken: String? = null,
    val copied: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    override fun toString(): String =
        "AlexaState(status=$status, newToken=${if (newToken != null) "hidden" else "null"}, copied=$copied, busy=$busy, error=$error)"
}

/** What goes in Home Assistant's secrets.yaml (alexa/home-assistant.yaml reads it as !secret meal_planner_auth). */
fun secretsLine(token: String): String = "meal_planner_auth: \"Bearer $token\""

const val TOKEN_NOT_SAVED = "Couldn't save the token in the secrets file. Try again in a moment."
const val COPY_FAILED = "Couldn't copy. Select the line and copy it instead."

/**
 * Settings' server panel: the server's status, and Create (or Make a new) token, shown once with a Copy.
 *
 * The create runs off the main thread (the server's rebind waits up to its start timeout) and can't be cancelled
 * halfway: a token saved in the secrets file is always offered to ServerControls.pendingReveal. While Settings is on
 * screen ([shown]) it is taken and shown here; once Settings is [left] the shown token is dropped from memory, and a
 * create that finishes meanwhile leaves its token waiting for Settings' return.
 */
class AlexaViewModel(
    private val controls: ServerControls,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    // Not a data class: its toString would show the token.
    private class Local(val newToken: String? = null, val copied: Boolean = false, val busy: Boolean = false, val error: String? = null) {
        fun copy(
            newToken: String? = this.newToken,
            copied: Boolean = this.copied,
            busy: Boolean = this.busy,
            error: String? = this.error,
        ) = Local(newToken, copied, busy, error)
    }

    private val local = MutableStateFlow(Local())

    @Volatile
    private var onScreen = true

    val state: StateFlow<AlexaState> = combine(controls.status, local) { status, mine ->
        AlexaState(status, mine.newToken, mine.copied, mine.busy, mine.error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AlexaState(controls.status.value))

    init {
        // A token made by a Settings since closed, or while this one was away, is shown as soon as it is here.
        viewModelScope.launch { controls.pendingReveal.waiting.collect { if (it) reveal() } }
    }

    /** Makes a token, or a new one in place of the one set up. Ignored while one is being made. */
    fun createToken() {
        if (local.value.busy) return
        local.value = Local(busy = true)
        viewModelScope.launch {
            val saved = try {
                withContext(NonCancellable + ioDispatcher) { controls.createToken() }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            reveal()
            local.update { it.copy(busy = false, error = if (saved) it.error else TOKEN_NOT_SAVED) }
        }
    }

    /** Copies the whole line for Home Assistant's secrets.yaml; a clipboard that refuses says to select it instead. */
    fun copyToken() {
        val token = local.value.newToken ?: return
        try {
            controls.copy(secretsLine(token))
            local.update { it.copy(copied = true, error = null) }
        } catch (e: Exception) {
            local.update { it.copy(copied = false, error = COPY_FAILED) }
        }
    }

    /**
     * The token leaves the screen, and memory, for good; the clipboard too, while it still holds the line. A copy
     * failure goes with it: it was about the line.
     */
    fun tokenDone() {
        val token = local.value.newToken
        local.update { it.copy(newToken = null, copied = false, error = null) }
        if (token != null) {
            try {
                controls.clearClipboard(secretsLine(token))
            } catch (e: Exception) {
                // Best effort: the clipboard may be held by another program.
            }
        }
    }

    /** Settings is on screen: a token waiting there is shown now. */
    fun shown() {
        onScreen = true
        reveal()
    }

    /**
     * Settings has gone: the token on screen is dropped from memory (the clipboard is left, so it can still be pasted
     * into Home Assistant). A create under way leaves its token waiting for Settings' return.
     */
    fun left() {
        onScreen = false
        local.update { it.copy(newToken = null, copied = false) }
    }

    private fun reveal() {
        if (!onScreen) return
        val token = controls.pendingReveal.take() ?: return
        local.update { it.copy(newToken = token, copied = false, error = null) }
    }
}
