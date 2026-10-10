package com.naeblis11.mealplanner.folder

import java.nio.file.ClosedWatchServiceException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Watches the recipes folder (not its subfolders) and calls [onChange] once per burst of changes.
 * An editor's save is often several events, so [onChange] waits until nothing has happened for
 * [debounceMillis]. Which file changed doesn't matter: a sync looks at every file and skips the
 * unchanged ones, the app's own writes included, by content hash.
 *
 * If the watched folder itself disappears, [onChange] runs one last time (so the library can
 * report the missing folder), the watcher releases its resources and [isWatching] turns false;
 * it does not poll for the folder's return. A change that arrives while [onChange] is running
 * leaves one follow-up round behind.
 */
class RecipeFolderWatcher(
    dir: Path,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val onChange: suspend () -> Unit,
) : AutoCloseable {
    private val service: WatchService = dir.fileSystem.newWatchService()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)

    @Volatile
    private var job: Job? = null

    // Set (before the signal that follows it) once the operating system says the folder is gone.
    @Volatile
    private var folderGone = false

    /** True from [start] until the watcher is closed or its folder has vanished. */
    val isWatching: Boolean get() = job?.isActive == true

    init {
        // Registered at once, so a change made between construction and start() is still seen.
        try {
            dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
        } catch (e: Throwable) {
            // A missing or unreadable folder must not leak the service's native handle and thread.
            service.close()
            throw e
        }
    }

    /**
     * Starts watching; [onChange] runs on [scope]. A failing [onChange] is reported and watching
     * goes on. A watcher starts once: a second call throws [IllegalStateException].
     */
    fun start(scope: CoroutineScope): RecipeFolderWatcher {
        check(started.compareAndSet(false, true)) { "RecipeFolderWatcher is already started" }
        Thread({ pump() }, "recipe-folder-watcher").apply { isDaemon = true }.start()
        val running = scope.launch {
            while (true) {
                if (signals.receiveCatching().isClosed) return@launch
                // The quiet spell: every further event starts it again.
                while (true) {
                    val next = withTimeoutOrNull(debounceMillis) { signals.receiveCatching() } ?: break
                    if (next.isClosed) return@launch
                }
                // Read before syncing: a folder that vanishes during the sync leaves its own signal
                // behind, which runs the final round.
                val lastRound = folderGone
                try {
                    // Closing the watcher stops the waiting but never abandons a sync half way.
                    withContext(NonCancellable) { onChange() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("Meal Planner: recipe folder sync failed: $e")
                }
                if (lastRound) {
                    release()
                    return@launch
                }
            }
        }
        // However the job ends (close(), the folder vanishing, or its scope cancelled without close()),
        // the pump thread and the operating system's watch handle are released. release() is idempotent.
        running.invokeOnCompletion { release() }
        job = running
        return this
    }

    // Blocks on the operating system's change notifications; ends when the service is closed or
    // the folder is gone (its key can no longer be reset). Never spins: take() blocks.
    private fun pump() {
        try {
            while (true) {
                val key = service.take()
                // OVERFLOW and every event kind are deliberately collapsed into one full resync.
                key.pollEvents()
                if (!key.reset()) {
                    folderGone = true
                    signals.trySend(Unit)
                    break
                }
                signals.trySend(Unit)
            }
        } catch (e: ClosedWatchServiceException) {
            // close() was called.
        } catch (e: InterruptedException) {
            // The JVM is shutting down.
        }
    }

    // Safe to call more than once, from any thread: both closes do nothing the second time.
    private fun release() {
        service.close()
        signals.close()
    }

    override fun close() {
        release()
        job?.cancel()
    }

    /**
     * [close], then waits for the watcher to finish. A sync that was already running is allowed to
     * complete first, so the caller can safely close what [onChange] uses (the database) afterwards.
     */
    suspend fun closeAndJoin() {
        close()
        job?.join()
    }

    companion object {
        const val DEBOUNCE_MILLIS = 500L
    }
}
