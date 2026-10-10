package com.naeblis11.mealplanner.folder

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FolderFileStoreTest {
    private val dir: File = Files.createTempDirectory("mp-store").toFile()
    private val trashed = mutableListOf<String>()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun writeReplacesTheFileAtomicallyAndReturnsItsHash() {
        val store = FolderFileStore(dir, moveToTrash = { false })
        store.write("soup.yaml", "recipe_name: Old\n")
        val hash = store.write("soup.yaml", "recipe_name: Soup\n")
        assertEquals("recipe_name: Soup\n", File(dir, "soup.yaml").readText())
        assertEquals(sha256("recipe_name: Soup\n".toByteArray()), hash)
        assertEquals(listOf("soup.yaml"), dir.list()!!.toList()) // no temp file left behind
    }

    @Test
    fun aMissingFolderIsNeverMadeByAWriteOrARestore() {
        // A recreated, empty recipe folder would unindex every other recipe at the next sync.
        val gone = File(dir, "recipes")
        val store = FolderFileStore(gone, moveToTrash = { false })
        val write = assertThrows(IOException::class.java) { store.write("soup.yaml", "recipe_name: Soup\n") }
        assertEquals("The recipe folder is missing: ${gone.path}", write.message)
        val restore = assertThrows(IOException::class.java) { store.restore("soup.yaml", "recipe_name: Soup\n".toByteArray()) }
        assertEquals("The recipe folder is missing: ${gone.path}", restore.message)
        assertThrows(IOException::class.java) { store.restore("soup.yaml", null) }
        assertThrows(IOException::class.java) { AtomicFiles.write(File(gone, "soup.yaml"), "x".toByteArray()) }
        assertFalse(gone.exists())
    }

    @Test
    fun aWriteWindowsRefusesSaysHowToAllowTheApp() {
        // Controlled folder access refuses the temp file: NIO says AccessDeniedException, java.io "Access is denied".
        for (refusal in listOf(java.nio.file.AccessDeniedException("x"), IOException("Access is denied"))) {
            var told = 0
            val store = FolderFileStore(dir, { false }, writer = { _, _ -> throw refusal }, onBlocked = { told++ })
            val write = assertThrows(LibraryBlockedException::class.java) { store.write("soup.yaml", "recipe_name: Soup\n") }
            assertEquals(LIBRARY_BLOCKED_MESSAGE, write.message)
            val restore = assertThrows(LibraryBlockedException::class.java) { store.restore("soup.yaml", "x".toByteArray()) }
            assertEquals(LIBRARY_BLOCKED_MESSAGE, restore.message)
            assertEquals(2, told)
        }
    }

    @Test
    fun aSaveRefusedAsNotFoundInTheRecipeFolderIsTheBlock() {
        // P7-R11b: the recipe folder is there, so a new file "not found" in it is Controlled folder access: NIO names the
        // temp file, java.io's File.createTempFile names nothing (then the recipe file being saved is what is judged).
        val refusals = listOf(
            java.nio.file.NoSuchFileException(File(dir, ".soup.yaml.1.tmp").path),
            IOException("The system cannot find the path specified"),
        )
        for (refusal in refusals) {
            var told = 0
            val store = FolderFileStore(dir, { false }, writer = { _, _ -> throw refusal }, onBlocked = { told++ })
            val write = assertThrows(LibraryBlockedException::class.java) { store.write("soup.yaml", "recipe_name: Soup\n") }
            assertEquals(LIBRARY_BLOCKED_MESSAGE, write.message)
            assertEquals(1, told)
        }
        // Not found under a folder that isn't there is the generic failure, said in words.
        val gone = java.nio.file.NoSuchFileException(File(File(dir, "gone"), "x.tmp").path)
        val store = FolderFileStore(dir, { false }, writer = { _, _ -> throw gone }, onBlocked = { error("not a block") })
        val write = assertThrows(LibrarySaveException::class.java) { store.write("soup.yaml", "recipe_name: Soup\n") }
        assertEquals("Couldn't save to Documents\\Meal Planner: Windows couldn't find part of the path", write.message)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun anyOtherWriteFailureSaysWhyWithoutAPathAndIsNoBlock() {
        // P7-R10b: a full disk is not Controlled folder access; it says so, naming no path but the folder's.
        val full = IOException("C:\\Users\\someone\\Documents\\Meal Planner\\recipes\\.soup.yaml.1.tmp (There is not enough space on the disk)")
        val store = FolderFileStore(dir, { false }, writer = { _, _ -> throw full }, onBlocked = { error("not a block") })
        val write = assertThrows(LibrarySaveException::class.java) { store.write("soup.yaml", "recipe_name: Soup\n") }
        assertEquals("Couldn't save to Documents\\Meal Planner: There is not enough space on the disk", write.message)
        assertEquals(full, write.cause)
    }

    @Test
    fun aReadOnlyRecipeFileIsNoBlockAndSaysWhy() {
        // P7-R10b M3: Windows refuses a replace of a read-only file as access denied, as it does a locked one; only
        // a new file refused is Controlled folder access.
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val store = FolderFileStore(dir, moveToTrash = { false }, onBlocked = { error("not a block") })
        store.write("soup.yaml", "recipe_name: Old\n")
        val target = File(dir, "soup.yaml")
        assertTrue(target.setReadOnly())
        try {
            val write = assertThrows(LibrarySaveException::class.java) { store.write("soup.yaml", "recipe_name: Soup\n") }
            assertEquals("Couldn't save to Documents\\Meal Planner: the file may be open or read-only", write.message)
            assertEquals("recipe_name: Old\n", target.readText())
            assertEquals(listOf("soup.yaml"), dir.list()!!.toList()) // no temp file left behind
        } finally {
            target.setWritable(true)
        }
    }

    @Test
    fun theHashIsSha256Hex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AtomicFiles.sha256("abc".toByteArray()))
    }

    @Test
    fun trashUsesTheRecycleBinWhenThereIsOne() {
        val store = FolderFileStore(dir, moveToTrash = { file -> trashed += file.name; file.delete() })
        store.write("soup.yaml", "recipe_name: Soup\n")
        store.trash("soup.yaml")
        assertEquals(listOf("soup.yaml"), trashed)
        assertFalse(File(dir, "soup.yaml").exists())
        store.trash("soup.yaml") // already gone: nothing happens
        assertEquals(listOf("soup.yaml"), trashed)
    }

    @Test
    fun trashDeletesWhenThereIsNoRecycleBin() {
        val store = FolderFileStore(dir, moveToTrash = null)
        store.write("soup.yaml", "recipe_name: Soup\n")
        store.trash("soup.yaml")
        assertFalse(File(dir, "soup.yaml").exists())
    }

    @Test
    fun aRecycleBinThatFailsNeverFallsBackToAPermanentDelete() {
        for (failing in listOf<(File) -> Boolean>({ false }, { throw IllegalStateException("busy") })) {
            val store = FolderFileStore(dir, failing)
            store.write("soup.yaml", "recipe_name: Soup\n")
            val error = assertThrows(IOException::class.java) { store.trash("soup.yaml") }
            assertEquals("Couldn't move soup.yaml to the Recycle Bin.", error.message)
            assertEquals("recipe_name: Soup\n", File(dir, "soup.yaml").readText())
        }
    }

    @Test
    fun restorePutsBackWhatReadFound() {
        val store = FolderFileStore(dir, moveToTrash = null)
        store.write("soup.yaml", "recipe_name: Soup\n")
        val before = store.read("soup.yaml")
        assertNull(store.read("cake.yaml"))
        store.write("soup.yaml", "recipe_name: Changed\n")
        store.write("cake.yaml", "recipe_name: Cake\n")
        store.restore("soup.yaml", before)
        store.restore("cake.yaml", null)
        assertEquals("recipe_name: Soup\n", File(dir, "soup.yaml").readText())
        assertEquals(listOf("soup.yaml"), dir.list()!!.toList())
    }

    @Test
    fun aMoveThatFailsLeavesNoTempFileAndTheTargetUntouched() {
        // A non-empty folder where the file should go: the rename can never replace it.
        val target = File(dir, "soup.yaml").apply { mkdirs() }
        File(target, "keep.txt").writeText("keep\n")
        assertThrows(IOException::class.java) { AtomicFiles.write(target, "recipe_name: Soup\n".toByteArray()) }
        assertEquals(listOf("soup.yaml"), dir.list()!!.toList())
        assertEquals("keep\n", File(target, "keep.txt").readText())
    }

    @Test
    fun aNameOutsideTheFolderIsRefused() {
        val store = FolderFileStore(dir, moveToTrash = { false })
        for (bad in listOf("../outside.yaml", "sub\\soup.yaml", "C:soup.yaml", "..", "")) {
            assertThrows(bad, IllegalArgumentException::class.java) { store.write(bad, "x") }
        }
    }
}
