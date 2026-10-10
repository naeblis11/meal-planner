package com.naeblis11.mealplanner.desktop.peers

import com.naeblis11.mealplanner.settings.PeerControls
import com.naeblis11.mealplanner.settings.PeerNotice
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Other Meal Planner PCs on the network (P6-R6, P6-R7). [start] announces this PC ([own], for the server's [port]) and
 * looks once; [browseNow] looks again when Settings opens, and [lookNow] before each send to Google (P6-FR1). [status]
 * changes only after a look; a change of the PC this
 * one yields to calls [onYieldChanged] (Main rebinds the server). A look takes [browseMillis].
 * P6-T4a: while there is no network to announce on (the installed app starts with Windows, often before Wi-Fi is up),
 * the announce is tried again every [retryMillis] and whenever Settings opens; once it works, nothing polls again.
 * P6-R11: [log] gets counts, the yield decision and exception class names only, never a peer's name or other data.
 */
class PeerWatch(
    private val discovery: PeerDiscovery,
    private val own: suspend () -> PeerRecord,
    private val port: Int,
    private val onYieldChanged: () -> Unit = {},
    private val browseMillis: Long = BROWSE_MILLIS,
    private val retryMillis: Long = RETRY_MILLIS,
    private val log: (String) -> Unit = { System.err.println(it) },
) : PeerControls {
    private val _status = MutableStateFlow(PeerStatus())
    val status: StateFlow<PeerStatus> = _status.asStateFlow()

    private val _notice = MutableStateFlow<PeerNotice?>(null)
    override val notice: StateFlow<PeerNotice?> = _notice.asStateFlow()

    @Volatile
    private var scope: CoroutineScope? = null

    // This PC's record once own() has made it; set before the first announce.
    @Volatile
    private var record: PeerRecord? = null

    // This PC's record once it is announced; null until then. Set while the one-look flag is held, before it is let go.
    @Volatile
    private var mine: PeerRecord? = null

    // Why the last announce said off (NO_NETWORK or an exception's class), so a retry every minute logs only a change.
    @Volatile
    private var offReason: String? = null

    @Volatile
    private var startJob: Job? = null

    @Volatile
    private var lookJob: Job? = null

    // One announce or look at a time: taken on the asking thread, so one asked for while another runs is dropped at
    // once (the running one is just as fresh), and let go when it ends, however it ends. A flow, so lookNow can wait
    // for the one under way.
    private val browsing = MutableStateFlow(false)
    private val closed = AtomicBoolean(false)

    /** The older household's PC this one yields to (P6-R7), or null; the server's bind and the Google send ask it. */
    fun yieldTo(): String? = _status.value.yieldTo

    /**
     * Announces this PC, then looks once, all in [scope] (P6-R6); returns at once. While the announce says off, it is
     * tried again every retryMillis; the returned job ends once it has worked, or with [close].
     */
    fun start(scope: CoroutineScope): Job {
        this.scope = scope
        return scope.launch {
            if (closed.get()) return@launch
            val made = try {
                own()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Meal Planner: other Meal Planner PCs aren't looked for: ${e.javaClass.simpleName}")
                return@launch
            }
            record = made
            while (!closed.get() && mine == null) {
                // Taken by a browseNow trying at the same moment: that one is just as good, and this loop checks after.
                if (browsing.compareAndSet(false, true)) {
                    try {
                        announceThenLook(made)
                    } finally {
                        browsing.value = false
                    }
                }
                if (closed.get() || mine != null) break
                delay(retryMillis)
            }
        }.also { startJob = it }
    }

    /**
     * P6-FR1: one look before a send to Google, so a newer PC started before the older one came up still steps back
     * without Settings being opened (asked for by the user, so not polling: P6-R6). A look already under way (start's,
     * or Settings') is waited for instead of starting another. Off (no network yet), not started or closed: it returns
     * at once and the send goes on what is known. Waits [browseMillis] plus LOOK_SLACK_MILLIS at most; a look still
     * going then carries on in the background.
     */
    suspend fun lookNow() {
        if (closed.get()) return
        withTimeoutOrNull(browseMillis + LOOK_SLACK_MILLIS) {
            val job = if (mine != null) launchLook() else null
            if (job != null) job.join() else browsing.first { !it }
        }
    }

    override fun browseNow() {
        launchLook()
    }

    // The one look (or, while still off, the announce again: P6-T4a) in scope; null when one is under way already, or
    // nothing can look (not started, or closed).
    private fun launchLook(): Job? {
        val where = scope ?: return null
        val made = record ?: return null
        if (closed.get() || !browsing.compareAndSet(false, true)) return null
        val job = where.launch { if (mine == null) announceThenLook(made) else look(made) }
        // Also when the scope was cancelled before it began, so the flag is never left taken.
        job.invokeOnCompletion { browsing.value = false }
        lookJob = job
        return job
    }

    // With the one-look flag held. On: this PC is announced and looks once. Off (P6-R5): the status says so, and the
    // log says why once per change of reason.
    private suspend fun announceThenLook(made: PeerRecord) {
        if (closed.get() || mine != null) return
        val failure = try {
            if (runInterruptible(Dispatchers.IO) { discovery.announce(made, port) }) null else NO_NETWORK
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.javaClass.simpleName
        }
        if (closed.get()) return
        if (failure != null) {
            _status.value = PeerStatus(off = true)
            if (failure != offReason) {
                offReason = failure
                log(
                    if (failure == NO_NETWORK) {
                        "Meal Planner: no home network found, so other Meal Planner PCs aren't looked for yet; tried again every ${interval(retryMillis)}."
                    } else {
                        "Meal Planner: announcing this PC failed: $failure; tried again every ${interval(retryMillis)}."
                    },
                )
            }
            return
        }
        if (offReason != null) log("Meal Planner: a home network is there now, so other Meal Planner PCs are looked for.")
        offReason = null
        mine = made
        _status.value = PeerStatus()
        look(made)
    }

    private suspend fun look(made: PeerRecord) {
        if (closed.get()) return
        val next = try {
            val seen = runInterruptible(Dispatchers.IO) { discovery.browse(browseMillis) }
            if (closed.get()) return
            PeerDecision.decide(made, seen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Meal Planner: looking for other Meal Planner PCs failed: ${e.javaClass.simpleName}")
            return
        }
        val before = _status.value.yieldTo
        _status.value = next
        _notice.value = next.notice?.let { PeerNotice(it, alexa = next.yieldTo?.let(PeerMessages::yieldAlexa)) }
        if (next.yieldTo != before) {
            log(lookedMessage(next.peers.size, next.yieldTo != null))
            try {
                onYieldChanged()
            } catch (e: Exception) {
                log("Meal Planner: listening again after the other-PC check failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Stops announcing (bounded, about a second) and cancels a look under way without waiting for it; later looks are
     * ignored. Calling it again does nothing.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            discovery.close()
        } finally {
            startJob?.cancel()
            lookJob?.cancel()
        }
    }

    companion object {
        const val BROWSE_MILLIS = 5_000L

        /** How much longer than a look lookNow waits: JmDNS's replay of records it holds runs on the browsing thread. */
        const val LOOK_SLACK_MILLIS = 1_000L

        /** How often an announce that said off is tried again (P6-T4a): the server's port-in-use retry's minute. */
        const val RETRY_MILLIS = 60_000L

        private const val NO_NETWORK = "no network"

        // The retry interval as the log says it: whole seconds when it is that ("60 s"), else milliseconds.
        private fun interval(millis: Long): String = if (millis >= 1_000L && millis % 1_000L == 0L) "${millis / 1_000L} s" else "$millis ms"

        /** P6-R11, P6-PF3: how many other PCs were seen and whether this one yields; never a name. */
        fun lookedMessage(others: Int, yields: Boolean): String {
            val pcs = if (others == 1) "1 other Meal Planner PC" else "$others other Meal Planner PCs"
            return "Meal Planner: $pcs on the network; yields to an older household: ${if (yields) "yes" else "no"}."
        }
    }
}
