package com.naeblis11.mealplanner.desktop

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test

/**
 * The screens' ViewModels are cleared, and so their viewModelScopes cancelled, before the app closes: a Room query
 * still running in one would otherwise fail on the closed database on no test's thread, and kotlinx-coroutines-test
 * would throw it at a later, unrelated runTest.
 */
class CloseAfterComposeTest {
    @Volatile
    private var probe: Probe? = null

    // Stands for the app's close: by then the probe must be cleared and its scope's work cancelled.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({
        val seen = checkNotNull(probe) { "the screen never made its ViewModel" }
        check(seen.cleared) { "the screens' ViewModels were still live when the app closed" }
        check(seen.work?.isCancelled == true) { "a viewModelScope was still running when the app closed" }
    })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    class Probe : ViewModel() {
        @Volatile
        var cleared = false

        // Stands for a Room query collected in viewModelScope.
        val work: Job? = viewModelScope.launch { awaitCancellation() }

        override fun onCleared() {
            cleared = true
        }
    }

    @Test
    fun theScreensViewModelsAreClearedBeforeTheAppCloses() {
        compose.showAt(400.dp) {
            probe = viewModel { Probe() }
            Text("shown")
        }
        compose.waitForText("shown")
    }

    @Test
    fun setTestContentKeepsThemInTheTestToo() {
        compose.setTestContent {
            probe = viewModel { Probe() }
            Text("shown")
        }
        compose.waitForText("shown")
    }
}
