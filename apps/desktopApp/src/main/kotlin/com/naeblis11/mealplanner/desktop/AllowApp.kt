package com.naeblis11.mealplanner.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.io.File
import java.io.IOException
import java.util.Base64

/** How an elevated run went. Tests use a fake runner; nothing in the tests ever elevates. */
sealed interface ElevatedOutcome {
    /** The program ran and ended with [code]. */
    data class Exited(val code: Int) : ElevatedOutcome

    /** ShellExecuteEx failed with Windows error [error]: ERROR_CANCELLED (1223) when the user declined the prompt. */
    data class NotStarted(val error: Int) : ElevatedOutcome

    /** Windows started nothing it could hand back (no process handle). */
    data object NoProcess : ElevatedOutcome

    /** It didn't end within the wait. */
    data object TimedOut : ElevatedOutcome

    /** Waiting on it failed (WAIT_FAILED) with Windows error [error]. */
    data class WaitFailed(val error: Int) : ElevatedOutcome

    /** It ended, but its exit code couldn't be read (Windows error [error]). */
    data class NoExitCode(val error: Int) : ElevatedOutcome
}

/** Runs a program elevated, through Windows' own UAC prompt, waiting at most timeoutMillis. */
fun interface ElevatedRunner {
    fun run(file: String, parameters: String, timeoutMillis: Long): ElevatedOutcome
}

/**
 * The real one: ShellExecuteEx with the "runas" verb and SEE_MASK_NOCLOSEPROCESS, so the app keeps the process handle,
 * waits for it (bounded) and reads its exit code. The elevated process runs outside the app's job object, as it must.
 * [owner] is the app's window, so the prompt belongs to it; null (or a window with no native peer) means no owner.
 * Errors are read with Native.getLastError(): jna-platform's Shell32 and Kernel32 mappings (W32APIOptions) have JNA
 * save each call's last error, which a later Kernel32.GetLastError() call could find overwritten by the JVM.
 */
class JnaElevatedRunner(private val owner: () -> java.awt.Window? = { null }) : ElevatedRunner {
    override fun run(file: String, parameters: String, timeoutMillis: Long): ElevatedOutcome {
        val info = ShellAPI.SHELLEXECUTEINFO().apply {
            fMask = Shell32.SEE_MASK_NOCLOSEPROCESS
            hwnd = ownerHandle()
            lpVerb = "runas"
            lpFile = file
            lpParameters = parameters
            nShow = WinUser.SW_HIDE
        }
        if (!Shell32.INSTANCE.ShellExecuteEx(info)) return ElevatedOutcome.NotStarted(Native.getLastError())
        val process = info.hProcess ?: return ElevatedOutcome.NoProcess
        try {
            when (Kernel32.INSTANCE.WaitForSingleObject(process, timeoutMillis.toInt())) {
                WinBase.WAIT_OBJECT_0 -> Unit
                WinBase.WAIT_FAILED -> return ElevatedOutcome.WaitFailed(Native.getLastError())
                else -> return ElevatedOutcome.TimedOut
            }
            val code = IntByReference()
            if (!Kernel32.INSTANCE.GetExitCodeProcess(process, code)) return ElevatedOutcome.NoExitCode(Native.getLastError())
            return ElevatedOutcome.Exited(code.value)
        } finally {
            Kernel32.INSTANCE.CloseHandle(process)
        }
    }

    private fun ownerHandle(): WinDef.HWND? = try {
        owner()?.takeIf { it.isDisplayable }?.let { WinDef.HWND(Native.getWindowPointer(it)) }
    } catch (e: Throwable) {
        null
    }
}

/**
 * Windows' own folders, from the known-folder API: never from the environment (SystemRoot, LOCALAPPDATA), which
 * whatever started the app can set. Null when Windows doesn't say. Tests use their own; they never call the real API.
 */
interface WindowsFolders {
    /** FOLDERID_System (System32). */
    fun system(): File?

    /** FOLDERID_LocalAppData. */
    fun localAppData(): File?
}

/** The real one: SHGetKnownFolderPath through JNA. */
object KnownWindowsFolders : WindowsFolders {
    override fun system(): File? = known(KnownFolders.FOLDERID_System)

    override fun localAppData(): File? = known(KnownFolders.FOLDERID_LocalAppData)

    private fun known(id: Guid.GUID): File? = try {
        Shell32Util.getKnownFolderPath(id)?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isAbsolute }
    } catch (e: Throwable) {
        null
    }
}

/**
 * P7-R11: "Allow Meal Planner (asks for admin)", for Controlled folder access. Offered only by the installed app:
 * [launcher] (StartWithWindows.launcherPath) must be exactly `<FOLDERID_LocalAppData>\Meal-Planner\Meal Planner.exe`
 * (P7-R11a), and both folders come from [folders], never the environment. [run] asks Windows, through [runner] and UAC,
 * to add that exe to the allowed apps (Add-MpPreference), then [recheck]s the library (Check again, which returns the
 * notice left, null when it can be written). Blocks for up to TIMEOUT_MILLIS: never on the UI thread. The command is
 * built only from the app's own validated path, never from anything the user typed, and the log says only how it went
 * and the exit code, never the command line.
 */
