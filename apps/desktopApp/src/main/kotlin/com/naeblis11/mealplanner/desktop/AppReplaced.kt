package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.settings.AppReplaced
import com.naeblis11.mealplanner.settings.RestartControls
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What one look at the installed files found. */
enum class InstallLook {
    /** Everything is as it was at startup. */
    SAME,

    /** Something definitely changed: a different size, time, jar set or cfg, or a file that is gone. */
    CHANGED,

    /** A read failed for another reason (a SecurityException, an I/O error): not known, so never "replaced". */
    UNKNOWN,
}

/** Reads the installed files. Tests fake it to make a read fail; the real one is [NioInstallReader]. */
interface InstallReader {
    /** The file's size and last-modified time in milliseconds; NoSuchFileException when it is gone. */
    fun sizeAndTime(path: Path): Pair<Long, Long>

    /** The names of the `*.jar` files in [dir]; NoSuchFileException when the folder is gone. */
    fun jarNames(dir: Path): Set<String>

    /** The file's bytes; NoSuchFileException when it is gone. */
    fun bytes(path: Path): ByteArray
}

/** The real reader, through NIO, so a missing file is told apart from a read that failed. */
object NioInstallReader : InstallReader {
    override fun sizeAndTime(path: Path): Pair<Long, Long> = Files.size(path) to Files.getLastModifiedTime(path).toMillis()

    override fun jarNames(dir: Path): Set<String> = Files.newDirectoryStream(dir, "*.jar").use { stream ->
        stream.map { it.fileName.toString() }.toSortedSet()
    }

    override fun bytes(path: Path): ByteArray = Files.readAllBytes(path)
}

/**
 * P7-R12: the installed app as it was at startup. The jar the app's code was loaded from (its size and last-modified
 * time), the set of `*.jar` names in its folder, and the launcher's `Meal Planner.cfg` beside them (a hash of its
 * content, as it is small). The JVM holds its classpath jars open, so msiexec may only replace the watched jar at the
 * next reboot; the jar names (each carries a content hash) and the cfg are not held open, so a new MSI changes them at
 * once. [look] finds CHANGED when any one of them changed or is gone, and UNKNOWN when a read failed otherwise, which
 * never counts as replaced. Tests make one over temp files ([of]).
 */
