package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.app.PreferencesStore
import com.naeblis11.mealplanner.app.SettingsStore
import com.naeblis11.mealplanner.calendar.CalendarChoice
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.desktop.peers.PeerIdentity
import com.naeblis11.mealplanner.desktop.peers.PeerWatch
import com.naeblis11.mealplanner.desktop.server.AppServer
import com.naeblis11.mealplanner.folder.FolderFileStore
import com.naeblis11.mealplanner.folder.LIBRARY_BLOCKED_MESSAGE
import com.naeblis11.mealplanner.folder.RecipeFolder
import com.naeblis11.mealplanner.folder.RecipeFolderWatcher
import com.naeblis11.mealplanner.folder.isLibraryBlocked
import com.naeblis11.mealplanner.folder.libraryFolderFailedMessage
import com.naeblis11.mealplanner.folder.libraryNoticeFor
import com.naeblis11.mealplanner.folder.recycleBin
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The desktop's database file name: its own, so the Python server's mealplanner.db is never read. */
const val DESKTOP_DB = "mealplanner-app.db"

/**
 * The desktop app over one library ([libraryDir], DesktopPaths.libraryDir: the recipe files and their photos) and its
 * own data ([appDataDir], DesktopPaths.appDataDir: the database and the cache, P7-R10): the shared container with the
 * recipe folder as the source of truth, plus the background work that keeps the index current. The two default to one
 * folder, as the preview and the tests have them. [libraryAccess] makes the library's folders and saves its files; a library
 * Windows won't let the app write (Controlled folder access) is still read, and [libraryNotice] says so. [prefsNode]
 * keeps tests' and the preview's settings apart from the installed app's; [moveToTrash] is the
 * Recycle Bin (tests pass their own); [closeTimeoutMillis] bounds how long [close] waits;
 * [settingsFactory] gives the small settings (the prefs node by default; tests pass MapSettings);
 * [rearmPollMillis] is how often a recipe folder that isn't watched is looked for; [serverFactory] makes the built-in
 * server (plan 4) from this app, and null (most tests) means there is none; [peersFactory] likewise makes the look-out
 * for other Meal Planner PCs (plan 6).
 */
