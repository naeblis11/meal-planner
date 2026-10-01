package com.naeblis11.mealplanner.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.data.AddStatus
import com.naeblis11.mealplanner.data.PantryAdd
import com.naeblis11.mealplanner.data.PantryItemEntity
import com.naeblis11.mealplanner.data.PantryRepository
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.domain.AisleGroup
import com.naeblis11.mealplanner.domain.AisleGroups
import com.naeblis11.mealplanner.domain.PantryDates
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** On hand, grouped by aisle like the Pi's pantry page; what has run out, A-Z. */
data class PantryState(val onHand: List<AisleGroup<PantryItemEntity>>, val removed: List<PantryItemEntity>)

class PantryViewModel(
    private val pantry: PantryRepository,
    private val shopping: ShoppingRepository,
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val state: StateFlow<PantryState?> = pantry.observe()
        .map { items ->
            PantryState(
                onHand = AisleGroups.group(items.filter { it.active }, { it.aisle }, { it.name }),
                removed = items.filter { !it.active }.sortedBy { it.name.lowercase(Locale.ROOT) },
            )
        }
        .retryWhen { _, attempt ->
            _error.value = FAILED
            delay(1_000L * (attempt + 1).coerceAtMost(5L))
            true
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(name: String, aisle: String) {
        if (name.isBlank()) {
            _error.value = "Please enter an ingredient name."
            return
        }
        act {
            _message.value = when (val result = pantry.add(name, aisle)) {
                is PantryAdd.Added -> "Added '${result.name}' to your pantry."
                is PantryAdd.AlreadyThere -> "'${result.name}' is already in your pantry."
            }
        }
    }

    fun setOnHand(item: PantryItemEntity, onHand: Boolean) = act {
        pantry.setActive(item.id, onHand)
        _message.value = if (onHand) "Added '${item.name}' back to your pantry." else "Removed '${item.name}' from your pantry."
    }

    fun toggleExactMatch(item: PantryItemEntity) = act { pantry.toggleExactMatch(item.id) }

    /** The Edit dialog's Save: the date is checked first, so a bad one changes nothing at all. */
    fun save(item: PantryItemEntity, aisle: String, addedOn: String) {
        val date = try {
            PantryDates.parse(addedOn)
        } catch (e: IllegalArgumentException) {
            _error.value = PantryDates.HINT
            return
        }
        act { pantry.updateDetails(item.id, aisle, date) }
    }

    fun delete(item: PantryItemEntity) = act {
        pantry.delete(item.id)
        _message.value = "Deleted '${item.name}' from your pantry."
    }

    /** A staple that has run out is one tap from the list (the Pi's cart button). */
    fun addToShoppingList(item: PantryItemEntity) = act {
        val result = shopping.addItem(item.name, aisle = item.aisle)
        _message.value = if (result.status == AddStatus.DUPLICATE) {
            "'${item.name}' is already on your shopping list."
        } else {
            "Added '${item.name}' to your shopping list."
        }
    }

    /** The screen showed [shown]; a newer message that arrived meanwhile is kept for its turn. */
    fun messageShown(shown: String) {
        _message.compareAndSet(shown, null)
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = FAILED
            }
        }
    }

    private companion object {
        const val FAILED = "The pantry could not be updated."
    }
}
