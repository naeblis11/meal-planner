package com.naeblis11.mealplanner.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The production driver path (AndroidSQLiteDriver), not the framework-helper path the other data tests use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriverTransactionTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(ApplicationProvider.getApplicationContext<Context>())
            .setDriver(AndroidSQLiteDriver())
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun aThrowInsideATransactionRollsItsWritesBack() = runBlocking {
        try {
            db.inTransaction {
                db.pantryDao().insertOrIgnore(PantryItemEntity(name = "Rice"))
                throw IllegalStateException("abort")
            }
            fail("the throw should have propagated")
        } catch (expected: IllegalStateException) {
            assertEquals("abort", expected.message)
        }
        assertEquals(emptyList<PantryItemEntity>(), db.pantryDao().observeAll().first())
    }

    @Test
    fun aFlowReEmitsAfterACommittedTransaction() = runBlocking {
        val first = CompletableDeferred<List<PantryItemEntity>>()
        val collector = async {
            db.pantryDao().observeAll()
                .onEach { if (!first.isCompleted) first.complete(it) }
                .first { rows -> rows.any { it.name == "Rice" } }
        }
        // Write only once the collector has its first (empty) emission, so Rice can only arrive by invalidation.
        assertEquals(emptyList<PantryItemEntity>(), withTimeout(5_000) { first.await() })
        db.inTransaction { db.pantryDao().insertOrIgnore(PantryItemEntity(name = "Rice")) }

        assertEquals(listOf("Rice"), withTimeout(5_000) { collector.await() }.map { it.name })
    }
}
