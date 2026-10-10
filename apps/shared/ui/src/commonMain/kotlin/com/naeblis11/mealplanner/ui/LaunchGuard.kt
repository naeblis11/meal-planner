package com.naeblis11.mealplanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Opens an outside activity (a file picker, the camera) at most once until its result
 * comes back. Unlike navigation, launching doesn't change this screen's lifecycle
 * until the other activity is up, so a fast double tap would otherwise open two.
 */
class LaunchGuard {
    private var open = false

    /** Runs [launch] unless a launch is still waiting for its result. A launch that throws frees the guard. */
    fun launch(launch: () -> Unit) {
        if (open) return
        open = true
        try {
            launch()
        } catch (e: Throwable) {
            open = false
            throw e
        }
    }

    /** The result arrived (or the user backed out): the next tap may launch again. */
    fun done() {
        open = false
    }
}

@Composable
fun rememberLaunchGuard(): LaunchGuard = remember { LaunchGuard() }
