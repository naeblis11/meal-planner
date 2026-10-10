package com.naeblis11.mealplanner.folder

import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files

/**
 * The recipe folder as RecipeRepository writes it: whole-file atomic replaces, and deletes that go to
 * the Recycle Bin, so deleting a recipe in the app can usually be undone from Explorer.
 *
 * [moveToTrash] moves a file to the Recycle Bin and returns false when it couldn't. It is null where
 * the JVM has no Recycle Bin to offer (headless, or not supported), and only then is a file deleted for
 * good. When the Recycle Bin is there but fails, trash throws and the file stays. Windows decides what
 * "Recycle Bin" means per drive: on one without a bin (a network share, some USB drives) Windows itself
 * may delete the file for good, so undo is not promised there.
 *
 * A write Windows refuses (Controlled folder access, P7-R10; access denied or, P7-R11b, "not found" in a folder that is
 * there) throws LibraryBlockedException, whose message says how to
 * allow the app, and tells [onBlocked], so the window's notice shows. Any other failed write throws
 * LibrarySaveException ("Couldn't save to Documents\Meal Planner: <reason>", P7-R10b). [writer] is AtomicFiles.write;
 * tests refuse it.
 */
class FolderFileStore(
    private val dir: File,
    private val moveToTrash: ((File) -> Boolean)? = recycleBin(),
    private val writer: (File, ByteArray) -> Unit = AtomicFiles::write,
    private val onBlocked: () -> Unit = {},
) : RecipeFileStore {
    override fun exists(fileName: String): Boolean = file(fileName).exists()

    override fun read(fileName: String): ByteArray? = file(fileName).takeIf { it.isFile }?.readBytes()

    override fun write(fileName: String, text: String): String {
        val target = file(fileName)
        requireFolder()
        val bytes = text.toByteArray(Charsets.UTF_8)
        refusedSaysWhy(target) { writer(target, bytes) }
        return AtomicFiles.sha256(bytes)
    }

    override fun restore(fileName: String, bytes: ByteArray?) {
        val target = file(fileName)
        requireFolder()
        refusedSaysWhy(target) { if (bytes == null) deleteExisting(target) else writer(target, bytes) }
    }

    // A delete refused is a lock or a read-only file, never Controlled folder access (P7-R10b M3).
    private fun deleteExisting(target: File) {
        try {
            Files.deleteIfExists(target.toPath())
        } catch (e: AccessDeniedException) {
            throw ExistingFileRefusedException(e)
        }
    }

    private inline fun refusedSaysWhy(target: File, block: () -> Unit) {
        try {
            block()
        } catch (e: LibraryWriteException) {
            throw e
        } catch (e: IOException) {
            // P7-R10b, P7-R11b: only a refusal is Controlled folder access (isLibraryBlocked, judged against the file
            // being made, whose folder is there); anything else says why, with no path.
            val failure = libraryWriteFailure(e, target)
            if (failure is LibraryBlockedException) onBlocked()
            throw failure
        }
    }

    override fun trash(fileName: String) {
        val target = file(fileName)
        if (!target.exists()) return
        val move = moveToTrash
        if (move == null) {
            Files.deleteIfExists(target.toPath())
            return
        }
        val moved = try {
            move(target)
        } catch (e: Exception) {
            throw IOException("Couldn't move $fileName to the Recycle Bin.", e)
        }
        if (!moved) throw IOException("Couldn't move $fileName to the Recycle Bin.")
    }

    // A save never makes the folder: one that went away (deleted, or an offline Documents folder) and came
    // back empty would make the next sync unindex every other recipe and its planned meals.
    private fun requireFolder() {
        if (!dir.isDirectory) throw IOException("The recipe folder is missing: ${dir.path}")
    }

    // Only a bare name inside the folder: never a path out of it, a drive or an NTFS stream.
    private fun file(fileName: String): File {
        require(fileName.isNotBlank() && fileName != "." && fileName != ".." && fileName.none { it in "/\\:" }) {
            "Not a recipe file name: $fileName"
        }
        return File(dir, fileName)
    }
}

/** java.awt.Desktop's Recycle Bin, or null where there is none (headless, or not supported). */
fun recycleBin(): ((File) -> Boolean)? =
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
        ::moveToRecycleBin
    } else {
        null
    }

/** Moves [file] to the Recycle Bin through java.awt.Desktop; false when it couldn't. Check [recycleBin] first. */
fun moveToRecycleBin(file: File): Boolean = Desktop.getDesktop().moveToTrash(file)
