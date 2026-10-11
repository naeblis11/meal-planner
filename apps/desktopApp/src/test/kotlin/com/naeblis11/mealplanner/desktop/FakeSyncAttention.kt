package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.settings.SyncAttention
import com.naeblis11.mealplanner.sync.HeldRemoval
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Held sync removals without a hub: each name is held as "id-<name>". Remove and Keep take off exactly the uuids they
 * are given, and are counted with what they were given. While [gate] is set, both wait for it.
 */
class FakeSyncAttention(names: List<String>, private val meals: Int = 0) : SyncAttention {
    override val held = MutableStateFlow(names.map { HeldRemoval(idOf(it), it) })
    val confirms = AtomicInteger()
    val keeps = AtomicInteger()
    val confirmed = CopyOnWriteArrayList<Set<String>>()
    val kept = CopyOnWriteArrayList<Set<String>>()
    val mealsAsked = CopyOnWriteArrayList<Set<String>>()

    @Volatile
    var failMeals: Exception? = null

    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun plannedMeals(uuids: Set<String>): Int {
        mealsAsked += uuids
        failMeals?.let { throw it }
        return meals
    }

    override suspend fun confirm(uuids: Set<String>) {
        gate?.await()
        confirms.incrementAndGet()
        confirmed += uuids
        held.value = held.value.filter { it.uuid !in uuids }
    }

    override suspend fun keep(uuids: Set<String>) {
        gate?.await()
        keeps.incrementAndGet()
        kept += uuids
        held.value = held.value.filter { it.uuid !in uuids }
    }

    companion object {
        fun idOf(name: String) = "id-$name"
    }
}
