package com.naeblis11.mealplanner.data

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Runs [block] as one write transaction. Room's multiplatform replacement for room-ktx's
 * `withTransaction`: DAO calls inside the block join the transaction, and a throw rolls it back.
 */
suspend fun <R> RoomDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { connection -> connection.immediateTransaction { block() } }
