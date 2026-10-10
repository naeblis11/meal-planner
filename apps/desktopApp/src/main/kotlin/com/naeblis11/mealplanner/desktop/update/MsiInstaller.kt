package com.naeblis11.mealplanner.desktop.update

import com.naeblis11.mealplanner.desktop.KnownWindowsFolders
import com.naeblis11.mealplanner.desktop.ProcessStarter
import com.naeblis11.mealplanner.desktop.SystemProcessStarter
import com.naeblis11.mealplanner.desktop.WindowsFolders
import com.naeblis11.mealplanner.desktop.windowsExplorer
import com.naeblis11.mealplanner.update.ReleaseManifest
import com.naeblis11.mealplanner.update.UpdateInstaller
import com.naeblis11.mealplanner.update.Updates
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The PC's installer (spec "Updates", P8-PF2). The packaged launcher keeps the app in a job object, so an installer
 * started straight from here would end when the app quits. The verified MSI is handed instead to Windows' own
 * explorer.exe (windowsExplorer: FOLDERID_System's parent, never the environment), which opens it with msiexec outside
 * the job, per-user and without admin rights, the same hand-off as Restart now (Relauncher). Then [quit] runs: main's
 * UpdateQuit, the bounded shutdown (Settings asked first), so the upgrade can replace the program's files. [canQuit]
 * says whether that quit is there yet and nothing unsaved holds the LeaveGuard (P8-R6a, P8-F1): until then,
 * canStartNow is false and nothing is handed over, so the installer never starts beside an app that can't get out of
 * its way, and an edit begun during the download is never discarded.
 *
 * Only a file Updates made is ever handed over: a plain file (not a link or a folder) directly in [updatesDir] (named
 * exactly `updates`, and itself neither a link nor a junction), named `update-<the signed list's file name>.msi`, whose
 * absolute path passes [isHandOffSafe]. Anything else throws before anything starts, and the app stays open; so does a
 * start that fails. It is one argument of a ProcessBuilder list, never a shell's command line. [starter] is the seam:
 * tests pass a fake, so no test starts explorer or an installer.
 */
class MsiInstaller(
    val updatesDir: File,
    private val quit: () -> Unit,
    private val canQuit: () -> Boolean = { true },
    private val folders: WindowsFolders = KnownWindowsFolders,
    private val starter: ProcessStarter = SystemProcessStarter,
) : UpdateInstaller {
    override val closesApp: Boolean = true

    override fun canInstall(): Boolean = true

    // The PC has no foreground rule (Android's P8-PF11), but the app must be able to quit once the installer starts.
    override fun canStartNow(): Boolean = canQuit()

    override fun openInstallPermission() = Unit

    override fun install(file: File) {
        val command = command(file)
        if (!canQuit()) throw IOException("Meal Planner can't close for the installer yet.")
        starter.start(command)
        quit()
    }

    /** explorer.exe and the MSI, or an IOException when either can't be trusted. Starts nothing. */
    fun command(file: File): List<String> {
        val msi = checkedMsi(file)
        val explorer = windowsExplorer(folders) ?: throw IOException("Windows' explorer.exe can't be found.")
        return listOf(explorer.path, msi.path)
    }

    private fun checkedMsi(file: File): File {
        val dir = updatesDir.toPath().toAbsolutePath().normalize()
        val path = file.toPath().toAbsolutePath().normalize()
        if (dir.fileName?.toString() != Updates.DIR_NAME) throw IOException("The updates folder isn't named ${Updates.DIR_NAME}.")
        if (path.parent != dir) throw IOException("The installer isn't in the updates folder.")
        val name = path.fileName?.toString().orEmpty()
        if (!NAME.matches(name) || !name.endsWith(EXTENSION)) throw IOException("That isn't an update Meal Planner downloaded.")
        if (!isHandOffSafe(path.toString())) throw IOException("The installer's path has characters Windows can't pass on.")
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("There is no installer to start.")
        // The updates folder must be itself, not a link or junction to somewhere else, and the file must really be in it.
        val real = try {
            val parent = dir.parent ?: throw IOException("The updates folder has no parent.")
            !Files.isSymbolicLink(dir) &&
                Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) &&
                dir.toRealPath() == parent.toRealPath().resolve(dir.fileName) &&
                path.toRealPath() == dir.toRealPath().resolve(name)
        } catch (e: IOException) {
            false
        }
        if (!real) throw IOException("The updates folder isn't a plain folder.")
        return path.toFile()
    }

    companion object {
        const val EXTENSION = ".msi"

        /** Updates' prefix, then a file name the signed list allows (ReleaseManifest.FILE_PATTERN): one path segment. */
        private val NAME = Regex(Regex.escape(Updates.FILE_PREFIX) + ReleaseManifest.FILE_PATTERN)

        /**
         * P8-R6a: what explorer.exe can take as one argument: a drive-letter path (no UNC, no leading slash) with no
         * double quote, comma, forward slash or control character. Anything else (an apostrophe, an accent, an
         * ampersand) is fine, as the path is one ProcessBuilder argument. AllowApp keeps its own, stricter rule for
         * PowerShell.
         */
        private val HAND_OFF_SAFE = Regex("[A-Za-z]:\\\\[^\",\\x00-\\x1F/]*")

        fun isHandOffSafe(path: String): Boolean = HAND_OFF_SAFE.matches(path)
    }
}
