package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.settings.PendingReveal
import com.naeblis11.mealplanner.settings.ServerControls
import com.naeblis11.mealplanner.settings.ServerStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The server as Settings sees it, without one: making a token ("abc123", then "def456") sets it up on the home
 * network and offers it to [pendingReveal]; Copy is recorded on a pretend clipboard.
 */
class FakeControls(initial: ServerStatus) : ServerControls {
    val flow = MutableStateFlow(initial)
    override val status: StateFlow<ServerStatus> = flow
    override val pendingReveal = PendingReveal()
    val copied = CopyOnWriteArrayList<String>()

    @Volatile
    var clipboard: String? = null

    @Volatile
    var created = 0

    @Volatile
    var failWith: Exception? = null

    @Volatile
    var copyFails: Exception? = null

    /** When set, a create waits for it: a create still under way. */
    @Volatile
    var gate: CountDownLatch? = null

    /** Counted down when a create starts. */
    val entered = CountDownLatch(1)

    override fun createToken(): String {
        entered.countDown()
        gate?.await(5, TimeUnit.SECONDS)
        failWith?.let { throw it }
        val made = if (created == 0) "abc123" else "def456"
        created++
        flow.value = flow.value.copy(onLan = true, tokenConfigured = true)
        pendingReveal.offer(made)
        return made
    }

    override fun copy(text: String) {
        copyFails?.let { throw it }
        copied += text
        clipboard = text
    }

    override fun clearClipboard(ifHolding: String) {
        if (clipboard == ifHolding) clipboard = ""
    }
}
