package com.naeblis11.mealplanner.folder

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7-R10b, P7-R11b: the Controlled folder access sentence only for access denied, or "not found" where the folder above
 * is there; any other failure says why, with no path.
 */
class LibraryMessagesTest {
    @Test
    fun accessDeniedIsTheBlock() {
        assertTrue(isLibraryBlocked(AccessDeniedException("C:\\Users\\someone\\Documents\\Meal Planner\\recipes")))
        assertTrue(isLibraryBlocked(FileNotFoundException("C:\\Users\\someone\\Documents\\Meal Planner\\x.tmp (Access is denied)")))
        // The photo writer wraps the refusal: "Could not save <uuid>.jpg." with the refusal as its cause.
        assertTrue(isLibraryBlocked(IOException("Could not save soup.jpg.", FileNotFoundException("C:\\x\\soup.jpg.tmp (Access is denied)"))))
        assertFalse(isLibraryBlocked(IOException("Could not save soup.jpg.", IOException("There is not enough space on the disk"))))
        assertFalse(isLibraryBlocked(IOException("There is not enough space on the disk")))
        assertFalse(isLibraryBlocked(FileSystemException("C:\\x", null, "The device is not ready")))
    }

    @Test
    fun notFoundWhereTheFolderAboveIsThereIsTheBlock() {
        // P7-R11b: on the owner's PC Controlled folder access refused "Meal Planner" in an existing Documents folder,
        // and NIO said NoSuchFileException, naming the level it couldn't make.
        val root = Files.createTempDirectory("mp-blocked").toFile()
        try {
            val documents = File(root, "Documents").apply { mkdirs() }
            val library = File(documents, "Meal Planner")
            assertTrue(isLibraryBlocked(NoSuchFileException(library.path)))
            // java.io says it in words, after the path or (File.createTempFile) with no path: then the file being made.
            assertTrue(isLibraryBlocked(FileNotFoundException("${File(documents, "x.tmp").path} (The system cannot find the path specified)")))
            assertTrue(isLibraryBlocked(IOException("The system cannot find the path specified"), File(documents, "soup.yaml")))
            assertTrue(isLibraryBlocked(IOException("Could not save soup.jpg.", NoSuchFileException(File(documents, "soup.jpg.tmp").path))))
            // The folder above genuinely missing (an offline drive, a folder gone): not the block.
            assertFalse(isLibraryBlocked(NoSuchFileException(File(library, "recipes").path)))
            assertFalse(isLibraryBlocked(NoSuchFileException(File(library, "recipes").path), File(documents, "x")))
            assertFalse(isLibraryBlocked(IOException("The system cannot find the path specified"), File(library, "soup.yaml")))
            assertFalse(isLibraryBlocked(IOException("The system cannot find the path specified")))
            // Judged once: a failure already said otherwise stays so.
            assertFalse(isLibraryBlocked(LibrarySaveException(NoSuchFileException(File(library, "recipes").path))))
            // The look is a read: nothing was made.
            assertFalse(library.exists())
            assertEquals(listOf("Documents"), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun notFoundWithTheFolderAboveMissingSaysSoInWords() {
        // P7-R11b: NIO gives no reason; the message says what happened, still with no path.
        val missing = NoSuchFileException("C:\\Users\\someone\\gone\\Documents\\Meal Planner")
        assertEquals("Couldn't save to Documents\\Meal Planner: Windows couldn't find part of the path", librarySaveFailedMessage(missing))
        assertEquals("Couldn't save to Documents\\Meal Planner: Windows couldn't find part of the path", libraryNoticeFor(missing))
        assertEquals(
            "Couldn't save to Documents\\Meal Planner: Windows couldn't find part of the path",
            libraryFolderFailedMessage("recipes", missing),
        )
        assertEquals(LIBRARY_BLOCKED_MESSAGE, libraryNoticeFor(AccessDeniedException("C:\\x")))
    }

    @Test
    fun aRefusedReplaceOfAnExistingFileIsNoBlock() {
        // P7-R10b M3: Controlled folder access refuses new files; a refused rename over an existing one is a lock
        // (OneDrive, a virus scanner) or a read-only file, and says so.
        val replace = ExistingFileRefusedException(AccessDeniedException("C:\\x\\soup.yaml"))
        assertFalse(isLibraryBlocked(replace))
        assertFalse(isLibraryBlocked(IOException("Could not save soup.jpg.", replace)))
        val hint = "Couldn't save to Documents\\Meal Planner: the file may be open or read-only"
        assertEquals(hint, librarySaveFailedMessage(replace))
        assertEquals(hint, librarySaveFailedMessage(IOException("Could not save soup.jpg.", replace)))
    }

    @Test
    fun aFullDiskSaysSoWithoutAPath() {
        val nio = FileSystemException("C:\\Users\\someone\\Documents\\Meal Planner\\recipes\\soup.yaml", null, "There is not enough space on the disk")
        assertEquals("Couldn't save to Documents\\Meal Planner: There is not enough space on the disk", librarySaveFailedMessage(nio))
        val io = IOException("C:\\Users\\someone\\Documents\\Meal Planner\\recipes\\.soup.yaml.1.tmp (There is not enough space on the disk)")
        assertEquals("Couldn't save to Documents\\Meal Planner: There is not enough space on the disk", librarySaveFailedMessage(io))
        assertEquals("Couldn't save to Documents\\Meal Planner: There is not enough space on the disk", LibrarySaveException(io).message)
    }

    @Test
    fun aReasonThatNamesAPathGivesOnlyTheKindOfFailure() {
        val leaky = IOException("Couldn't write C:\\Users\\someone\\secret\\soup.yaml")
        assertEquals("Couldn't save to Documents\\Meal Planner: IOException", librarySaveFailedMessage(leaky))
        val noReason = FileSystemException("C:\\Users\\someone\\x")
        assertEquals("Couldn't save to Documents\\Meal Planner: FileSystemException", librarySaveFailedMessage(noReason))
        assertEquals("Couldn't save to Documents\\Meal Planner: The device is not ready", librarySaveFailedMessage(IOException("The device is not ready")))
    }
}
