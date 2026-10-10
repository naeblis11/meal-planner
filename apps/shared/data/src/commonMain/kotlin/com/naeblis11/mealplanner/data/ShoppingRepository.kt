package com.naeblis11.mealplanner.data

import com.naeblis11.mealplanner.domain.GroceryCategories
import com.naeblis11.mealplanner.domain.JsonTree
import com.naeblis11.mealplanner.domain.Line
import com.naeblis11.mealplanner.domain.PantryRule
import com.naeblis11.mealplanner.domain.Py
import com.naeblis11.mealplanner.domain.ShoppingMerge
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What happened to an item added by hand: shopping_list.add_item's status. */
enum class AddStatus { ADDED, MERGED, DUPLICATE }

/** [id] is the row added, merged into, or (for a duplicate) the one already there. */
data class AddItemResult(val status: AddStatus, val id: Long)

/**
 * The shopping list: shopping_list.py. Additive: adding a week only ever updates or
 * adds rows; only [remove] and [clear] take anything off. Read-modify-write runs in one
 * transaction under the write lock (not re-entrant: locked paths call only *Locked
 * helpers). The single-statement writes ([setChecked], [remove], [clear]) take the lock
 * too: it is fair (FIFO), so calls made in tap order are applied in tap order, where
 * separate coroutines on IO threads would reach SQLite in any order.
 */
class ShoppingRepository(
    private val db: AppDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = db.shoppingDao()
    private val writeLock = Mutex()
    private val _adding = MutableStateFlow(false)

    /**
     * True while an "Add this week" runs, from any screen: the Calendar's and the Shopping
     * list's buttons are both off meanwhile, since the list is additive and a second add
     * would double every amount. Claimed with [tryStartAdd], released with [finishAdd].
     */
    val adding: StateFlow<Boolean> = _adding.asStateFlow()

    /** Claims the one add-a-week slot; false (and the tap is ignored, not queued) if an add is running. */
    fun tryStartAdd(): Boolean = _adding.compareAndSet(false, true)

    /** Releases the slot [tryStartAdd] claimed: after success, failure or cancellation. */
    fun finishAdd() {
        _adding.value = false
    }

    /** Not-in-pantry first, then by name. */
    fun observe(): Flow<List<ShoppingItemEntity>> = dao.observeAll()

    /** "Add this week" for the week starting [weekStart]; returns how many planned meals it read. */
    suspend fun addWeek(weekStart: LocalDate): Int = writeLock.withLock {
        withContext(dispatcher) { db.inTransaction { addWeekLocked(weekStart) } }
    }

    private suspend fun addWeekLocked(weekStart: LocalDate): Int {
        val planned = dao.plannedForShopping(weekStart.toString(), weekStart.plusDays(6).toString())
        val meals = planned.map { meal ->
            ShoppingMerge.PlannedMeal(
                servings = meal.servings,
                yieldAmount = firstYieldAmount(meal.yieldsJson),
                ingredients = dao.ingredientLines(meal.recipeId).map { Line(it.name, it.amount, it.unit) },
            )
        }
        val existing = dao.allInIdOrder().map { it.toListRow() }
        // Only what is on hand: a crossed-out pantry item has run out, so it needs buying.
        val pantry = dao.onHandPantry().map { PantryRule(it.name, it.exactMatch) }
        val aisles = dao.knownAisles().associate { it.name to it.aisle }

        val before = existing.associateBy { it.id }
        for (row in ShoppingMerge.addWeek(existing, meals, pantry, aisles)) {
            val id = row.id
            if (id == null) {
                dao.insert(row.toEntity(0))
            } else if (row != before[id]) {
                dao.update(row.toEntity(id))
            }
        }
        return planned.size
    }

    /** Puts one named item on the list by hand (shopping_list.add_item). The name must not be blank. */
    suspend fun addItem(name: String, amount: String? = null, unit: String? = null, aisle: String? = null): AddItemResult =
        writeLock.withLock { withContext(dispatcher) { db.inTransaction { addItemLocked(name, amount, unit, aisle) } } }

    private suspend fun addItemLocked(name: String, amount: String?, unit: String?, aisle: String?): AddItemResult {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Shopping list item name cannot be empty" }
        val cleanAmount = amount?.trim()?.ifEmpty { null }
        val cleanUnit = unit?.trim()?.ifEmpty { null }

        // Same-name rows oldest first, so which one an amount merges into is deterministic.
        val existing = dao.byName(cleanName)
        if (existing.isNotEmpty() && cleanAmount == null) return AddItemResult(AddStatus.DUPLICATE, existing[0].id)
        for (row in existing) {
            val merged = ShoppingMerge.mergeAmounts(row.toListRow(), cleanAmount, cleanUnit) ?: continue
            // What needs buying changed, so the row is no longer "bought".
            dao.update(row.copy(amount = merged.first, unit = merged.second, checked = false))
            return AddItemResult(AddStatus.MERGED, row.id)
        }
        val chosenAisle = aisle?.trim()?.ifEmpty { null }
            ?: dao.rememberedAisle(cleanName)
            ?: GroceryCategories.categorize(cleanName)
        val id = dao.insert(ShoppingItemEntity(name = cleanName, amount = cleanAmount, unit = cleanUnit, aisle = chosenAisle))
        return AddItemResult(AddStatus.ADDED, id)
    }

    /** Sets (not toggles) the check, so a double tap or a stale screen can't flip it back. */
    suspend fun setChecked(id: Long, checked: Boolean): Unit =
        writeLock.withLock { withContext(dispatcher) { dao.setChecked(id, checked) } }

    /** Sets an item's aisle and remembers it for that ingredient; a blank aisle forgets it. */
    suspend fun setAisle(id: Long, aisle: String?): Unit = writeLock.withLock {
        withContext(dispatcher) { db.inTransaction { setAisleLocked(id, aisle) } }
    }

    private suspend fun setAisleLocked(id: Long, aisle: String?) {
        val row = dao.item(id) ?: return
        val clean = aisle?.trim()?.ifEmpty { null }
        dao.setAisle(id, clean)
        if (clean != null) dao.rememberAisle(IngredientAisleEntity(name = row.name, aisle = clean)) else dao.forgetAisle(row.name)
    }

    /** Not a permanent exclusion: adding the week again brings it back if it is still an ingredient. */
    suspend fun remove(id: Long): Unit = writeLock.withLock { withContext(dispatcher) { dao.delete(id) } }

    /** The only way the whole list empties: adding a week never does. */
    suspend fun clear(): Unit = writeLock.withLock { withContext(dispatcher) { dao.clear() } }

    /** Returns once no write that had already started is running: the desktop waits on it before closing the database. */
    suspend fun awaitWrites() {
        writeLock.withLock {}
    }

    private fun ShoppingItemEntity.toListRow() = ShoppingMerge.ListRow(id, name, amount, unit, aisle, inPantry, checked)

    private fun ShoppingMerge.ListRow.toEntity(id: Long) = ShoppingItemEntity(id, name, amount, unit, aisle, inPantry, checked)

    // The recipe's first yield amount as Python's str() writes it; unreadable yields mean
    // "no yield" (ratio 1), as shopping_list._planned_ratio treats them.
    private fun firstYieldAmount(yieldsJson: String?): String? = try {
        ((JsonTree.decode(yieldsJson) as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("amount")?.let { Py.str(it) }
    } catch (e: IllegalArgumentException) {
        null
    }
}
