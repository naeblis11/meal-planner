package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.folder.AtomicFiles
import com.naeblis11.mealplanner.folder.isLibraryBlocked
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files

/**
 * The app's writes to the library (`Documents\Meal Planner`) that Windows' Controlled folder access can refuse
 * (Defender event 1123), behind one seam so tests can act as the block does. Java's File.mkdirs would only say false,
 * so folders are made through NIO, which says why.
 *
 * P7-R10b: nothing is ever written only to look, because under the block every refused write is a Defender
 * notification. The library is judged blocked only when a write it really needs is refused: the first run's folders,
 * a recipe save ([writeFile]), a photo save, an import. [probeWrite] runs only when the user presses Check again.
 */
open class LibraryAccess {
    /**
     * Makes [dir] and any parent it needs; throws IOException when it can't. Under the block that is AccessDeniedException
     * or, as on the owner's PC (P7-R11b), NoSuchFileException naming the level it couldn't make, whose parent is there.
     */
    open fun createDirectories(dir: File) {
        Files.createDirectories(dir.toPath())
    }

    /** A recipe file's save: AtomicFiles.write. */
    open fun writeFile(target: File, bytes: ByteArray) {
        AtomicFiles.write(target, bytes)
    }

    /** Settings' Check again only: writes and deletes a small file in [dir]; throws IOException when it can't. */
    open fun probeWrite(dir: File) {
        val probe = Files.createTempFile(dir.toPath(), PROBE_PREFIX, ".tmp")
        Files.deleteIfExists(probe)
    }

    /**
     * A first run's folders (recipes and photos): on a true first run, and when the sync makes the recipe folder while
     * the index has no recipe files (P7-R11b, the recovery after the block). Each is tried, so one that fails (a file in
     * the way, M7) doesn't keep the other from being made; one already there is left alone (looked at, never written
     * to). A failure judged as the block (isLibraryBlocked) stops there: both share the refused parent, and each refused
     * write is one more Defender notification. Returns each failure with its folder, already logged; empty when both
     * are there.
     */
    fun makeFirstRunFolders(recipesDir: File, imagesDir: File): List<Pair<File, IOException>> {
        val failures = mutableListOf<Pair<File, IOException>>()
        for (dir in listOf(recipesDir, imagesDir)) {
            if (dir.isDirectory) continue
            try {
                createDirectories(dir)
            } catch (e: IOException) {
                System.err.println("Meal Planner: the library's ${dir.name} folder couldn't be made (${describe(e)}): ${dir.path}")
                failures += dir to e
                if (isLibraryBlocked(e, dir)) break
            }
        }
        return failures
    }

    /**
     * Check again: one write, making the library folder if it is gone, else a small file in it. Null when it worked;
     * else the failure with what was being made (the folder, or the small file in it), for isLibraryBlocked.
     */
    fun checkAgain(libraryDir: File): Pair<File, IOException>? {
        val probing = libraryDir.isDirectory
        val target = if (probing) File(libraryDir, PROBE_PREFIX + "tmp") else libraryDir
        return try {
            if (probing) probeWrite(libraryDir) else createDirectories(libraryDir)
            null
        } catch (e: IOException) {
            System.err.println("Meal Planner: the library still can't be written (${describe(e)}): ${libraryDir.path}")
            target to e
        }
    }

    private companion object {
        const val PROBE_PREFIX = ".meal-planner-write-check-"

        // The failure's kind and, for NIO's, the level it names (a library path, fine to log): which folder Windows refused.
        fun describe(e: IOException): String =
            (e as? FileSystemException)?.file?.let { "${e.javaClass.simpleName} at $it" } ?: e.javaClass.simpleName
    }
}
