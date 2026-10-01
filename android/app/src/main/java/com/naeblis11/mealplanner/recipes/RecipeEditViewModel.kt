package com.naeblis11.mealplanner.recipes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.RecipeFormatException
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.SubmittedRow
import com.naeblis11.mealplanner.domain.YamlMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditState(
    val form: RecipeForm? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val isNew: Boolean = false,
    /** The form differs from what is stored: leaving asks first. */
    val dirty: Boolean = false,
)

/**
 * Edit Recipe (an id) or New Recipe (null). Nothing is written until Save succeeds.
 * Unsaved typing is kept in [savedState], so a rotation or process death restores it.
 */
class RecipeEditViewModel(
    private val recipeId: Long?,
    private val repository: RecipeRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _state = MutableStateFlow(EditState(isNew = recipeId == null))
    val state: StateFlow<EditState> = _state.asStateFlow()
    private val _savedId = MutableStateFlow<Long?>(null)

    /**
     * The saved recipe's id once Save succeeds. The destination navigates on it
     * and calls [savedHandled]; held here, not as a callback, so it survives the
     * screen being recreated (a rotation) while the save runs.
     */
    val savedId: StateFlow<Long?> = _savedId.asStateFlow()
    private var doc: YamlMap? = null
    private var nextKey = savedState.get<Int>(NEXT_KEY) ?: 0
    private var loadedForm: RecipeForm? = null

    init {
        viewModelScope.launch {
            try {
                val loaded = if (recipeId == null) RecipeEdits.newRecipeDoc() else repository.doc(recipeId)
                if (loaded == null) {
                    _state.update { it.copy(error = "This recipe no longer exists.") }
                    return@launch
                }
                val fresh = RecipeEdits.formFor(loaded)
                doc = loaded
                loadedForm = fresh
                // A draft from before a process death wins over what is stored; a draft that
                // can't be read (an older app version) is dropped rather than crashing the editor.
                val draft = savedState.get<String>(DRAFT_KEY)?.let { runCatching { RecipeFormCodec.decode(it) }.getOrNull() }
                _state.update { it.copy(form = draft ?: fresh, dirty = draft != null && draft != fresh) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "This recipe can't be edited: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun edit(change: (RecipeForm) -> RecipeForm) {
        _state.update { s ->
            val form = s.form?.let(change)
            s.copy(form = form, error = null, dirty = form != null && form != loadedForm)
        }
        val current = _state.value
        if (current.dirty && current.form != null) {
            savedState[DRAFT_KEY] = RecipeFormCodec.encode(current.form)
        } else {
            savedState.remove<String>(DRAFT_KEY)
        }
    }

    fun addIngredient() = edit { it.copy(rows = it.rows + EditorRowState(newKey(), SubmittedRow.INGREDIENT, "")) }

    fun addSection() = edit { it.copy(rows = it.rows + EditorRowState(newKey(), SubmittedRow.SECTION, "")) }

    fun updateRow(key: String, change: (EditorRowState) -> EditorRowState) =
        edit { f -> f.copy(rows = f.rows.map { if (it.key == key) change(it) else it }) }

    fun removeRow(key: String) = edit { f -> f.copy(rows = f.rows.filterNot { it.key == key }) }

    fun moveRow(key: String, by: Int) = edit { f -> f.copy(rows = moved(f.rows, f.rows.indexOfFirst { it.key == key }, by)) }

    fun addStep() = edit { it.copy(steps = it.steps + StepState(newKey(), "")) }

    fun updateStep(key: String, text: String) =
        edit { f -> f.copy(steps = f.steps.map { if (it.key == key) it.copy(text = text) else it }) }

    fun removeStep(key: String) = edit { f -> f.copy(steps = f.steps.filterNot { it.key == key }) }

    fun moveStep(key: String, by: Int) = edit { f -> f.copy(steps = moved(f.steps, f.steps.indexOfFirst { it.key == key }, by)) }

    fun save() {
        val original = doc ?: return
        // Claim the save atomically: a second tap before recomposition must not start another.
        var form: RecipeForm? = null
        while (true) {
            val current = _state.value
            if (current.saving) return
            form = current.form ?: return
            if (_state.compareAndSet(current, current.copy(saving = true, error = null))) break
        }
        val submitted: RecipeForm = form!!
        viewModelScope.launch {
            try {
                // apply() changes the doc it is given and can fail part-way, so each
                // attempt works on a copy and a retry still sees the original steps.
                @Suppress("UNCHECKED_CAST")
                val updated = RecipeEdits.apply(RecipeYaml.deepCopy(original) as YamlMap, submitted, repository.lowerCaseNamesExcept(recipeId))
                val id = repository.save(updated)
                // What is stored now is what was submitted: later edits are measured against it.
                loadedForm = submitted
                savedState.remove<String>(DRAFT_KEY)
                _state.update { it.copy(saving = false, dirty = false) }
                _savedId.value = id
            } catch (e: RecipeEditException) {
                _state.update { it.copy(saving = false, error = e.message) }
            } catch (e: RecipeFormatException) {
                _state.update { it.copy(saving = false, error = "This recipe can't be saved: ${e.message}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = "The recipe could not be saved: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** The destination has navigated away for [savedId]. */
    fun savedHandled() {
        _savedId.value = null
    }

    // Keys that aren't e<n> are new rows to the editor rules. The counter is saved too, so
    // a row added after a restore never reuses a restored row's key.
    private fun newKey(): String {
        val key = "n${nextKey++}"
        savedState[NEXT_KEY] = nextKey
        return key
    }

    private fun <T> moved(items: List<T>, index: Int, by: Int): List<T> {
        val target = index + by
        if (index < 0 || target !in items.indices) return items
        return items.toMutableList().apply { add(target, removeAt(index)) }
    }

    companion object {
        const val DRAFT_KEY = "draft"
        const val NEXT_KEY = "next_key"
    }
}
