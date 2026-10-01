package com.naeblis11.mealplanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
