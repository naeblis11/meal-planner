package com.naeblis11.mealplanner.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.RecipeSummary
import com.naeblis11.mealplanner.domain.ServingsInput
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Choose the recipe (and optionally the servings) for one slot: the Pi's /calendar/assign. */
class AssignMealViewModel(
    val date: LocalDate,
    val slot: String,
    private val plans: MealPlanRepository,
    private val recipes: RecipeRepository,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _servings = MutableStateFlow("")
    val servings: StateFlow<String> = _servings.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _assigned = MutableStateFlow(false)

    /** True once the meal is saved; the destination leaves on it (state, so a rotation mid-save still leaves). */
    val assigned: StateFlow<Boolean> = _assigned.asStateFlow()
    private val busy = AtomicBoolean(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<RecipeSummary>?> = _query
        .flatMapLatest { recipes.summaries(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Changing a planned meal starts from the servings it was planned at.
        viewModelScope.launch {
            try {
                plans.assignment(date, slot)?.servings?.let { planned -> _servings.compareAndSet("", planned) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An empty field is a fine start.
            }
        }
    }

    fun setQuery(text: String) {
        _query.value = text
    }

    fun setServings(text: String) {
        _servings.value = text
        _error.value = null
    }

    fun assign(recipeId: Long) {
        val servings = when (val input = ServingsInput.read(_servings.value)) {
            ServingsInput.Result.Blank -> null
            is ServingsInput.Result.Valid -> input.servings
            ServingsInput.Result.Invalid -> {
                _error.value = ServingsInput.HINT
                return
            }
        }
        // One save at a time, and none once a meal is saved: a second tap on another recipe
        // in the frames before the screen leaves must not overwrite the meal just saved.
        if (_assigned.value || !busy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                plans.assign(date, slot, recipeId, servings)
                _assigned.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                _error.value = e.message ?: "The meal could not be saved."
            } catch (e: Exception) {
                _error.value = "The meal could not be saved."
            } finally {
                busy.set(false)
            }
        }
    }
}
