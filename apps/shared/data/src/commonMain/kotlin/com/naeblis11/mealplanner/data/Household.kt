package com.naeblis11.mealplanner.data

import java.security.SecureRandom

/**
 * This household's id (P5-R2): 128 random bits as 32 lower-case hex digits, made once and kept in app_meta under
 * [KEY]. Google event ids are derived from it, so it belongs to the library, not to the PC: phase 2's sync carries it
 * to the phones, and two devices sending the same week then name the same events. [random] gives the 16 bytes.
 *
 * Plan 6 (P6-R4) also keeps when the household was created, in epoch milliseconds under [CREATED_KEY]: written with the
 * id, or, for an id made before plan 6, on the first [created]. Once written it never changes. Two PCs on one network
 * compare it to agree which one keeps Google Calendar and Alexa. The time written is [createdFallback]'s when it gives
 * one no later than [clock] (the desktop passes its database file's creation time, the best estimate of when this PC
 * started), else [clock]'s. [clock] and [createdFallback] come before [random], so `Household(db) { bytes }` still sets
 * [random].
 */
class Household(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val createdFallback: () -> Long? = { null },
    private val random: () -> ByteArray = { ByteArray(16).also(SecureRandom()::nextBytes) },
) {
    /** The id, made and kept on the first call with its creation time; one transaction, so two first calls at once agree. */
    suspend fun id(): String =
        AppMetaIds.keep(db, KEY, "A household id", random) { meta ->
            if (meta.get(CREATED_KEY)?.toLongOrNull() == null) meta.put(AppMetaEntity(CREATED_KEY, estimate().toString()))
        }

    /**
     * When this household was created, in epoch milliseconds (P6-R4); makes the id first if there is none. A stored
     * value that isn't a number (only a hand-edited database has one) is replaced like a missing one.
     */
    suspend fun created(): Long {
        id()
        val meta = db.appMetaDao()
        meta.get(CREATED_KEY)?.toLongOrNull()?.let { return it }
        return db.inTransaction {
            meta.get(CREATED_KEY)?.toLongOrNull() ?: estimate().also { meta.put(AppMetaEntity(CREATED_KEY, it.toString())) }
        }
    }

    // The fallback when it is a time no later than now (a clock set back can't date a household in the future), else now.
    private fun estimate(): Long {
        val now = clock()
        return createdFallback()?.takeIf { it in 1..now } ?: now
    }

    companion object {
        const val KEY = "household_id"
        const val CREATED_KEY = "household_created"
    }
}
