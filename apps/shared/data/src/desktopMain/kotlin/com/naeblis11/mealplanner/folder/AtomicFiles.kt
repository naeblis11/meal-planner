package com.naeblis11.mealplanner.folder

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Temp-file-then-rename writes, and the content hash the folder sync compares. */
object AtomicFiles {
    /** How many more times a refused rename is tried, and how long to wait before each. */
    private const val MOVE_RETRIES = 3
    private const val MOVE_RETRY_DELAY_MS = 100L

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

    // Windows refuses a rename while an antivirus scanner or the search indexer holds the target open;
    // that lock is brief, so a refused rename is retried a few times before giving up.
    private fun move(from: Path, to: Path) {
        var retries = 0
        while (true) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (e: AccessDeniedException) {
                // Still refused: a lock that stays, or a read-only target. Not Controlled folder access, which refuses
                // the new temp file before this (P7-R10b M3).
                if (retries == MOVE_RETRIES) throw ExistingFileRefusedException(e)
                retries++
                Thread.sleep(MOVE_RETRY_DELAY_MS)
            }
        }
    }

    /** SHA-256 of [bytes], as lowercase hex. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
