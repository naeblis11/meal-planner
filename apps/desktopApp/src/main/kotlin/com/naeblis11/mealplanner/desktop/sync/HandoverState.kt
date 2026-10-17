package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.settings.HandoverView
import com.naeblis11.mealplanner.sync.HandoverRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The main PC's hand-over as it goes (LAN sync stage 4b, R1, R2): nothing; offered to one paired PC until
 * [OFFER_MILLIS] runs out or it is cancelled; then, once that PC has taken the bundle, handing over for up to
 * [WAIT_MILLIS] while the PC says it is done, every other request answered `not_master`. [view] is what Settings shows.
 * Thread-safe; every step reads the clock, so an offer or a wait that ran out is simply gone.
 */
class HandoverState(private val clock: () -> Long = System::currentTimeMillis) {
    private sealed class Step {
        abstract val deviceId: String
        abstract val name: String
        abstract val until: Long

        class Offered(override val deviceId: String, override val name: String, override val until: Long) : Step()

        class Handing(override val deviceId: String, override val name: String, override val until: Long, val request: HandoverRequest) : Step()
    }

    private var step: Step? = null // guarded by this
    private val _view = MutableStateFlow<HandoverView?>(null)
    val view: StateFlow<HandoverView?> = _view.asStateFlow()

    // The step now; one that ran out is dropped (and the view cleared) first.
    private fun current(): Step? {
        val now = step ?: return null
        if (clock() >= now.until) set(null)
        return step
    }

    private fun set(next: Step?) {
        step = next
        _view.value = next?.let { HandoverView(it.deviceId, it.name, it.until, handing = it is Step.Handing) }
    }

    /** Offers the role to [deviceId] (a PC named [name]), in place of any offer before; not while one is handing over. */
    @Synchronized
    fun offer(deviceId: String, name: String): Boolean {
        if (current() is Step.Handing) return false
        set(Step.Offered(deviceId, name, clock() + OFFER_MILLIS))
        return true
    }

    /** Cancel (Settings), or [deviceId] removed: an offer goes; a hand-over under way runs its course. */
    @Synchronized
    fun cancel(deviceId: String? = null) {
        val now = current() as? Step.Offered ?: return
        if (deviceId == null || now.deviceId == deviceId) set(null)
    }

    @Synchronized
    fun offeredTo(deviceId: String): Boolean = (current() as? Step.Offered)?.deviceId == deviceId

    /** True while handing over: every request but the taker's done is answered `not_master`. */
    @Synchronized
    fun handing(): Boolean = current() is Step.Handing

    /** The offered PC takes the role: true (and handing over from now) only when it is the one offered. */
    @Synchronized
    fun take(deviceId: String, request: HandoverRequest): Boolean {
        val now = current() as? Step.Offered ?: return false
        if (now.deviceId != deviceId) return false
        set(Step.Handing(deviceId, now.name, clock() + WAIT_MILLIS, request))
        return true
    }

    /** The taker is done: its request, and the hand-over is over; null when [deviceId] isn't handing over just now. */
    @Synchronized
    fun done(deviceId: String): HandoverRequest? {
        val now = current() as? Step.Handing ?: return null
        if (now.deviceId != deviceId) return null
        set(null)
        return now.request
    }

    /** True when a hand-over's wait has run out (no done in time) and is dropped: the main PC serves as before. */
    @Synchronized
    fun expireHanding(): Boolean {
        val was = step is Step.Handing
        current()
        return was && step == null
    }

    companion object {
        /** How long an offer waits for the PC's next Sync (R1). */
        const val OFFER_MILLIS = 10L * 60 * 1000

        /** How long the main PC waits for done after the bundle was taken (R2). */
        const val WAIT_MILLIS = 30_000L
    }
}
