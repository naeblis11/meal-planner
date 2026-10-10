package com.naeblis11.mealplanner.desktop.google

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.Win32Exception
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Seals and unseals small secrets; DPAPI in the app, FakeProtector in tests. */
interface SecretProtector {
    fun protect(plain: ByteArray): ByteArray

    /** Throws when [sealed] wasn't sealed here (another Windows user, another PC). */
    fun unprotect(sealed: ByteArray): ByteArray
}

/**
 * Windows DPAPI for the signed-in Windows user (P5-R4): only that user, on this PC, can unseal what it sealed. A
 * refusal from Windows (JNA's Win32Exception) is an IOException, its cause kept for its error code.
 */
class DpapiProtector : SecretProtector {
    override fun protect(plain: ByteArray): ByteArray = try {
        Crypt32Util.cryptProtectData(plain)
    } catch (e: Win32Exception) {
        throw IOException("Windows couldn't seal the secret.", e)
    }

    override fun unprotect(sealed: ByteArray): ByteArray = try {
        Crypt32Util.cryptUnprotectData(sealed)
    } catch (e: Win32Exception) {
        throw IOException("Windows couldn't unseal the secret.", e)
    }
}

/**
 * The Google refresh token, sealed by [protector] in [file] (google-token.dat beside the secrets file), never in plain
 * text. Written to a temporary file in the same folder and moved over the old one, so a crash leaves one or the other.
 * Neither this class's messages nor its toString ever contain the token. When the protector fails, the message is a
 * fixed one and [log] gets only the failure's kind (exception classes, and Windows' error code), never a message: that
 * tells a corrupt file from one sealed by another Windows user without risking the secret in the log.
 */
class GoogleTokenStore(
    val file: File,
    private val protector: SecretProtector,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    fun exists(): Boolean = file.isFile

    /** The refresh token, or null when none is kept. Throws IOException when the file can't be read or unsealed. */
    fun load(): String? {
        if (!file.isFile) return null
        val sealed = try {
            file.readBytes()
        } catch (e: IOException) {
            log("Meal Planner: the saved Google sign-in can't be read (${kind(e)}).")
            throw IOException(CANT_READ)
        }
        val plain = try {
            protector.unprotect(sealed)
        } catch (e: Exception) {
            log("Meal Planner: the saved Google sign-in can't be unsealed (${kind(e)}).")
            throw IOException(CANT_UNSEAL)
        }
        return String(plain, Charsets.UTF_8).ifEmpty { null }
    }

    /** Seals and keeps [refreshToken]; IOException(CANT_SEAL) when the protector fails, and nothing is written. */
    fun save(refreshToken: String) {
        val sealed = try {
            protector.protect(refreshToken.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            log("Meal Planner: the Google sign-in can't be sealed (${kind(e)}).")
            throw IOException(CANT_SEAL)
        }
        val folder = file.absoluteFile.parentFile
        folder.mkdirs()
        val temp = File(folder, "${file.name}.tmp")
        try {
            temp.writeBytes(sealed)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    /** Forgets the token; true when there is no file any more. */
    fun delete(): Boolean = !file.exists() || file.delete()

    override fun toString(): String = "GoogleTokenStore(${file.name}, saved=${file.isFile})"

    companion object {
        const val FILE_NAME = "google-token.dat"

        const val CANT_READ = "The saved Google sign-in can't be read. Sign in again."
        const val CANT_UNSEAL = "The saved Google sign-in can't be read on this PC or by this Windows user."
        const val CANT_SEAL = "Windows couldn't keep the Google sign-in safe, so it wasn't saved. Try again."

        // The chain of exception classes, with Windows' error code where there is one; never a message.
        private fun kind(e: Throwable): String = generateSequence(e) { it.cause }.take(4).joinToString(" <- ") { t ->
            if (t is Win32Exception) "${t.javaClass.name} code=${t.errorCode}" else t.javaClass.name
        }
    }
}
