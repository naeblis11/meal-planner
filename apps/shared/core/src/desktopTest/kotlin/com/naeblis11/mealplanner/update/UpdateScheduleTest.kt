package com.naeblis11.mealplanner.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateScheduleTest {
    private val now = 1_000_000_000_000L
    private val day = UpdateSchedule.DAY_MILLIS

    @Test
    fun theFirstLaunchChecks() {
        assertTrue(UpdateSchedule.launchCheckDue(automatic = true, lastAttempt = null, now = now))
    }

    @Test
    fun withinADayItWaits() {
        assertFalse(UpdateSchedule.launchCheckDue(automatic = true, lastAttempt = now - day + 1, now = now))
        assertFalse(UpdateSchedule.launchCheckDue(automatic = true, lastAttempt = now, now = now))
    }

    @Test
    fun aDayLaterItChecks() {
        assertTrue(UpdateSchedule.launchCheckDue(automatic = true, lastAttempt = now - day, now = now))
    }

    @Test
    fun aClockSetBackChecks() {
        // A last check "in the future" would otherwise hold every check back until the clock caught up.
        assertTrue(UpdateSchedule.launchCheckDue(automatic = true, lastAttempt = now + day, now = now))
    }

    @Test
    fun switchedOffItNeverChecksAtLaunch() {
        assertFalse(UpdateSchedule.launchCheckDue(automatic = false, lastAttempt = null, now = now))
        assertFalse(UpdateSchedule.launchCheckDue(automatic = false, lastAttempt = now - 10 * day, now = now))
    }

    @Test
    fun aCorruptStoredValueChecks() {
        assertTrue(UpdateSchedule.launchCheckDue(automatic = true, lastAttempt = Long.MIN_VALUE, now = now))
    }
}
