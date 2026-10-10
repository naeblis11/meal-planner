package com.naeblis11.mealplanner.folder

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Temp-file-then-rename writes, and the content hash the folder sync compares. */
object AtomicFiles {
    /** How many more times a refused rename is tried, and how long to wait before each. */
    internal const val MOVE_RETRIES = 3
    internal const val MOVE_RETRY_DELAY_MS = 100L

    /**
     * Writes [bytes] to [target] so that a reader (an editor, the watcher, a crash) only ever sees the
     * old file or the new one. The bytes go to a temp file in the same folder, are flushed to disk, and
     * the temp file is then moved over the target in one step. Its name ends in ".tmp", so the folder
     * sync ignores it. On failure the temp file is removed and the target is left as it was.
     * The folder must exist: it is never made here, because a recipe folder made again after it went
     * away would be empty, and the next sync would unindex every other recipe.
     */
    fun write(target: File, bytes: ByteArray) {
        val dir = target.absoluteFile.parentFile
        val temp = File.createTempFile(".${target.name}.", ".tmp", dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            move(temp.toPath(), target.toPath())
        } finally {
            temp.delete()
        }
    }

    /**
     * Windows refuses a rename while an antivirus scanner, the search indexer, an editor's preview or OneDrive holds
     * the target open; that lock is usually brief, so a refused rename is retried [MOVE_RETRIES] times, [MOVE_RETRY_DELAY_MS]
     * apart, before giving up. A refusal is NIO's AccessDeniedException or a FileSystemException whose reason is
     * Windows' sharing violation (ERROR_SHARING_VIOLATION, "being used by another process"); any other failure is
     * thrown at once. Still refused after the retries: ExistingFileRefusedException (a lock that stays, or a read-only
     * target; not Controlled folder access, which refuses the new temp file before this, P7-R10b M3).
     * [mover] and [sleep] are seams for tests, which never wait for real.
     */
    internal fun move(
        from: Path,
        to: Path,
        mover: (Path, Path) -> Unit = ::atomicMove,
        sleep: (Long) -> Unit = Thread::sleep,
    ) {
        var retries = 0
        while (true) {
            try {
                mover(from, to)
                return
            } catch (e: FileSystemException) {
                if (!isRefusal(e)) throw e
                if (retries == MOVE_RETRIES) throw ExistingFileRefusedException(e)
                retries++
                sleep(MOVE_RETRY_DELAY_MS)
            }
        }
    }

    private fun atomicMove(from: Path, to: Path) {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    // A rename Windows refused over a file that is there: access denied, or the sharing violation's words.
    private fun isRefusal(e: FileSystemException): Boolean =
        e is AccessDeniedException || e.reason?.contains(IN_USE, ignoreCase = true) == true

    /** The JDK's text for ERROR_SHARING_VIOLATION: "The process cannot access the file because it is being used by another process." */
    private const val IN_USE = "used by another process"

    /** SHA-256 of [bytes], as lowercase hex. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
