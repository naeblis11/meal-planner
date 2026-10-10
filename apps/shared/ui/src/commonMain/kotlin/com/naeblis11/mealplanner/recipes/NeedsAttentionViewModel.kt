package com.naeblis11.mealplanner.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.folder.RecipeFileProblem
import com.naeblis11.mealplanner.folder.RecipeFolderStatus
import com.naeblis11.mealplanner.folder.pointBackInstructions
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The confirmation before removing recipes held back from a mass removal: which ones (their index row [ids], the
 * ones "Remove" removes), and the planned meals that go too.
 */
data class RemoveMissingAsk(val ids: Set<Long>, val plannedMeals: Int) {
    /** How many recipes the confirmation counts. */
    val recipes: Int get() = ids.size
}

/** The Needs attention screen: the folder's problems, and "Assign new ID" for a duplicate. */
class NeedsAttentionViewModel(private val folder: RecipeFolderStatus) : ViewModel() {
    val problems: StateFlow<List<RecipeFileProblem>> = folder.problems

    private val _message = MutableStateFlow<String?>(null)

    /** A failure to show once, or null. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** Files getting a new ID right now; their button is disabled. */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /**
     * Gives [fileName] a new recipe_uuid. A second tap while the first is still working does nothing, so a
     * double tap never writes two IDs; a tap after it is fixed (no longer a listed duplicate) does nothing either.
     */
    fun assignNewId(fileName: String) {
        if (!claim(fileName)) return
        viewModelScope.launch {
            try {
                folder.assignNewId(fileName)
            } catch (e: IllegalArgumentException) {
                // Already fixed: nothing to do.
            } catch (e: IOException) {
                _message.value = "Couldn't change $fileName: ${e.message ?: "the file can't be written"}."
            } finally {
                _busy.update { it - fileName }
            }
        }
    }

    private val _removing = MutableStateFlow(false)

    /** True while "Remove them from the app" is working; its button is disabled. */
    val removing: StateFlow<Boolean> = _removing.asStateFlow()

    private val _removeAsk = MutableStateFlow<RemoveMissingAsk?>(null)

    /** The confirmation before "Remove them from the app", with its numbers; null while it isn't open. */
    val removeAsk: StateFlow<RemoveMissingAsk?> = _removeAsk.asStateFlow()

    /** "Remove them from the app" was tapped: counts the held recipes and their planned meals now, and asks. */
    fun askRemoveMissing() {
        viewModelScope.launch {
            try {
                val held = folder.heldSummary()
                if (held.ids.isNotEmpty()) _removeAsk.value = RemoveMissingAsk(held.ids, held.plannedMeals)
            } catch (e: IOException) {
                _message.value = "Couldn't count them: ${e.message ?: "the app's data can't be read"}."
            }
        }
    }

    /** "Keep them": closes the confirmation; nothing is removed. */
    fun keepMissing() {
        _removeAsk.value = null
    }

    /**
     * "Remove" in the confirmation: removes the recipes it counted whose files are still gone
     * (RecipeFolderStatus.confirmMassRemoval); one that went missing after it opened is not among them. With no
     * confirmation open, or while the first tap is still working, it does nothing.
     */
    fun removeMissing() {
        val ask = _removeAsk.getAndUpdate { null } ?: return
        if (!_removing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                folder.confirmMassRemoval(ask.ids)
            } catch (e: IOException) {
                _message.value = "Couldn't remove them: ${e.message ?: "the recipe folder can't be read"}."
            } finally {
                _removing.value = false
            }
        }
    }

    private val _usingNewFolder = MutableStateFlow(false)

    /** True while "Use the new folder" is working; its button is disabled (P7-R10c). */
    val usingNewFolder: StateFlow<Boolean> = _usingNewFolder.asStateFlow()

    /**
     * "Use the new folder": accepts the current recipe folder as the library and removes nothing; recipes missing from
     * it wait for "Remove them from the app" (P7-R10e). A second tap meanwhile does nothing.
     */
    fun useNewFolder() {
        if (!_usingNewFolder.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                folder.useNewFolder()
            } catch (e: IOException) {
                _message.value = "Couldn't use the new folder: ${e.message ?: "the recipe folder can't be read"}."
            } finally {
                _usingNewFolder.value = false
            }
        }
    }

    /** "Point back": says how to give the app its old recipe folder again; changes nothing. */
    fun pointBack(problem: RecipeFileProblem) {
        problem.movedFrom?.let { _message.value = pointBackInstructions(it) }
    }

    // Marks [fileName] busy; false when it already was. Atomic, so two quick taps can't both get through.
    private fun claim(fileName: String): Boolean {
        while (true) {
            val current = _busy.value
            if (fileName in current) return false
            if (_busy.compareAndSet(current, current + fileName)) return true
        }
    }

    fun messageShown() {
        _message.value = null
    }
}
