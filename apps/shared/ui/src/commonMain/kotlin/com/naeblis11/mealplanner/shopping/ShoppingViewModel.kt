package com.naeblis11.mealplanner.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.data.AddStatus
import com.naeblis11.mealplanner.data.ShoppingItemEntity
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.domain.AisleGroup
import com.naeblis11.mealplanner.domain.AisleGroups
import com.naeblis11.mealplanner.domain.Amounts
import com.naeblis11.mealplanner.domain.Week
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** "Need to buy" grouped by aisle, and "Already in My Kitchen" (the pantry covers them), as on the Pi. */
data class ShoppingState(
    val needToBuy: List<AisleGroup<ShoppingItemEntity>>,
    val alreadyHave: List<ShoppingItemEntity>,
) {
    val isEmpty: Boolean get() = needToBuy.isEmpty() && alreadyHave.isEmpty()
}

/** The shopping list: the Pi's /shopping-list. Built for one thumb in a store on a slow phone. */
class ShoppingViewModel(
    private val shopping: ShoppingRepository,
    // The database's rows; a seam so a test can make them lag behind a write.
    private val items: Flow<List<ShoppingItemEntity>> = shopping.observe(),
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** True while any week is being added (here or from the Calendar); the button is off meanwhile. */
    val adding: StateFlow<Boolean> = shopping.adding

    // written: the database has the tap; the row keeps showing it until a row from the database agrees.
    private data class PendingCheck(val checked: Boolean, val tap: Long, val written: Boolean = false)

    // Taps the database hasn't confirmed yet: the row shows them at once.
    private val pending = MutableStateFlow<Map<Long, PendingCheck>>(emptyMap())
    private var taps = 0L

    // Check writes go one at a time in tap order (Mutex is fair), so the last tap is what sticks.
    private val checkLock = Mutex()

    val state: StateFlow<ShoppingState?> = combine(items, pending) { items, overrides ->
        // Decided on the very rows being rendered (items is subscribed once): a written tap stops
        // applying only when this emission agrees with it, so stale rows can't flash the old state.
        val agreed = overrides.filter { (id, o) -> o.written && items.firstOrNull { it.id == id }.let { it == null || it.checked == o.checked } }
        if (agreed.isNotEmpty()) pending.update { cur -> cur.filter { (id, o) -> agreed[id]?.tap != o.tap } }
        val shown = items.map { item -> overrides[item.id]?.takeIf { it.tap != agreed[item.id]?.tap }?.let { item.copy(checked = it.checked) } ?: item }
        ShoppingState(
            needToBuy = AisleGroups.group(shown.filter { !it.inPantry }, { it.aisle }, { it.name }),
            alreadyHave = shown.filter { it.inPantry },
        )
    }
        .retryWhen { _, attempt ->
            _error.value = ShoppingMessages.UPDATE_FAILED
            delay(1_000L * (attempt + 1).coerceAtMost(5L))
            true
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Ticks or unticks a row: shown at once, written to the database straight away. The row
     * keeps showing the tap until the database's own rows agree (see [state]; Room re-emits a
     * moment after the write, and stale rows must not flash the old state). A failed write drops
     * it, so the row falls back to what the database holds.
     */
    fun setChecked(item: ShoppingItemEntity, checked: Boolean) {
        val tap = ++taps
        pending.update { it + (item.id to PendingCheck(checked, tap)) }
        viewModelScope.launch {
            try {
                checkLock.withLock { shopping.setChecked(item.id, checked) }
                // Only this tap's override is marked; a later tap on the same row has its own.
                pending.update { cur -> cur[item.id]?.takeIf { it.tap == tap }?.let { cur + (item.id to it.copy(written = true)) } ?: cur }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A tick is deep in the list: say so without scrolling the shopper away.
                _message.value = TICK_FAILED
                pending.update { if (it[item.id]?.tap == tap) it - item.id else it }
            }
        }
    }

    /** Adds this week's (Monday to Sunday around today) meals. Additive, so a tap while one runs (here or from the Calendar) is ignored. */
    fun addThisWeek() {
        if (!shopping.tryStartAdd()) return
        viewModelScope.launch {
            try {
                _message.value = ShoppingMessages.addedWeek(shopping.addWeek(Week.start(today())))
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = ShoppingMessages.UPDATE_FAILED
            }
        }.invokeOnCompletion { shopping.finishAdd() } // Also when cancelled before it started.
    }

    /** The Pi's /shopping-list/add: an amount is optional, but one that can't be read is refused. */
    fun addItem(name: String, amount: String, unit: String, aisle: String) {
        val cleanName = name.trim()
        val problem = nameProblem(cleanName) ?: amountProblem(amount)
        if (problem != null) {
            _error.value = problem
            return
        }
        val cleanAmount = amount.trim().ifEmpty { null }
        // A unit means nothing without an amount (the Pi's route drops it too).
        val cleanUnit = if (cleanAmount == null) null else unit.trim().ifEmpty { null }
        act {
            val result = shopping.addItem(cleanName, cleanAmount, cleanUnit, aisle)
            _message.value = when (result.status) {
                AddStatus.ADDED -> "Added '$cleanName' to your shopping list."
                AddStatus.MERGED -> "Added more '$cleanName' to the one already on your list."
                AddStatus.DUPLICATE -> "'$cleanName' is already on your shopping list."
            }
        }
    }

    fun setAisle(item: ShoppingItemEntity, aisle: String) = act(asError = false) { shopping.setAisle(item.id, aisle) }

    fun remove(item: ShoppingItemEntity) = act(asError = false) {
        shopping.remove(item.id)
        _message.value = "Removed '${item.name}' from your shopping list."
    }

    fun clear() = act {
        shopping.clear()
        _message.value = "Cleared your shopping list."
    }

    /** The screen showed [shown]; a newer message that arrived meanwhile is kept for its turn. */
    fun messageShown(shown: String) {
        _message.compareAndSet(shown, null)
    }

    // asError: the banner at the top of the list (and a scroll to it) for actions made from the top; a snackbar for the rest.
    private fun act(asError: Boolean = true, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (asError) _error.value = ShoppingMessages.UPDATE_FAILED else _message.value = ShoppingMessages.UPDATE_FAILED
            }
        }
    }

    private companion object {
        const val TICK_FAILED = "That item could not be updated."
    }
}

/** Why a hand-added item's name is refused, or null. Shared with the add dialog so it can stay open. */
fun nameProblem(name: String): String? = if (name.trim().isEmpty()) "Please enter an item name." else null

/** Why a hand-added item's amount is refused (blank is fine), or null. */
fun amountProblem(amount: String): String? {
    val clean = amount.trim()
    return if (clean.isNotEmpty() && Amounts.parseAmount(clean) == null) {
        "Couldn't read the amount '$clean' -- try a number like 2, 1/2 or 1.5."
    } else {
        null
    }
}
