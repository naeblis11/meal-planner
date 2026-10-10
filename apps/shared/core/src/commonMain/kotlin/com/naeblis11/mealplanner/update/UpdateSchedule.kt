package com.naeblis11.mealplanner.update

/** Spec "Updates": each app checks at launch at most once a day, and never at launch with Automatically off. */
object UpdateSchedule {
    const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * Whether this launch checks: Automatically on, and no attempt yet, or the last one a day or more ago, or one dated
     * after [now] (a clock set back). Attempts count, not successes, so a PC without a network doesn't try at every
     * launch. Settings' Check for updates is never held back by this.
     */
    fun launchCheckDue(automatic: Boolean, lastAttempt: Long?, now: Long): Boolean {
        if (!automatic) return false
        if (lastAttempt == null || now < lastAttempt) return true
        // Compared against now - DAY so a corrupt stored value (Long.MIN_VALUE) can't overflow.
        return lastAttempt <= now - DAY_MILLIS
    }
}
