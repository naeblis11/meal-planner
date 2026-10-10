package com.naeblis11.mealplanner.folder

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rename at the end of an atomic write, and the brief Windows locks it rides out. Nothing here sleeps for real. */
class AtomicFilesTest {
    private val dir: File = Files.createTempDirectory("mp-atomic").toFile()
    private val from: Path = File(dir, ".soup.yaml.1.tmp").toPath()
    private val to: Path = File(dir, "soup.yaml").toPath()
    private val sleeps = mutableListOf<Long>()
    private var attempts = 0

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // Windows' sharing violation as the JDK reports it: a plain FileSystemException, not AccessDeniedException.
    private fun inUse() = FileSystemException(from.toString(), to.toString(), "The process cannot access the file because it is being used by another process.")

    private fun denied() = AccessDeniedException(from.toString(), to.toString(), null)

    /** A mover that refuses the first [refusals] attempts with [refusal], then moves. */
    private fun refusing(refusals: Int, refusal: () -> FileSystemException): (Path, Path) -> Unit = { _, _ ->
        if (++attempts <= refusals) throw refusal()
    }

    private fun move(mover: (Path, Path) -> Unit) = AtomicFiles.move(from, to, mover, sleep = { sleeps += it })

    @Test
    fun aRenameRefusedAsAccessDeniedIsRetriedAndThenSucceeds() {
        move(refusing(2, ::denied))
        assertEquals(3, attempts)
        assertEquals(listOf(AtomicFiles.MOVE_RETRY_DELAY_MS, AtomicFiles.MOVE_RETRY_DELAY_MS), sleeps)
    }

    @Test
    fun aRenameRefusedBecauseTheFileIsInUseIsRetriedAndThenSucceeds() {
        // An editor's preview, Explorer or OneDrive holding the target open: a sharing violation, retried like a lock.
        move(refusing(2, ::inUse))
        assertEquals(3, attempts)
        assertEquals(listOf(AtomicFiles.MOVE_RETRY_DELAY_MS, AtomicFiles.MOVE_RETRY_DELAY_MS), sleeps)
    }

    @Test
    fun theInUseReasonIsMatchedWhateverItsLetterCase() {
        move(refusing(1) { FileSystemException(from.toString(), to.toString(), "Being USED BY ANOTHER PROCESS") })
        assertEquals(2, attempts)
    }

    @Test
    fun aRenameStillRefusedAfterTheRetriesIsTheFileBeingOpenOrReadOnly() {
        for (refusal in listOf(::denied, ::inUse)) {
            attempts = 0
            sleeps.clear()
            val last = refusal()
            val thrown = assertThrows(ExistingFileRefusedException::class.java) { move { _, _ -> attempts++; throw last } }
            assertSame(last, thrown.cause)
            assertEquals("the file may be open or read-only", thrown.message)
            assertEquals(AtomicFiles.MOVE_RETRIES + 1, attempts)
            assertEquals(AtomicFiles.MOVE_RETRIES, sleeps.size)
            assertTrue(sleeps.all { it == AtomicFiles.MOVE_RETRY_DELAY_MS })
        }
    }

    @Test
    fun anyOtherFailureIsThrownAtOnceWithoutARetry() {
        // Not a lock: an unplugged drive, say. Nothing to wait for, and never dressed up as a locked file.
        val failure = FileSystemException(from.toString(), to.toString(), "The device is not ready")
        val thrown = assertThrows(FileSystemException::class.java) { move { _, _ -> attempts++; throw failure } }
        assertSame(failure, thrown)
        assertEquals(1, attempts)
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun theRealMoveReplacesTheTargetInOneStep() {
        Files.write(from, "new".toByteArray())
        Files.write(to, "old".toByteArray())
        AtomicFiles.move(from, to, sleep = { sleeps += it })
        assertEquals("new", File(dir, "soup.yaml").readText())
        assertEquals(listOf("soup.yaml"), dir.list()!!.toList())
        assertEquals(emptyList<Long>(), sleeps)
    }
}
