package com.naeblis11.mealplanner.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LaunchGuardTest {
    @Test
    fun aSecondTapWaitsForTheFirstResult() {
        val guard = LaunchGuard()
        var opened = 0
        guard.launch { opened++ }
        guard.launch { opened++ }
        assertEquals(1, opened)
        guard.done()
        guard.launch { opened++ }
        assertEquals(2, opened)
    }

    @Test
    fun aLaunchThatFailsDoesNotLockTheButton() {
        val guard = LaunchGuard()
        assertThrows(IllegalStateException::class.java) { guard.launch { throw IllegalStateException("no app") } }
        var opened = 0
        guard.launch { opened++ }
        assertEquals(1, opened)
    }
}
