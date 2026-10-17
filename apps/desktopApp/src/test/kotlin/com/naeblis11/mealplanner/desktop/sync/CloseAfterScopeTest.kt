package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.openAt
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sync tests' teardown: a query still running in the scope ends before the database closes (closeAfterScope). */
class CloseAfterScopeTest {
    private val dir: File = Files.createTempDirectory("mp-close-after-scope").toFile()
    private val db = AppDatabase.openAt(File(dir, "pc.db"))

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun theDatabaseClosesOnlyOnceTheScopesQueryIsDone() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val inQuery = CountDownLatch(1)
        val release = CountDownLatch(1)
        val read = CompletableFuture<Result<String?>>()
        scope.launch {
            // Like Room's native step under a flow's collector: work the cancel can't stop, then the database.
            inQuery.countDown()
            release.await()
            read.complete(runCatching { withContext(NonCancellable) { db.appMetaDao().get("k") } })
        }
        assertTrue(inQuery.await(5, TimeUnit.SECONDS))
        val closed = CompletableFuture.runAsync { closeAfterScope(scope, db) }
        // A short negative wait: the scope is cancelled at once, but the database must stay open under the query.
        assertThrows(TimeoutException::class.java) { closed.get(400, TimeUnit.MILLISECONDS) }
        release.countDown()
        closed.get(5, TimeUnit.SECONDS)
        assertTrue(read.get(5, TimeUnit.SECONDS).isSuccess)
    }
}
