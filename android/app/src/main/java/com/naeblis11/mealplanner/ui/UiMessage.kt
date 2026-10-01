package com.naeblis11.mealplanner.ui

import java.util.concurrent.atomic.AtomicLong

/**
 * A one-off message for a screen's snackbar. Each has its own [id], so the same words
 * twice in a row are two messages: both are shown, and consuming one never clears the other.
 */
data class UiMessage(val text: String, val id: Long = ids.incrementAndGet()) {
    private companion object {
        val ids = AtomicLong()
    }
}
