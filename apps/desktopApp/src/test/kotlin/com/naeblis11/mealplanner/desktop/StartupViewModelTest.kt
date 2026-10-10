package com.naeblis11.mealplanner.desktop

import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.settings.StartupState
import com.naeblis11.mealplanner.settings.StartupSwitch
import com.naeblis11.mealplanner.settings.StartupViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupViewModelTest {
    /** The Run key as a value; [writes] false makes every change fail, as a refused reg.exe would. */
    private class FakeSwitch(override val available: Boolean = true, @Volatile var on: Boolean = false, val writes: Boolean = true) : StartupSwitch {
        val sets = mutableListOf<Boolean>()

        override fun isOn() = on

        override fun setOn(on: Boolean): Boolean {
            sets += on
            if (writes) this.on = on
            return writes
        }
    }

    private val created = mutableListOf<StartupViewModel>()

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
    }

    private fun viewModel(switch: StartupSwitch) = StartupViewModel(switch).also { created += it }

    @Test
    fun itShowsTheRunKeyAndTurnsItOff() = runBlocking {
        val switch = FakeSwitch(on = true)
        val vm = viewModel(switch)
        assertEquals(StartupState(available = true, on = true), withTimeout(5_000) { vm.state.first { it != null } })
        vm.set(false)
        assertEquals(StartupState(available = true, on = false), withTimeout(5_000) { vm.state.first { it != null && !it.busy && !it.on } })
        assertEquals(listOf(false), switch.sets)
    }

    @Test
    fun aRefusedChangeSaysSoAndShowsWhatWindowsHas() = runBlocking {
        val vm = viewModel(FakeSwitch(on = false, writes = false))
        withTimeout(5_000) { vm.state.first { it != null } }
        vm.set(true)
        assertEquals(
            StartupState(available = true, on = false, error = StartupViewModel.FAILED),
            withTimeout(5_000) { vm.state.first { it?.error != null } },
        )
    }
}
