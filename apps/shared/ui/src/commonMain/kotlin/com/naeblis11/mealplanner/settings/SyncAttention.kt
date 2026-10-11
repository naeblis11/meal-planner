package com.naeblis11.mealplanner.settings

import com.naeblis11.mealplanner.sync.HeldRemoval
import kotlinx.coroutines.flow.StateFlow

/**
 * Recipes a sync from another device would remove, held for the user (LAN sync, stage 2b): the master PC's SyncHub
 * holds a mass removal as RecipeFolder's R2 does. Needs attention lists them; null on Android. [plannedMeals], [confirm]
 * and [keep] do their work on an IO thread themselves (database reads, the hub's lock, the Recycle Bin), so the UI may
 * call them from its own scope. Each takes the uuids the user was shown: a removal held after that (another exchange
 * while the question was open) stays held, never removed or kept unseen.
 */
interface SyncAttention {
    /** The held recipes; empty when nothing is held. */
    val held: StateFlow<List<HeldRemoval>>

    /** How many planned meals use the held recipes [uuids] (they go with them). */
    suspend fun plannedMeals(uuids: Set<String>): Int

    /** Removes the held recipes [uuids] (their files go to the Recycle Bin), with their planned meals. */
    suspend fun confirm(uuids: Set<String>)

    /** Keeps the held recipes [uuids]: they are sent back to the device that removed them. */
    suspend fun keep(uuids: Set<String>)
}
