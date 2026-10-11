package com.naeblis11.mealplanner.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.settings.SyncAttention
import com.naeblis11.mealplanner.sync.HeldRemoval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The confirmation before removing held sync removals: how many recipes, and the planned meals that go too (null: not
 * known), and the [uuids] it asks about, which are exactly what Remove removes.
 */
data class SyncRemoveAsk(val recipes: Int, val plannedMeals: Int?, val uuids: Set<String>)

const val SYNC_REMOVE = "Remove them"
const val SYNC_KEEP = "Keep them"
const val SYNC_REMOVE_FAILED = "Couldn't remove them. Try again in a moment."
const val SYNC_KEEP_FAILED = "Couldn't keep them. Try again in a moment."

/** Needs attention's row for held sync removals. */
fun syncHeldText(recipes: Int): String =
    if (recipes == 1) "1 recipe would be removed by a sync from another device." else "$recipes recipes would be removed by a sync from another device."

/** The Recipes banner while sync removals are held. */
fun syncHeldBanner(recipes: Int): String =
    if (recipes == 1) "1 recipe is waiting under Needs attention." else "$recipes recipes are waiting under Needs attention."

/** The confirmation's question: removing takes the planned meals with the recipes, said before it is done. */
fun syncRemoveQuestion(recipes: Int, plannedMeals: Int?): String {
    val what = if (recipes == 1) "1 recipe" else "$recipes recipes"
    val them = if (recipes == 1) "it" else "them"
    val meals = when {
        plannedMeals == null || plannedMeals == 0 -> ""
        plannedMeals == 1 -> " 1 planned meal uses $them and will be removed too."
        else -> " $plannedMeals planned meals use $them and will be removed too."
    }
    val files = if (recipes == 1) "Its file goes" else "Their files go"
    return "Remove $what from this PC?$meals $files to the Recycle Bin."
}

/**
 * Recipes a sync from another device would remove, held for the user (LAN sync stage 2b, D11). Remove them asks first,
 * with the count of planned meals when it can be had, and removes exactly the recipes it asked about; Keep them sends
 * the ones shown back to the device that removed them. A removal held meanwhile stays held. One at a time: a tap while
 * one works does nothing, and leaves an open question open.
 */
class SyncAttentionViewModel(private val attention: SyncAttention) : ViewModel() {
    val held: StateFlow<List<HeldRemoval>> = attention.held

    private val _ask = MutableStateFlow<SyncRemoveAsk?>(null)
    val ask: StateFlow<SyncRemoveAsk?> = _ask.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** "Remove them": counts, then asks. */
    fun askRemove() {
        viewModelScope.launch {
            val shown = held.value.map { it.uuid }.toSet()
            if (shown.isEmpty()) return@launch
            val meals = try {
                attention.plannedMeals(shown)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _ask.value = SyncRemoveAsk(shown.size, meals, shown)
        }
    }

    fun cancelRemove() {
        _ask.value = null
    }

    /** "Remove" in the confirmation: the question closes only once the removal starts. */
    fun confirmRemove() {
        val ask = _ask.value ?: return
        if (!_busy.compareAndSet(false, true)) return
        if (!_ask.compareAndSet(ask, null)) {
            _busy.value = false
            return
        }
        startAction(SYNC_REMOVE_FAILED) { attention.confirm(ask.uuids) }
    }

    /** "Keep them": the ones shown now. */
    fun keep() {
        val shown = held.value.map { it.uuid }.toSet()
        if (shown.isEmpty()) return
        if (!_busy.compareAndSet(false, true)) return
        startAction(SYNC_KEEP_FAILED) { attention.keep(shown) }
    }

    fun messageShown() {
        _message.value = null
    }

    // Busy is already set by the caller.
    private fun startAction(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = failure
            } finally {
                _busy.value = false
            }
        }
    }
}
