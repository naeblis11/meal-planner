package com.naeblis11.mealplanner.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import javax.swing.SwingUtilities
import org.junit.rules.ExternalResource

/**
 * Clicks on the UI thread. performClick() injects the pointer events on the test thread, and navigating then moves
 * a NavBackStackEntry's lifecycle there, which LifecycleRegistry rejects. A real click arrives on the Swing thread.
 */
fun SemanticsNodeInteraction.click() {
    performSemanticsAction(SemanticsActions.OnClick)
}

/** Waits up to [millis] for a node whose text is exactly [text]. */
fun ComposeContentTestRule.waitForText(text: String, millis: Long = 5_000) =
    waitUntil(millis) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

/**
 * [content] in a [width] x [height] box at density 1, so a test chooses the narrow or the wide layout. The test
 * window is 1024 x 768; a wider box overflows it evenly on both sides, which the bounds checks don't mind.
 */
fun ComposeContentTestRule.showAt(width: Dp, height: Dp = 760.dp, content: @Composable () -> Unit) = setTestContent {
    CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(width, height)) { MealPlannerTheme { content() } }
    }
}

/**
 * setContent, with the screens' ViewModels kept in the test's CloseAfterCompose (when it has one), which clears them
 * before it closes the app: see [CloseAfterCompose.viewModels]. Full-app UI tests use this or [showAt].
 */
fun ComposeContentTestRule.setTestContent(content: @Composable () -> Unit) {
    val owner = CloseAfterCompose.current()?.viewModels
    setContent {
        if (owner == null) content() else CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
    }
}

/** The ViewModels of a UI test's screens, held apart from the test window's own store so the test can clear them. */
class TestViewModels : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    /**
     * Clears them on the UI thread (Dispatchers.Main there, where their viewModelScopes run): first what is already
     * queued there, then the clear, which cancels every viewModelScope, then the cancellations it dispatched there.
     */
    fun clear() {
        SwingUtilities.invokeAndWait {}
        SwingUtilities.invokeAndWait { viewModelStore.clear() }
        SwingUtilities.invokeAndWait {}
    }
}

/**
 * Closes what a UI test opened (the app and its database, temp folders) after the compose rule has disposed the
 * composition. Declare it as `@get:Rule(order = 0)` with the compose rule at order 1: the outer rule's [after] runs
 * last. An @After method, or a finally in the test, runs inside the compose rule while the screens are still composed
 * and their ViewModels still query Room, so closing the database there failed a test now and then with "Connection
 * pool is closed". Actions run last added first; every one runs, and the first failure is thrown with the others
 * suppressed.
 *
 * Before any action, the screens' [viewModels] (given them by [showAt] and [setTestContent]) are cleared. The test
 * window's own ViewModelStore outlives the composition, so a viewModelScope's Room query could otherwise run on past
 * the app's close and fail with "Connection pool is closed" on no test's thread; kotlinx-coroutines-test keeps such an
 * exception and throws it at the next runTest (Ktor's testApplication), failing an unrelated test.
 */
class CloseAfterCompose(vararg first: () -> Unit) : ExternalResource() {
    private val actions = ArrayDeque<() -> Unit>().apply { first.forEach { addFirst(it) } }

    /** The screens' ViewModels, cleared before the actions run (so before the app's database closes). */
    val viewModels = TestViewModels()

    /** Runs [action] once the composition is gone. */
    fun add(action: () -> Unit) {
        synchronized(actions) { actions.addFirst(action) }
    }

    override fun before() {
        CURRENT.set(this)
    }

    override fun after() {
        CURRENT.remove()
        var failure: Throwable? = null
        try {
            viewModels.clear()
        } catch (t: Throwable) {
            failure = t
        }
        while (true) {
            val next = synchronized(actions) { actions.removeFirstOrNull() } ?: break
            try {
                next()
            } catch (t: Throwable) {
                failure?.addSuppressed(t) ?: run { failure = t }
            }
        }
        failure?.let { throw it }
    }

    companion object {
        // The test thread's CloseAfterCompose: JUnit runs a rule's before, the test and its after on one thread.
        private val CURRENT = ThreadLocal<CloseAfterCompose>()

        /** The CloseAfterCompose of the test running on this thread, if it has one. */
        fun current(): CloseAfterCompose? = CURRENT.get()
    }
}

/** A tab of the bottom bar or the rail. */
fun ComposeContentTestRule.tab(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
