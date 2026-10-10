package com.naeblis11.mealplanner.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's update check as the screens see it, ready at once on the main thread (P8-F1). Making Updates reads
 * files and settings (the updates folder's real path, SharedPreferences, the package's version, the release key),
 * so [build] runs on [background], never on the thread that asks for this. Until it has run, [state] says nothing
 * (not offered, with no reason: Settings' Updates section is blank for that moment and no notice shows); then it
 * follows the built Updates' state. A tap before then waits for it and runs on [main], as it would
 * have. A [build] that fails leaves the section blank and is logged; the app goes on.
 */
class DeferredUpdates(
    private val build: () -> Updates,
    private val background: CoroutineDispatcher = Dispatchers.IO,
    private val main: CoroutineDispatcher = Dispatchers.Main,
) : UpdateControls {
    private val scope = CoroutineScope(SupervisorJob() + background)
    private val built = CompletableDeferred<Updates>()

    @Volatile
    private var real: Updates? = null

    private val _state = MutableStateFlow(UpdateStatus(offered = false))

    override val state: StateFlow<UpdateStatus> = _state.asStateFlow()

    init {
        scope.launch {
            val updates = try {
                build()
            } catch (e: Throwable) {
                // Throwable, not Exception: an Error here would otherwise escape the scope and crash the app.
                System.err.println("Meal Planner: update check: making it failed (${e.javaClass.simpleName})")
                return@launch
            }
            real = updates
            built.complete(updates)
            updates.state.collect { _state.value = it }
        }
    }

    /** Runs [block] with the built Updates on the background thread, once it is built (the launch check). */
    fun onBuilt(block: (Updates) -> Unit) {
        scope.launch { block(built.await()) }
    }

    /** The built Updates, or null while it is still being made (or couldn't be). Never builds it. */
    val builtOrNull: Updates? get() = real

    override fun checkNow() = whenReady { it.checkNow() }

    override fun setAutomatic(on: Boolean) = whenReady { it.setAutomatic(on) }

    override fun install() = whenReady { it.install() }

    override fun openInstallPermission() = whenReady { it.openInstallPermission() }

    override fun dismissNotice() = whenReady { it.dismissNotice() }

    // Built: at once, on the caller's thread, as before. Not yet: on [main] once it is.
    private fun whenReady(block: (Updates) -> Unit) {
        val updates = real
        if (updates != null) {
            block(updates)
        } else {
            scope.launch {
                val ready = built.await()
                withContext(main) { block(ready) }
            }
        }
    }
}
