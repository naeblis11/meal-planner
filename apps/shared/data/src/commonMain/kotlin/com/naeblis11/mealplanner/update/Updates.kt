package com.naeblis11.mealplanner.update

import com.naeblis11.mealplanner.app.SettingsStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.PublicKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * The update check (spec "Updates"), the same on the PC and the phone. At launch (at most once a day, while
 * Automatically is on) and on Check for updates it fetches latest.json once, as capped bytes, and latest.json.sig through
 * [http], verifies the signature against [publicKey] over exactly those bytes before reading the list at all, then
 * parses those same bytes and offers this app's entry only when it is newer than [running]. A pair that doesn't verify
 * is fetched again, both files, once (P8-F1: a release published between the two fetches). A failed or unverifiable
 * check says so quietly and changes nothing. Install downloads the file into [dir] (the app's cache, a folder named
 * exactly `updates`), checks its size and SHA-256 against the signed list, deletes anything that doesn't match, and only
 * then hands it to [installer]; nothing is ever installed without the user's Install. A download has a deadline
 * (ReleaseHttp), stops when its coroutine is cancelled, and leaves no partial file behind. One check or install runs at
 * a time, in [scope], which outlives any screen (a download isn't cancelled by leaving Settings). [unavailable] says
 * why this copy never checks (the PC's preview, a phone's debug build); a copy without a key never checks either.
 *
 * The clean-ups (P8-PF6) delete only plain files named `update-*` directly inside [dir], never a folder and never
 * recursively, because on the PC [dir] sits beside the secrets. A [dir] that is itself a link or junction is refused
 * (FOLDER_REDIRECTED): no clean-up, no check, no download. Its parent may be reached through one. A download is written only to a `.part` made
 * new, and a folder or link at the `.part`'s or the file's name is never written through, moved onto or removed.
 */
class Updates(
    val running: RunningApp,
    private val publicKey: PublicKey?,
    private val installer: UpdateInstaller,
    private val settings: SettingsStore,
    val dir: File,
    val http: ReleaseHttp = ReleaseHttp(),
    val endpoints: ReleaseEndpoints = ReleaseEndpoints.GITHUB,
    unavailable: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : UpdateControls {
    init {
        require(dir.name == DIR_NAME) { "The updates folder must be named $DIR_NAME." }
    }

    /** Why this copy never checks, or null when it does. */
    val unavailableReason: String? = unavailable
        ?: if (publicKey == null) {
            UpdateMessages.NO_KEY
        } else if (redirected(dir)) {
            UpdateMessages.FOLDER_REDIRECTED
        } else {
            null
        }

    private val busy = Mutex()

    private val _state = MutableStateFlow(
        UpdateStatus(
            offered = unavailableReason == null,
            unavailableReason = unavailableReason,
            automatic = read { settings.getString(AUTOMATIC) } != OFF,
            lastChecked = read { settings.getLong(LAST_CHECKED) },
            installClosesApp = installer.closesApp,
        ),
    )

    override val state: StateFlow<UpdateStatus> = _state.asStateFlow()

    /** The launch check, in the background: what the PC's main and the phone's first activity start. */
    fun startLaunchCheck(): Job = scope.launch { guarded("the launch check") { checkAtLaunch() } }

    override fun checkNow() {
        scope.launch { guarded("the check") { check(manual = true) } }
    }

    override fun install() {
        scope.launch { guarded("the install") { installOffer() } }
    }

    override fun setAutomatic(on: Boolean) {
        write(AUTOMATIC to if (on) ON else OFF)
        _state.update { it.copy(automatic = on) }
    }

    override fun openInstallPermission() {
        try {
            installer.openInstallPermission()
        } catch (e: Exception) {
            log("opening the install permission", e)
        }
    }

    /**
     * Android: the user is back (an activity resumed), perhaps from the "install unknown apps" page. Once installs are
     * allowed, the "allow installs" banner goes; nothing else changes and nothing is installed. Cheap, and safe on any
     * thread (the installer only asks the package manager).
     */
    fun recheckPermission() {
        if (!_state.value.needsPermission) return
        val allowed = try {
            installer.canInstall()
        } catch (e: Exception) {
            log("asking whether installs are allowed", e)
            false
        }
        if (allowed) _state.update { it.copy(needsPermission = false) }
    }

    override fun dismissNotice() {
        _state.update { it.copy(noticeDismissed = true) }
    }

    /** At launch: deletes downloads a day old, then checks if Automatically is on and a day has passed since the last try. */
    suspend fun checkAtLaunch() {
        if (unavailableReason != null) return
        val now = clock()
        withContext(Dispatchers.IO) { prune(now) }
        if (!UpdateSchedule.launchCheckDue(_state.value.automatic, read { settings.getLong(LAST_ATTEMPT) }, now)) return
        check(manual = false)
    }

    /** One check. [manual] is Settings' button; a launch check's find also shows the notice. Ignored while one runs. */
    suspend fun check(manual: Boolean) {
        val key = publicKey
        if (unavailableReason != null || key == null) return
        if (!busy.tryLock()) return
        try {
            val now = clock()
            // Attempts count for the day's limit, so a PC without a network doesn't try at every launch.
            write(LAST_ATTEMPT to now)
            _state.update { it.copy(phase = UpdatePhase.CHECKING, message = null, problem = null) }
            when (val found = withContext(Dispatchers.IO) { look(key) }) {
                is Found.Latest -> {
                    write(LAST_CHECKED to now)
                    val offer = found.offer
                    _state.update {
                        val same = it.offer == offer
                        it.copy(
                            phase = UpdatePhase.IDLE,
                            lastChecked = now,
                            offer = offer,
                            upToDate = offer == null,
                            foundAutomatically = offer != null && (!manual || (same && it.foundAutomatically)),
                            noticeDismissed = same && it.noticeDismissed,
                            waitingToInstall = same && it.waitingToInstall,
                        )
                    }
                }
                // Says so, and changes nothing else: the last offer and Last checked stay as they were.
                is Found.Failed -> _state.update { it.copy(phase = UpdatePhase.IDLE, message = found.message) }
            }
        } finally {
            _state.update { if (it.phase == UpdatePhase.CHECKING) it.copy(phase = UpdatePhase.IDLE) else it }
            busy.unlock()
        }
    }

    /** Install the offered update: asks for Android's permission first, then downloads, verifies and hands it over. */
    suspend fun installOffer() {
        if (unavailableReason != null) return
        val offer = _state.value.offer ?: return
        if (!busy.tryLock()) return
        try {
            val allowed = try {
                installer.canInstall()
            } catch (e: Exception) {
                log("asking whether installs are allowed", e)
                false
            }
            if (!allowed) {
                _state.update { it.copy(needsPermission = true, problem = null) }
                return
            }
            _state.update { it.copy(phase = UpdatePhase.DOWNLOADING, downloaded = 0, needsPermission = false, problem = null, message = null) }
            val outcome = withContext(Dispatchers.IO) {
                val job = coroutineContext.job
                fetchAndInstall(offer) { job.ensureActive() }
            }
            _state.update {
                when (outcome) {
                    Outcome.Started -> it.copy(phase = UpdatePhase.IDLE, downloaded = 0, problem = null, waitingToInstall = false)
                    // The verified file waits in the cache; the notice offers Install again (P8-PF11).
                    Outcome.Waiting -> it.copy(phase = UpdatePhase.IDLE, downloaded = 0, problem = null, waitingToInstall = true, noticeDismissed = false)
                    is Outcome.Failed -> it.copy(phase = UpdatePhase.IDLE, downloaded = 0, problem = outcome.message, waitingToInstall = false)
                }
            }
        } finally {
            _state.update { if (it.phase != UpdatePhase.IDLE) it.copy(phase = UpdatePhase.IDLE, downloaded = 0) else it }
            busy.unlock()
        }
    }

    private sealed interface Found {
        data class Latest(val offer: UpdateOffer?) : Found

        data class Failed(val message: String) : Found
    }

    private sealed interface Outcome {
        data object Started : Outcome

        data object Waiting : Outcome

        data class Failed(val message: String) : Outcome
    }

    // Blocking. P8-F1: latest.json and its .sig each come through their own `latest` redirect, so a release published
    // between the two fetches pairs one release's list with the next one's signature, and a good release looks unsigned.
    // A pair that doesn't verify (a bad, missing or oversized signature) is fetched again, both files, exactly once;
    // every rule (hosts, redirects, caps, the key) applies to the second pair as to the first. Resolving `latest` once
    // and fetching both from that tag would lean on the shape of GitHub's redirect, which ReleaseHttp doesn't read.
    private fun look(key: PublicKey): Found {
        val first = lookOnce(key)
        if (first !is Found.Failed || first.message != UpdateMessages.NOT_VERIFIED) return first
        return lookOnce(key)
    }

    // Blocking. The list read once as capped bytes, its capped signature, those exact bytes verified before the list is
    // read at all, then those same bytes parsed for this app's offer. Never read twice within one pair.
    private fun lookOnce(key: PublicKey): Found {
        val list = try {
            http.fetch(endpoints.latest(MANIFEST), ReleaseManifest.MAX_BYTES)
        } catch (e: ReleaseStatusException) {
            log("fetching the update list", e)
            return Found.Failed(if (e.status == 404) UpdateMessages.NO_LIST else UpdateMessages.CHECK_FAILED)
        } catch (e: IOException) {
            log("fetching the update list", e)
            return Found.Failed(UpdateMessages.CHECK_FAILED)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("fetching the update list", e)
            return Found.Failed(UpdateMessages.CHECK_FAILED)
        }
        val signature = try {
            http.fetch(endpoints.latest(SIGNATURE), ReleaseSignature.MAX_BYTES)
        } catch (e: ReleaseStatusException) {
            log("fetching the signature", e)
            return Found.Failed(if (e.status == 404) UpdateMessages.NOT_VERIFIED else UpdateMessages.CHECK_FAILED)
        } catch (e: ReleaseSizeException) {
            log("fetching the signature", e)
            return Found.Failed(UpdateMessages.NOT_VERIFIED)
        } catch (e: IOException) {
            log("fetching the signature", e)
            return Found.Failed(UpdateMessages.CHECK_FAILED)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("fetching the signature", e)
            return Found.Failed(UpdateMessages.CHECK_FAILED)
        }
        if (!verifies(list, signature.decodeToString(), key)) return Found.Failed(UpdateMessages.NOT_VERIFIED)
        val manifest = try {
            ReleaseManifest.parse(list.decodeToString())
        } catch (e: ManifestException) {
            log("reading the update list", e)
            return Found.Failed(UpdateMessages.UNREADABLE)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("reading the update list", e)
            return Found.Failed(UpdateMessages.UNREADABLE)
        }
        return Found.Latest(manifest.offerFor(running))
    }

    // Blocking. [active] throws once the install's coroutine is cancelled, which stops the download between reads.
    private fun fetchAndInstall(offer: UpdateOffer, active: () -> Unit): Outcome {
        // Checked before a cached file is trusted, and again below once the folder is made.
        if (redirected(dir)) return Outcome.Failed(UpdateMessages.FOLDER_REDIRECTED)
        val file = File(dir, FILE_PREFIX + offer.file)
        if (!matches(file, offer)) {
            val part = File(dir, FILE_PREFIX + offer.file + PART)
            try {
                if (!Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS) && !dir.mkdirs()) throw IOException("Can't make the updates folder.")
                // A folder that is (or now sits behind) a link or junction is neither cleaned nor written to.
                if (redirected(dir)) return Outcome.Failed(UpdateMessages.FOLDER_REDIRECTED)
                // Only this download is kept: an older one, a half one, or a file that didn't match goes.
                ownFiles().forEach { it.delete() }
                // Whatever is still at either name isn't a plain file (a folder, a link): never written through,
                // moved onto or removed.
                if (Files.exists(part.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    return Outcome.Failed(UpdateMessages.DOWNLOAD_FAILED)
                }
                val sha = http.download(endpoints.asset(offer.tag, offer.file), offer.size, part) { done ->
                    active()
                    _state.update { it.copy(downloaded = done) }
                }
                if (sha != offer.sha256) return Outcome.Failed(UpdateMessages.HASH_MISMATCH)
                if (!isPlainFile(part) || Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return Outcome.Failed(UpdateMessages.DOWNLOAD_FAILED)
                // No REPLACE_EXISTING: the name was just seen free, and anything that appears there since is refused.
                Files.move(part.toPath(), file.toPath())
            } catch (e: ReleaseSizeException) {
                log("downloading the update", e)
                return Outcome.Failed(UpdateMessages.SIZE_MISMATCH)
            } catch (e: IOException) {
                log("downloading the update", e)
                return Outcome.Failed(UpdateMessages.DOWNLOAD_FAILED)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("downloading the update", e)
                return Outcome.Failed(UpdateMessages.DOWNLOAD_FAILED)
            } finally {
                // Cancelled, timed out, refused or mismatched: no partial file is ever left (after the move there is
                // none). Only a plain file is removed: a folder or a link at that name stays where it is.
                deletePlainFile(part)
            }
            // Belt and braces: the file handed over is exactly the one the signed list gives.
            if (!matches(file, offer)) {
                deletePlainFile(file)
                return Outcome.Failed(UpdateMessages.HASH_MISMATCH)
            }
        }
        val now = try {
            installer.canStartNow()
        } catch (e: Exception) {
            log("asking whether the installer can start", e)
            false
        }
        if (!now) return Outcome.Waiting
        _state.update { it.copy(phase = UpdatePhase.INSTALLING) }
        return try {
            installer.install(file)
            Outcome.Started
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("starting the installer", e)
            Outcome.Failed(UpdateMessages.INSTALL_FAILED)
        }
    }

    // A file already in the cache counts only when it is exactly what the signed list gives.
    private fun matches(file: File, offer: UpdateOffer): Boolean =
        try {
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() == offer.size && Sha256.of(file) == offer.sha256
        } catch (e: Exception) {
            log("reading the downloaded update", e)
            false
        }

    // Only this class's own files: plain files named update-* directly in the updates folder; never a folder.
    private fun ownFiles(): List<File> =
        dir.listFiles().orEmpty().filter { it.name.startsWith(FILE_PREFIX) && isPlainFile(it) }

    private fun isPlainFile(file: File): Boolean = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun deletePlainFile(file: File) {
        if (isPlainFile(file)) file.delete()
    }

    private fun prune(now: Long) {
        // A folder that is (or sits behind) a link or junction is never cleaned.
        if (redirected(dir)) return
        try {
            ownFiles().filter { now - it.lastModified() >= UpdateSchedule.DAY_MILLIS }.forEach { it.delete() }
        } catch (e: Exception) {
            log("cleaning the updates folder", e)
        }
    }

    // A launched job never ends in an uncaught exception (on Android that would end the app); cancellation still stops it.
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log(what, e)
        }
    }

    // A settings failure (the PC's registry, say) is read as "not set" and never stops a check.
    private fun <T> read(block: () -> T?): T? =
        try {
            block()
        } catch (e: Exception) {
            log("reading the update settings", e)
            null
        }

    private fun write(value: Pair<String, Any>) {
        try {
            settings.put(mapOf(value))
        } catch (e: Exception) {
            log("saving the update settings", e)
        }
    }

    companion object {
        const val AUTOMATIC = "update_automatic"
        const val LAST_ATTEMPT = "update_last_attempt"
        const val LAST_CHECKED = "update_last_checked"
        const val ON = "on"
        const val OFF = "off"
        const val MANIFEST = "latest.json"
        const val SIGNATURE = "latest.json.sig"
        const val PART = ".part"

        /** The only name [dir] may have (P8-PF6): the clean-ups never run anywhere else. */
        const val DIR_NAME = "updates"

        /** Every file Updates writes starts with this, and the clean-ups delete nothing else. */
        const val FILE_PREFIX = "update-"

        /**
         * The built-in release key, or null when this copy has none, or it isn't P-256 (IllegalArgumentException), or
         * ReleaseSignature can't even start (an initialiser error on a device without the JDK's P-256 table). Null means
         * no update: a key problem is never taken as verified.
         */
        fun releaseKey(load: () -> PublicKey? = ReleaseKey::builtIn): PublicKey? =
            try {
                load()
            } catch (e: Throwable) {
                log("loading the release key", e)
                null
            }

        /**
         * Whether [dir] itself, once it exists, is a link or junction rather than a plain folder: a symbolic link, not a
         * directory, or a real path other than its real parent's plus its own name. Only the updates folder is judged:
         * a parent reached through a junction (a redirected profile or %LOCALAPPDATA%) or written in 8.3 short form is
         * fine. Such a folder is never cleaned or written to, because the clean-ups and the download would then act on
         * whatever it points at. Fails closed: a folder that can't be resolved counts as redirected. A folder not made
         * yet isn't (it is checked again once made).
         */
        internal fun redirected(dir: File): Boolean {
            val path = dir.toPath().toAbsolutePath().normalize()
            return try {
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
                val parent = path.parent ?: return true
                val name = path.fileName ?: return true
                Files.isSymbolicLink(path) ||
                    !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
                    path.toRealPath() != parent.toRealPath().resolve(name)
            } catch (e: Exception) {
                log("resolving the updates folder", e)
                true
            }
        }

        /** Whether [signature] is [key]'s over exactly [data]; anything [verify] throws, even an Error, is "not verified". */
        internal fun verifies(
            data: ByteArray,
            signature: String,
            key: PublicKey,
            verify: (ByteArray, String, PublicKey) -> Boolean = ReleaseSignature::verify,
        ): Boolean =
            try {
                verify(data, signature, key)
            } catch (e: Throwable) {
                log("checking the signature", e)
                false
            }

        // The exception's class only: a message could carry an address, and nothing here logs addresses, contents or keys.
        private fun log(what: String, e: Throwable) {
            System.err.println("Meal Planner: update check: $what failed (${e.javaClass.simpleName})")
        }
    }
}