class AppJar private constructor(
    val file: File,
    private val reader: InstallReader,
    private val jarStamp: Pair<Long, Long>,
    private val jarNames: Set<String>?,
    private val cfgHash: String?,
) {
    private val path: Path = file.toPath()
    private val dir: Path? = path.parent
    private val cfg: Path? = dir?.resolve(CFG_NAME)

    /** One look at the jar, the jar set and the cfg; never throws. */
    fun look(): InstallLook {
        val looks = listOf(
            compare { reader.sizeAndTime(path) != jarStamp },
            if (jarNames == null || dir == null) InstallLook.SAME else compare { reader.jarNames(dir) != jarNames },
            if (cfgHash == null || cfg == null) InstallLook.SAME else compare { hash(reader.bytes(cfg)) != cfgHash },
        )
        return when {
            InstallLook.CHANGED in looks -> InstallLook.CHANGED
            InstallLook.UNKNOWN in looks -> InstallLook.UNKNOWN
            else -> InstallLook.SAME
        }
    }

    /** Whether [look] finds a definite change. */
    fun replaced(): Boolean = look() == InstallLook.CHANGED

    companion object {
        /** The jpackage launcher's settings, beside the jars in `app\`. */
        const val CFG_NAME = "Meal Planner.cfg"

        // A file that is gone is a definite change; any other failed read is not known.
        private fun compare(changed: () -> Boolean): InstallLook = try {
            if (changed()) InstallLook.CHANGED else InstallLook.SAME
        } catch (e: NoSuchFileException) {
            InstallLook.CHANGED
        } catch (e: Exception) {
            InstallLook.UNKNOWN
        }

        private fun hash(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        /**
         * [file] and what is beside it as they are now, or null when it isn't a jar file (the classes folder of a
         * development run) or can't be read. The jar set and the cfg are watched only if they could be read now.
         */
        fun of(file: File, reader: InstallReader = NioInstallReader): AppJar? {
            if (!file.name.endsWith(".jar", ignoreCase = true)) return null
            val path = file.toPath()
            val stamp = try {
                if (!Files.isRegularFile(path)) return null
                reader.sizeAndTime(path)
            } catch (e: Exception) {
                return null
            }
            val dir = path.parent
            val names = dir?.let { runCatching { reader.jarNames(it) }.getOrNull() }
            val cfg = dir?.resolve(CFG_NAME)
            val cfgHash = cfg?.let { runCatching { hash(reader.bytes(it)) }.getOrNull() }
            return AppJar(file, reader, stamp, names, cfgHash)
        }

        /** The jar [code] was loaded from (its ProtectionDomain's code source), or null when there is none. */
        fun running(code: Class<*> = AppJar::class.java): AppJar? = try {
            code.protectionDomain?.codeSource?.location?.toURI()?.let { of(File(it)) }
        } catch (e: Exception) {
            null
        }
    }
}

/** Starts a program and doesn't wait for it. Tests fake it; they never start a real process. */
fun interface ProcessStarter {
    fun start(command: List<String>)
}

/** The real one: output discarded, never waited for. */
object SystemProcessStarter : ProcessStarter {
    override fun start(command: List<String>) {
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}

/**
 * P7-R12: opens the installed app again after a Restart now. Only the installed app ([installed] is
 * StartWithWindows.INSTALLED_PROPERTY's value, "true" in the packaged launcher) with a valid launcher
 * (AllowApp.validatedExe: exactly `<FOLDERID_LocalAppData>\Meal-Planner\Meal Planner.exe`, there, with safe characters).
 *
 * The launcher is handed to Windows' own explorer.exe (from FOLDERID_System's parent, never the environment), which
 * opens it outside this process's job object: the packaged launcher runs the app in a job, and a child started straight
 * from here would end with this process (P8-PF2). [launch] is called only after the bounded close has released the
 * single-instance lock (closeForQuit), so the new copy never finds this one still holding it and asks it to show.
 */
class Relauncher(
    private val installed: Boolean = System.getProperty(StartWithWindows.INSTALLED_PROPERTY) == "true",
    private val launcher: () -> String? = { StartWithWindows.launcherPath() },
    private val folders: WindowsFolders = KnownWindowsFolders,
    private val starter: ProcessStarter = SystemProcessStarter,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    /** Whether Restart now can be offered, as things are now. */
    val available: Boolean get() = command() != null

    /** The installed app whose launcher is gone: uninstalled, or an install under way. */
    fun launcherGone(): Boolean = try {
        installed && launcher()?.takeIf { it.isNotBlank() }?.let { !File(it).exists() } == true
    } catch (e: Exception) {
        false
    }

    /** explorer.exe and the launcher, or null when this isn't the installed app or either can't be trusted. */
    fun command(): List<String>? {
        if (!installed) return null
        val local = folders.localAppData() ?: return null
        val exe = AllowApp.validatedExe(launcher(), local) ?: return null
        val explorer = windowsExplorer(folders) ?: return null
        return listOf(explorer.path, exe.path)
    }

    /** Opens the launcher; false (logged, never thrown) when it couldn't. */
    fun launch(): Boolean {
        val command = command()
        if (command == null) {
            log("$LOG_PREFIX not the installed app; open it again yourself")
            return false
        }
        return try {
            starter.start(command)
            log("$LOG_PREFIX started")
            true
        } catch (e: Exception) {
            log("$LOG_PREFIX failed (${e.javaClass.simpleName}); open it again yourself")
            false
        }
    }

    companion object {
        const val EXPLORER = "explorer.exe"
        private const val LOG_PREFIX = "Meal Planner: restarting after an update:"
    }
}

/**
 * Windows' own explorer.exe, in the Windows folder (FOLDERID_System's parent), never from the environment; null when
 * Windows doesn't name System32 as an absolute folder or explorer.exe isn't there. What explorer opens runs outside
 * this process's job object (P8-PF2): Restart now's launcher (Relauncher) and an update's MSI (MsiInstaller).
 */
internal fun windowsExplorer(folders: WindowsFolders): File? =
    folders.system()?.takeIf { it.isAbsolute }?.parentFile?.let { File(it, Relauncher.EXPLORER) }?.takeIf { it.isFile }

/**
 * P7-R12: the window's "Meal Planner has been updated" notice. [look] runs each time the window is shown (WindowShell's
 * beforeShow: a second launch's show request, the tray's Open, a recipe from the extension); it looks at [jar] (null
 * in development: never replaced), and once a look finds a definite change the notice stays for the session. While it
 * is up, each look and each Restart now ask [relauncher] again, so the button follows the launcher (and the wording
 * says when the launcher is gone too). [restart] is Restart now (main's guarded quit, then the relauncher).
 */
class ReplacedNotice(
    private val jar: AppJar?,
    private val relauncher: Relauncher,
    private val restart: () -> Unit = {},
    private val log: (String) -> Unit = { System.err.println(it) },
) : RestartControls {
    private val _replaced = MutableStateFlow<AppReplaced?>(null)
    override val replaced: StateFlow<AppReplaced?> = _replaced.asStateFlow()

    /** Looks once; true when this look found the app replaced. Never throws. */
    @Synchronized
    fun look(): Boolean {
        if (_replaced.value != null) {
            _replaced.value = state()
            return false
        }
        if (jar == null || jar.look() != InstallLook.CHANGED) return false
        _replaced.value = state()
        log("Meal Planner: the app was replaced on disk; restart to use the new version.")
        return true
    }

    // What the notice offers as things are now.
    private fun state(): AppReplaced {
        val canRestart = try {
            relauncher.available
        } catch (e: Exception) {
            false
        }
        return AppReplaced(canRestart = canRestart, removed = !canRestart && relauncher.launcherGone())
    }

    /** Restart now, if the launcher is still there; else the notice drops the button and says what to do instead. */
    override fun restartNow() {
        val now = synchronized(this) {
            if (_replaced.value == null) return
            state().also { _replaced.value = it }
        }
        if (now.canRestart) restart()
    }
}

/** The tray icon's tooltip, which says so too once the app was replaced or removed. */
internal fun trayTooltip(replaced: AppReplaced?): String = when {
    replaced == null -> TrayNotice.TITLE
    replaced.removed -> TRAY_REMOVED_TOOLTIP
    else -> TRAY_REPLACED_TOOLTIP
}

internal const val TRAY_REPLACED_TOOLTIP = "Meal Planner: updated, restart to use the new version"
internal const val TRAY_REMOVED_TOOLTIP = "Meal Planner: removed or being updated"
