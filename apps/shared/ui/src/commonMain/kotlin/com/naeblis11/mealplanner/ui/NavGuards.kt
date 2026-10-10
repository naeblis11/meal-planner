package com.naeblis11.mealplanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

/**
 * [block] only while this destination is RESUMED, like `dropUnlessResumed` but for a
 * callback with an argument: once a tap starts navigating, the entry stops being
 * resumed, so a fast second tap can't navigate again. The lambda is remembered per
 * lifecycle owner and always calls the latest [block].
 */
@Composable
fun <T> dropUnlessResumedWith(block: (T) -> Unit): (T) -> Unit {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(block)
    return remember(owner) {
        { value: T -> if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) latest(value) }
    }
}

/** Pops [entry] if it is still on top, so an outcome that arrives twice can't pop the screen below it. */
fun popIfCurrent(nav: NavController, entry: NavBackStackEntry) {
    if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack()
}

/**
 * Holds a switch of section (the rail, or the phone's tabs), or the desktop's Quit, while a page has something to
 * lose. MealPlannerApp provides one (the desktop passes its own, so Quit can ask it too); the Edit and New recipe
 * forms take it while they have unsaved changes, and let the switch go ahead only once the user chooses to discard
 * them, as Back does.
 */
class LeaveGuard {
    // Compose state, so a composable that waits for the guard to be let go (the desktop's extension inbox) runs
    // again when it is.
    private var holder by mutableStateOf<((proceed: () -> Unit) -> Unit)?>(null)

    /** True while a page holds the guard: a [request] will ask before going ahead. */
    val holding: Boolean get() = holder != null

    /** Runs [proceed] now, or hands it to the page holding the guard, which runs it if the user agrees. */
    fun request(proceed: () -> Unit) {
        val current = holder
        if (current == null) proceed() else current(proceed)
    }

    /** Takes the guard; the returned function gives it back, if no other page has taken it since. */
    fun hold(onRequest: (proceed: () -> Unit) -> Unit): () -> Unit {
        holder = onRequest
        return { if (holder === onRequest) holder = null }
    }
}

/** The app's LeaveGuard; null outside MealPlannerApp (a screen on its own in a test), where nothing switches. */
val LocalLeaveGuard: ProvidableCompositionLocal<LeaveGuard?> = staticCompositionLocalOf { null }
