package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.app.SettingsStore
import com.naeblis11.mealplanner.settings.JOIN_OFFER_DISMISSED_KEY
import com.naeblis11.mealplanner.settings.JoinOfferControls
import com.naeblis11.mealplanner.sync.FoundPc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stage 4a, R8: on a PC's true first run ([firstRun]: no database in the app data folder at this start), [look] looks
 * once ([find], JmdnsPcFinder through the client) and offers the first main PC found. Nothing is offered once this PC is
 * a client ([isClient]), nor after Keep this PC separate, kept in [settings] for good; the offer goes once this PC joins.
 * Nothing joins by itself.
 */
class DesktopJoinOffer(
    private val settings: SettingsStore,
    private val firstRun: Boolean,
    private val isClient: () -> Boolean,
    private val find: suspend () -> List<FoundPc>,
) : JoinOfferControls {
    private val _offer = MutableStateFlow<String?>(null)
    override val offer: StateFlow<String?> = _offer.asStateFlow()

    private fun dismissed(): Boolean = settings.getString(JOIN_OFFER_DISMISSED_KEY) == "1"

    /** The one look; a failure offers nothing. */
    suspend fun look() {
        if (!firstRun || isClient() || dismissed()) return
        val found = try {
            find()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (isClient() || dismissed()) return
        _offer.value = found.firstOrNull()?.name
    }

    /** Joined (or left): an offer still up goes. */
    fun roleChanged() {
        if (isClient()) _offer.value = null
    }

    override fun keepSeparate() {
        settings.put(mapOf(JOIN_OFFER_DISMISSED_KEY to "1"))
        _offer.value = null
    }

    companion object {
        /** How long after start the look waits, so the network and the main PC's announcement are up (R8). */
        const val LOOK_DELAY_MILLIS = 5_000L
    }
}
