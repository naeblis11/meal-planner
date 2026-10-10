package com.naeblis11.mealplanner.update

import java.io.File
import java.security.MessageDigest

/** SHA-256 in lowercase hex, as latest.json lists each file's. */
object Sha256 {
    private const val BUFFER = 64 * 1024

    fun of(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Streams [file], so a 100 MB installer is never held in memory. */
    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
