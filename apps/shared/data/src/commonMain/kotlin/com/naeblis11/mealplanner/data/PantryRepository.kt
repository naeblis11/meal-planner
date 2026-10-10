package com.naeblis11.mealplanner.data

import com.naeblis11.mealplanner.domain.PantryDates
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What adding to the pantry did; [name] is as stored (the existing spelling for a duplicate). */
sealed interface PantryAdd {
    val name: String

    data class Added(override val name: String) : PantryAdd

    data class AlreadyThere(override val name: String) : PantryAdd
}

/** What the recipe page's one-tap add did (P5-R7). */
enum class Stocked { ADDED, PUT_BACK, ALREADY_THERE }

/** [name] is as stored: the existing spelling for an item already there. */
data class PantryStock(val status: Stocked, val name: String)

/**
 * The pantry: pantry.py. [add] reads after its insert, so every write takes the write
 * lock (not re-entrant: locked paths call only *Locked helpers).
 */
class PantryRepository(
    private val db: AppDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val dao = db.pantryDao()
    private val writeLock = Mutex()

    /** Every item, by name ignoring case. */
    fun observe(): Flow<List<PantryItemEntity>> = dao.observeAll()

    /** Stamped today unless [addedOn] says otherwise; a name already there (in any case) changes nothing. */
    suspend fun add(name: String, aisle: String? = null, addedOn: String? = null): PantryAdd {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Pantry item name cannot be empty" }
        val date = PantryDates.parse(addedOn) ?: today().toString()
        return writeLock.withLock {
            withContext(dispatcher) {
                db.inTransaction {
                    val id = dao.insertOrIgnore(PantryItemEntity(name = clean, aisle = aisle?.trim()?.ifEmpty { null }, addedOn = date))
                    if (id != -1L) PantryAdd.Added(clean) else PantryAdd.AlreadyThere(dao.byName(clean)?.name ?: clean)
                }
            }
        }
    }

    /**
     * The recipe page's one-tap add (P5-R7): like [add], stamped today, except that an item there but marked out is put
     * back (and restamped) rather than left out, so the ingredient is then covered.
     */
    suspend fun stock(name: String): PantryStock {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Pantry item name cannot be empty" }
        val date = today().toString()
        return writeLock.withLock {
            withContext(dispatcher) {
                db.inTransaction {
                    val id = dao.insertOrIgnore(PantryItemEntity(name = clean, addedOn = date))
                    val existing = if (id != -1L) null else dao.byName(clean)
                    when {
                        existing == null -> PantryStock(Stocked.ADDED, clean)
                        existing.active -> PantryStock(Stocked.ALREADY_THERE, existing.name)
                        else -> {
                            dao.putBack(existing.id, date)
                            PantryStock(Stocked.PUT_BACK, existing.name)
                        }
                    }
                }
            }
        }
    }

    /** On hand or not. Putting an item back restamps its date: it is going into the pantry afresh. */
    suspend fun setActive(id: Long, active: Boolean): Unit = writeLock.withLock {
        withContext(dispatcher) { if (active) dao.putBack(id, today().toString()) else dao.markOut(id) }
    }

    /** Blank clears the date; anything but YYYY-MM-DD throws IllegalArgumentException and writes nothing. */
    suspend fun setAddedOn(id: Long, addedOn: String?) {
        val date = PantryDates.parse(addedOn)
        writeLock.withLock { withContext(dispatcher) { dao.setAddedOn(id, date) } }
    }

    /** The Edit dialog's Save: aisle and date change together or not at all (a bad date writes nothing). */
    suspend fun updateDetails(id: Long, aisle: String?, addedOn: String?) {
        val date = PantryDates.parse(addedOn)
        writeLock.withLock {
            withContext(dispatcher) {
                db.inTransaction {
                    dao.setAisle(id, aisle?.trim()?.ifEmpty { null })
                    dao.setAddedOn(id, date)
                }
            }
        }
    }

    suspend fun toggleExactMatch(id: Long): Unit = writeLock.withLock { withContext(dispatcher) { dao.toggleExactMatch(id) } }

    suspend fun setAisle(id: Long, aisle: String?): Unit =
        writeLock.withLock { withContext(dispatcher) { dao.setAisle(id, aisle?.trim()?.ifEmpty { null }) } }

    suspend fun delete(id: Long): Unit = writeLock.withLock { withContext(dispatcher) { dao.delete(id) } }

    /** Returns once no write that had already started is running: the desktop waits on it before closing the database. */
    suspend fun awaitWrites() {
        writeLock.withLock {}
    }
}