class AllowApp(
    private val runner: ElevatedRunner,
    private val launcher: () -> String? = { StartWithWindows.launcherPath() },
    private val folders: WindowsFolders = KnownWindowsFolders,
    private val recheck: () -> String?,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    /** Whether this is the installed app, whose exe can be allowed, and Windows' PowerShell is where it should be. */
    val available: Boolean get() = target() != null

    /** Asks, then checks the library again. Null when the library can now be written; else what to show. */
    fun run(): String? {
        val target = target()
        if (target == null) {
            log("$LOG_PREFIX not the installed app")
            return failedMessage("not the installed app")
        }
        val (exe, powerShell) = target
        val outcome = try {
            runner.run(powerShell.path, parameters(exe), TIMEOUT_MILLIS)
        } catch (e: Exception) {
            log("$LOG_PREFIX failed (${e.javaClass.simpleName})")
            return failedMessage("couldn't ask Windows")
        }
        return when (outcome) {
            ElevatedOutcome.Exited(0) -> {
                log("$LOG_PREFIX allowed (exit 0)")
                if (recheck() == null) null else STILL_BLOCKED
            }
            ElevatedOutcome.NotStarted(ERROR_CANCELLED) -> {
                log("$LOG_PREFIX cancelled (error $ERROR_CANCELLED)")
                NOT_ALLOWED
            }
            is ElevatedOutcome.Exited -> {
                log("$LOG_PREFIX failed (exit ${outcome.code})")
                failedMessage("code ${outcome.code}")
            }
            is ElevatedOutcome.NotStarted -> {
                log("$LOG_PREFIX didn't start (error ${outcome.error})")
                failedMessage("error ${outcome.error}")
            }
            ElevatedOutcome.NoProcess -> {
                log("$LOG_PREFIX didn't start (no process)")
                failedMessage("no process")
            }
            ElevatedOutcome.TimedOut -> {
                log("$LOG_PREFIX timed out")
                failedMessage("no answer in 2 minutes")
            }
            is ElevatedOutcome.WaitFailed -> {
                log("$LOG_PREFIX wait failed (error ${outcome.error})")
                failedMessage("wait failed, error ${outcome.error}")
            }
            is ElevatedOutcome.NoExitCode -> {
                log("$LOG_PREFIX no exit code (error ${outcome.error})")
                failedMessage("no exit code, error ${outcome.error}")
            }
        }
    }

    /** The exe to allow and the PowerShell to run, or null when either can't be trusted. */
    private fun target(): Pair<File, File>? {
        val local = folders.localAppData() ?: return null
        val exe = validatedExe(launcher(), local) ?: return null
        val powerShell = powerShell(folders.system() ?: return null) ?: return null
        return exe to powerShell
    }

    companion object {
        const val NOT_ALLOWED = "Not allowed. Nothing changed."
        const val STILL_BLOCKED = "Windows still blocks the folder. Restart Meal Planner and try again."
        const val ERROR_CANCELLED = 1223
        const val TIMEOUT_MILLIS = 120_000L
        const val INSTALL_DIR_NAME = "Meal-Planner"
        const val EXE_NAME = "Meal Planner.exe"
        private const val LOG_PREFIX = "Meal Planner: allowing the app through Controlled folder access:"

        /**
         * The only characters a path put into the command may hold (P7-R11a). No quote of any kind (Windows PowerShell
         * also ends a single-quoted string at U+2018 to U+201B), no `$`, backtick, `;` or control character.
         */
        private val SAFE_PATH = Regex("[A-Za-z0-9 \\\\:._()-]+")

        /** The characters Windows PowerShell reads as a single quote: ' and U+2018 to U+201B. */
        private val SINGLE_QUOTES = setOf('\'', '\u2018', '\u2019', '\u201A', '\u201B')

        fun failedMessage(detail: String): String =
            "Couldn't change the setting ($detail). You can allow Meal Planner yourself in Windows Security > " +
                "Virus & threat protection > Ransomware protection > Allow an app through Controlled folder access > " +
                "add Meal Planner."

        fun isSafePath(path: String): Boolean = SAFE_PATH.matches(path)

        /** The installed app's exe: <LocalAppData>\Meal-Planner\Meal Planner.exe (P7-R2b). */
        fun installedExe(localAppData: File): File = File(File(localAppData, INSTALL_DIR_NAME), EXE_NAME)

        /**
         * [path] as the exe to allow, or null. Its canonical path must equal the canonical installed exe under
         * [localAppData] (ignoring case, as Windows does), be an existing file, and hold only [isSafePath] characters.
         */
        fun validatedExe(path: String?, localAppData: File): File? {
            if (path.isNullOrBlank() || !isSafePath(path)) return null
            return try {
                val exe = File(path).canonicalFile
                val expected = installedExe(localAppData).canonicalFile
                exe.takeIf {
                    it.path.equals(expected.path, ignoreCase = true) && it.isFile && isSafePath(it.path)
                }
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }

        /** Windows PowerShell 5.1 under [system] (FOLDERID_System), when it is there. */
        fun powerShell(system: File): File? =
            File(system, "WindowsPowerShell\\v1.0\\powershell.exe").takeIf { system.isAbsolute && it.isFile }

        /** [text] with every single-quote-class character doubled, as a PowerShell single-quoted string reads it. */
        fun doubleQuotes(text: String): String = buildString {
            for (c in text) {
                append(c)
                if (c in SINGLE_QUOTES) append(c)
            }
        }

        /** The script run elevated: adding [exe] to the allowed apps. Nothing else goes in. */
        fun script(exe: File): String =
            "Add-MpPreference -ControlledFolderAccessAllowedApplications '${doubleQuotes(exe.path)}'"

        /** PowerShell's arguments: the [script] as -EncodedCommand (base64 of UTF-16LE), never -Command. */
        fun parameters(exe: File): String =
            "-NoProfile -NonInteractive -EncodedCommand " +
                Base64.getEncoder().encodeToString(script(exe).toByteArray(Charsets.UTF_16LE))
    }
}