class DesktopApp(
    val libraryDir: File,
    prefsNode: String = DesktopPaths.PREFS_NODE,
    moveToTrash: ((File) -> Boolean)? = recycleBin(),
    private val closeTimeoutMillis: Long = CLOSE_TIMEOUT_MILLIS,
    settingsFactory: () -> SettingsStore = { PreferencesStore(Preferences.userRoot().node(prefsNode)) },
    private val rearmPollMillis: Long = REARM_POLL_MILLIS,
    serverFactory: ((DesktopApp) -> AppServer)? = null,
    peersFactory: ((DesktopApp) -> PeerWatch)? = null,
    val appDataDir: File = libraryDir,
    private val libraryAccess: LibraryAccess = LibraryAccess(),
    /** Where the app's one-off notes go (stderr, so the log); tests pass their own. */
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    val recipesDir = File(libraryDir, "recipes")
    val imagesDir = File(libraryDir, "recipe-images")
    val cacheDir = File(appDataDir, ".cache")
    private val databaseFile = File(appDataDir, DESKTOP_DB)

    private val _libraryNotice = MutableStateFlow<String?>(null)

    /**
     * What the window says about the library, or null: LIBRARY_BLOCKED_MESSAGE once Controlled folder access refused a
     * real write (isLibraryBlocked: access denied, or P7-R11b "not found" where the folder above it is there), or "Couldn't save to Documents\Meal Planner: <reason>" when a first run's
     * folders failed otherwise. It stays for the session; only [checkLibraryAgain] (Settings' Check again) clears it.
     */
    val libraryNotice: StateFlow<String?> = _libraryNotice.asStateFlow()

    init {
        // The app's own data first: it is never in a guarded folder, so the database and the log always have a home.
        cacheDir.mkdirs()
        // A first run (no database yet) with no folder starts with an empty one. After that a missing recipe
        // folder is one that went away (deleted, or an offline Documents folder): it stays missing, so the sync
        // lists it instead of an empty one made here unindexing every recipe and its planned meals. A library
        // Windows refuses is never fatal: the app starts, reads what is there, and says how to allow it (P7-R10).
        // Any later start writes nothing to the library until it needs to (P7-R10b).
        if (!databaseFile.exists()) firstRunFoldersFailed(libraryAccess.makeFirstRunFolders(recipesDir, imagesDir))
        // M4: an earlier build kept its database in the library. It is neither read nor moved; said once per start.
        val stray = File(libraryDir, DESKTOP_DB)
        if (libraryDir.absoluteFile != appDataDir.absoluteFile && stray.exists()) {
            log("Meal Planner: ${stray.path} is from an earlier version and isn't used; the database is now ${databaseFile.path}.")
        }
    }

    // A write the library really needed failed: the block when it was refused, else why (P7-R10b, P7-R11b). [target] is
    // what was being made, for a failure that names no path.
    private fun libraryWriteFailed(e: IOException, target: File) {
        _libraryNotice.value = libraryNoticeFor(e, target)
    }

    // The first-run folders that couldn't be made: the block when any was refused, else what went wrong with the first.
    private fun firstRunFoldersFailed(failures: List<Pair<File, IOException>>) {
        if (failures.isEmpty()) return
        _libraryNotice.value = if (failures.any { (dir, e) -> isLibraryBlocked(e, dir) }) {
            LIBRARY_BLOCKED_MESSAGE
        } else {
            failures.first().let { (dir, e) -> libraryFolderFailedMessage(dir.name, e) }
        }
    }

    private fun libraryRefused() {
        _libraryNotice.value = LIBRARY_BLOCKED_MESSAGE
    }

    /**
     * Settings' Check again, only when the user presses it: one write to the library (a small file, made and deleted),
     * since nothing is ever written only to look otherwise. Clears the notice when it worked and returns the notice
     * left. A library that comes back writable is synced, which makes the recipe and photo folders only while the index
     * has no recipe files (as a first run would; P7-R11b: the run the block stopped has already made the database, so
     * this, or a later start's sync, is how its folders get made). Blocks briefly: never on the UI thread.
     */
    fun checkLibraryAgain(): String? {
        // M5: one check at a time; a second press while one is writing makes no second write.
        if (!_libraryChecking.compareAndSet(false, true)) return _libraryNotice.value
        try {
            val wasNoticed = _libraryNotice.value != null
            val failure = libraryAccess.checkAgain(libraryDir)
            if (failure == null) _libraryNotice.value = null else libraryWriteFailed(failure.second, failure.first)
            if (wasNoticed && failure == null && !closed.get()) scope.launch { syncAfterCheck() }
            return _libraryNotice.value
        } finally {
            _libraryChecking.value = false
        }
    }

    // Check again's sync. When it made the recipe folder (the index had no recipe files: a first run the block stopped),
    // the folder is watched at once, as start() does after a sync that made it, then looked at once more for a file saved
    // in between. A folder that was there already, or came back some other way, is the re-arm loop's (R3). Not before
    // startup has finished: start() registers its own watcher.
    private suspend fun syncAfterCheck() {
        val existed = recipesDir.isDirectory
        val failure = attempt("Indexing the recipe folder") { folder.sync() }
        if (failure != null || existed || !startedUp) return
        watcherLock.withLock {
            if (isWatching || !recipesDir.isDirectory) return
            val fresh = register() ?: return
            var started = false
            try {
                attempt("Indexing the recipe folder") { folder.sync() }
                if (recipesDir.isDirectory) {
                    watcher = fresh.start(scope)
                    started = true
                }
            } finally {
                if (!started) fresh.close()
            }
        }
    }

    // One watcher at a time: Check again's and the re-arm loop's registrations take turns, each looking again inside.
    private val watcherLock = Mutex()

    @Volatile
    private var startedUp = false

    private val _libraryChecking = MutableStateFlow(false)

    /** True while Check again is writing; its button is disabled (M5). */
    val libraryChecking: StateFlow<Boolean> = _libraryChecking.asStateFlow()

    /** The app's small settings (calendar choice, the tray message, Start with Windows); made on first use. */
    val settings: SettingsStore by lazy(settingsFactory)

    /** Indexes recipesDir and lists what needs attention; built on first use (declared first, so the container's lambda finds it). */
    val folder: RecipeFolder by lazy {
        RecipeFolder(
            recipesDir,
            container.database,
            container.recipes,
            onRemovalsPending = { recheck.trySend(Unit) },
            // Only a sync while the index has no recipe files makes the folder (RecipeFolder decides), and with it the
            // photo folder, as a first run would: that is also how a first run the block stopped recovers once the app is
            // allowed, at Check again or a later start (P7-R11b). Through libraryAccess, so a refused one is listed
            // missing, not made, and puts the notice up (a real write, P7-R10b).
            // Once this session has seen the block, no further attempt is made (each refused write is a Defender
            // notification): the folder is listed missing, as when its create fails. Only Check again clears the notice,
            // and it then syncs, which tries again.
            makeDir = { dir ->
                if (_libraryNotice.value == LIBRARY_BLOCKED_MESSAGE) {
                    System.err.println("Meal Planner: the library is blocked this session; not trying to make ${dir.path}")
                } else {
                    val failures = libraryAccess.makeFirstRunFolders(dir, imagesDir)
                    firstRunFoldersFailed(failures)
                    failures.firstOrNull { it.first == dir }?.let { throw it.second }
                }
            },
        )
    }

    val container: AppContainer = AppContainer(
        databaseFactory = { AppDatabase.openAt(databaseFile) },
        imagesDir = imagesDir,
        cacheDir = cacheDir,
        gatewayFactory = { NoCalendarGateway() },
        choiceFactory = { CalendarChoice(settings) },
        // A save Windows refuses says how to allow the app, and puts the notice up (P7-R10); so does a photo save or an
        // import refused (onLibraryBlocked, P7-R10b).
        files = FolderFileStore(recipesDir, moveToTrash, writer = libraryAccess::writeFile, onBlocked = ::libraryRefused),
        folderStatus = { folder },
        onLibraryBlocked = ::libraryRefused,
    )

    /** This PC as other Meal Planner PCs see it (plan 6): its instance id and its household, kept in app_meta. */
    val identity: PeerIdentity by lazy { PeerIdentity(container.database, databaseFile) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The built-in server (P4-R1), made once the container exists; null when there is none. */
    val server: AppServer? = serverFactory?.invoke(this)

    /** Other Meal Planner PCs on the network (plan 6), made after the server; null when nothing looks for them. */
    val peers: PeerWatch? = peersFactory?.invoke(this)

    private val closed = AtomicBoolean(false)

    @Volatile
    private var watcher: RecipeFolderWatcher? = null

    // R1: a sync that left removals waiting asks for another look a few seconds on. Conflated, so a burst of
    // syncs asks once; read by recheckRemovals, which runs in scope and so stops with close().
    private val recheck = Channel<Unit>(Channel.CONFLATED)

    /** Test seam: runs in [start] right after the startup sync. */
    internal var afterStartupSync: () -> Unit = {}

    /** True while the recipe folder is watched for changes made outside the app. */
    internal val isWatching: Boolean get() = watcher?.isWatching == true

    /**
     * Startup, off the UI thread. First index the folder, then start the server, then watch the folder. The
     * watcher is registered before the first sync and started after it, so a file saved while startup runs still wakes it; a folder
     * the sync itself made (a library with no recipe files yet) is registered right after it. From then on a
     * folder that isn't watched is looked for every rearmPollMillis, and a sync that left a removal waiting (R1) is
     * followed by another RECHECK_MILLIS later. Returns at once: the window shows straight away and fills in as rows
     * arrive. Tests join the returned job.
     */
    fun start(): Job = scope.launch {
        // A sibling of this job, ready before the first sync can ask for a re-check.
        scope.launch { recheckRemovals() }
        var registered = if (recipesDir.isDirectory) register() else null
        var started = false
        try {
            // P6-R4: settle when this household was created, first thing. Google Calendar's send shares this Household
            // (Main passes identity.household), so a send that comes first dates an old database from its file too.
            attempt("Dating the household") { identity.household.created() }
            val syncFailure = attempt("Indexing the recipe folder") { folder.sync() }
            afterStartupSync()
            if (syncFailure == null && registered == null && recipesDir.isDirectory) {
                // The sync made the folder (P3-R5): watch it from now on, then look once more, for a file saved
                // between that sync and this registration. Not after a failed sync: a folder that came back since
                // may still be filling, and waiting for it to settle is the re-arm loop's job (R3).
                registered = register()
                if (registered != null) attempt("Indexing the recipe folder") { folder.sync() }
            }
            // P4-R1: the server answers once the library is indexed, so Alexa's "plan tacos" finds it. A port in use or
            // any other failure is the server's to report; startup goes on either way.
            server?.let { s -> attempt("Starting the server") { s.start(scope) } }
            // P6-R6: announce this PC and look for others beside the server, in the background: never a wait here.
            peers?.let { p -> attempt("Looking for other Meal Planner PCs") { p.start(scope) } }
            val toStart = registered
            if (toStart != null && recipesDir.isDirectory) {
                attempt("Watching the recipe folder") {
                    watcher = toStart.start(scope)
                    started = true
                }
            } else if (!recipesDir.isDirectory) {
                // The sync has already put the missing folder on the Needs attention list.
                System.err.println("Meal Planner: the recipe folder isn't there; it is watched again once it is back: ${recipesDir.path}")
            }
        } finally {
            // Not started (the folder went, or startup was cancelled by close): its handle is let go here.
            if (!started) registered?.close()
        }
        startedUp = true
        // A sibling of this job, so start() still returns once startup is done; close() cancels it with the scope.
        scope.launch { rearmWhenBack() }
    }

    // A watcher on recipesDir, registered but not started; null (and logged) when it can't be made.
    private fun register(): RecipeFolderWatcher? =
        try {
            RecipeFolderWatcher(recipesDir.toPath()) { folder.sync() }
        } catch (e: Exception) {
            System.err.println("Meal Planner: Watching the recipe folder failed: $e")
            null
        }

    /**
     * P3-R5: a watcher stops for good when its folder goes; one whose folder was moved away while watched (which
     * Windows doesn't report) is stopped here. While nothing watches the folder, look every
     * rearmPollMillis; once it is a folder again and looks the same twice in a row (a folder copied back in file
     * by file, indexed half-full, would unindex the rest and their planned meals), register a watcher, sync, and
     * start watching. A folder that settles empty, or part-full, is watched too: its sync holds the missing recipes
     * for the user (RecipeFolder's mass-removal hold) until the files are back.
     */
    private suspend fun rearmWhenBack() {
        var seen: List<Triple<String, Long, Long>>? = null
        while (true) {
            delay(rearmPollMillis)
            if (isWatching && !recipesDir.isDirectory) {
                // Renamed, moved or deleted to the Recycle Bin while watched: on Windows the watch stays valid and
                // says nothing, so it would never stop on its own and the folder would never be re-armed. Stop it
                // here, and sync so the missing folder is listed; the settle-then-re-arm below takes it from there.
                watcher?.closeAndJoin()
                watcher = null
                attempt("Indexing the recipe folder") { folder.sync() }
            }
            if (isWatching || !recipesDir.isDirectory) {
                seen = null
                continue
            }
            val now = snapshot()
            if (now == null || now != seen) {
                seen = now
                continue
            }
            seen = null
            watcherLock.withLock {
                // Check again may have started one meanwhile.
                if (isWatching) return@withLock
                val fresh = register() ?: return@withLock
                var started = false
                try {
                    attempt("Indexing the recipe folder") { folder.sync() }
                    if (recipesDir.isDirectory) {
                        watcher = fresh.start(scope)
                        started = true
                        System.err.println("Meal Planner: the recipe folder is back and watched again: ${recipesDir.path}")
                    }
                } finally {
                    if (!started) fresh.close()
                }
            }
        }
    }

    // R1: each time a sync asks, wait RECHECK_MILLIS (past RecipeFolder.REMOVAL_DELAY_MILLIS) and sync again, so a
    // file deleted by hand leaves the app without another change in the folder to wake the watcher.
    private suspend fun recheckRemovals() {
        while (true) {
            recheck.receive()
            delay(RECHECK_MILLIS)
            attempt("Checking for removed recipe files") { folder.sync() }
        }
    }

    // What the folder holds now (name, size, time), to tell one still being copied in from one that has settled.
    private fun snapshot(): List<Triple<String, Long, Long>>? =
        recipesDir.listFiles()?.map { Triple(it.name, it.length(), it.lastModified()) }?.sortedBy { it.first }

    /**
     * The window's close: [close], off the calling (UI) thread. A save in the UI may hold the recipe lock
     * that a watcher sync is waiting for, and it needs the UI thread to finish and let go; blocking that
     * thread here would wait for ever. True when everything was closed.
     */
    suspend fun shutdown(): Boolean = withContext(Dispatchers.IO) { close() }

    /**
     * Stops the server and the background work, then closes the database. The order matters, because no request or
     * sync may touch a closed database: stop the server (bounded, outside the wait below: a bind under way, up to
     * KtorEngine.START_TIMEOUT_MILLIS, then KtorEngine's grace and timeout), stop announcing to other Meal Planner PCs
     * (about 1 s), stop the watcher and wait for a sync it is running, stop the startup work, the re-arm
     * loop and the removal re-check and wait for them, wait for a recipe, meal plan, shopping or pantry write still running, and only then
     * close the database.
     * The waits are bounded by closeTimeoutMillis: when they run out, the database is left open (the process
     * exits without closing it, which SQLite survives; closing it under a live sync would not be safe) and false
     * is returned. Blocks: never call it on the UI thread (see [shutdown]). Calling it again after it has
     * succeeded does nothing.
     */
    fun close(): Boolean {
        if (!closed.compareAndSet(false, true)) return true
        // P4-R1: no request may reach a closing database, so the server goes first.
        server?.let { s ->
            try {
                s.stop()
            } catch (e: Exception) {
                System.err.println("Meal Planner: stopping the server failed: $e")
            }
        }
        // P6-R6: stop announcing once nothing here answers any more, and before the database closes; bounded (about 1 s).
        // A look under way is cancelled, not waited for here; the scope's bounded wait below sees it end.
        peers?.let { p ->
            try {
                p.close()
            } catch (e: Exception) {
                System.err.println("Meal Planner: stopping the look-out for other PCs failed: $e")
            }
        }
        val stopped = runBlocking {
            withTimeoutOrNull(closeTimeoutMillis) {
                watcher?.closeAndJoin()
                scope.coroutineContext.job.cancelAndJoin()
                // Startup or the re-arm loop may have made a watcher after the first close; the cancel stopped it.
                watcher?.closeAndJoin()
                // A save from the window (not this scope) may still be writing: let it finish first. The meal plan, the
                // shopping list and the pantry have their own write locks (P6-R9).
                container.recipes.awaitWrites()
                container.plans.awaitWrites()
                container.shopping.awaitWrites()
                container.pantry.awaitWrites()
                true
            } ?: false
        }
        if (!stopped) {
            System.err.println("Meal Planner: background work didn't stop within $closeTimeoutMillis ms; the database is left open.")
            closed.set(false) // a later close can try again
            return false
        }
        // Deletes leftover files only; it never touches the database.
        container.startupCleanup.join(closeTimeoutMillis)
        container.database.close()
        return true
    }

    // One startup step: a failure is logged and returned, not thrown; null means it worked.
    private suspend fun attempt(what: String, block: suspend () -> Unit): Exception? =
        try {
            block()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Meal Planner: $what failed: $e")
            e
        }

    companion object {
        /** How long [close] waits for the background work before leaving the database to the process exit. */
        const val CLOSE_TIMEOUT_MILLIS = 5_000L

        /** How often a recipe folder that isn't watched is looked for (P3-R5). */
        const val REARM_POLL_MILLIS = 30_000L

        /** How long after a sync that left a removal waiting the folder is synced again (R1). */
        const val RECHECK_MILLIS = 3_000L
    }
}
