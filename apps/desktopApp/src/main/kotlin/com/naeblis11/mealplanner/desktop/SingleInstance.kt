package com.naeblis11.mealplanner.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One app per data folder (P3-R2). The first launch holds an exclusive lock on `<cacheDir>/instance.lock` and listens
 * on 127.0.0.1 at a port the system picks; the file says which port, with a random token. A later launch can't take
 * the lock, so it sends "show <token>" to that port and ends, and the first one's onShow brings its window forward.
 * The lock is per folder, so the preview, the tests and the installed app never meet.
 */
class SingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val token: String,
    private val onShow: () -> Unit,
) : AutoCloseable {
    /** The loopback port this instance listens on; the lock file says the same. */
    val port: Int = server.localPort

    init {
        Thread({ serve() }, "single-instance").apply { isDaemon = true }.start()
    }

    // One caller at a time. Each sends one line; only "show <token>" does anything.
    private fun serve() {
        val expected = "$SHOW $token"
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return // closed
            }
            val asked = try {
                socket.use { matches(firstLine(it), expected) }
            } catch (e: Exception) {
                false // a caller that went away mid-line or took too long: the next caller is still served
            }
            if (asked) {
                try {
                    onShow()
                } catch (e: Exception) {
                    System.err.println("Meal Planner: bringing the window forward failed: $e")
                }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        const val LOCK_FILE = "instance.lock"
        private const val SHOW = "show"

        // Locked far past the text: Windows file locks also block reads, and a second launch must read the port.
        internal const val LOCK_POSITION = 1L shl 30
        private const val CONNECT_TIMEOUT_MILLIS = 2_000
        private const val CALLER_DEADLINE_MILLIS = 3_000L
        private const val MAX_LINE = 128
        private const val GIVE_UP_MILLIS = 10_000L
        private const val RETRY_MILLIS = 100L
        private const val BACKLOG = 50

        /**
         * Becomes the one instance for [cacheDir] and returns it; or, when another already is, asks it to show its
         * window and returns null, and the caller ends. [onShow] runs on a background thread.
         *
         * The running instance may still be writing its port, or may be ending: for about ten seconds this keeps
         * trying to reach it, and takes the folder itself if the lock comes free.
         */
        fun acquire(cacheDir: File, onShow: () -> Unit): SingleInstance? {
            cacheDir.mkdirs()
            val file = File(cacheDir, LOCK_FILE)
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            try {
                val deadline = System.nanoTime() + GIVE_UP_MILLIS * 1_000_000
                while (true) {
                    val lock = tryLock(channel)
                    if (lock != null) return start(channel, lock, onShow)
                    if (askToShow(file)) {
                        channel.close()
                        return null
                    }
                    if (System.nanoTime() - deadline >= 0) {
                        System.err.println("Meal Planner: another copy holds ${file.path} but didn't answer; ending.")
                        channel.close()
                        return null
                    }
                    Thread.sleep(RETRY_MILLIS)
                }
            } catch (e: Exception) {
                runCatching { channel.close() }
                throw e
            }
        }

        private fun tryLock(channel: FileChannel): FileLock? = try {
            channel.tryLock(LOCK_POSITION, 1, false)
        } catch (e: OverlappingFileLockException) {
            null // another SingleInstance in this JVM holds it (tests do this)
        }

        // The lock is ours: clear the last holder's port before anything else, then listen and say where.
        private fun start(channel: FileChannel, lock: FileLock, onShow: () -> Unit): SingleInstance {
            var server: ServerSocket? = null
            try {
                channel.truncate(0)
                server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
                val token = newToken()
                channel.write(ByteBuffer.wrap("${server.localPort} $token\n".toByteArray(Charsets.UTF_8)), 0)
                channel.force(true)
                return SingleInstance(channel, lock, server, token, onShow)
            } catch (e: Exception) {
                runCatching { server?.close() }
                runCatching { lock.release() }
                throw e
            }
        }

        // One try at sending "show <token>" to the port the lock file names; false when there's no port yet or no answer.
        private fun askToShow(file: File): Boolean {
            val parts = runCatching { file.readText(Charsets.UTF_8).trim().split(' ') }.getOrNull()
            val port = parts?.getOrNull(0)?.toIntOrNull() ?: return false
            val token = parts.getOrNull(1) ?: return false
            return runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS)
                    socket.getOutputStream().apply {
                        write("$SHOW $token\n".toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
            }.isSuccess
        }

        // At most MAX_LINE bytes up to the first newline, within CALLER_DEADLINE_MILLIS in all, so a stray or slow
        // caller can't hold the listener.
        private fun firstLine(socket: Socket): String {
            val deadline = System.nanoTime() + CALLER_DEADLINE_MILLIS * 1_000_000
            val input = socket.getInputStream()
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < MAX_LINE) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) throw SocketTimeoutException("caller took too long")
                socket.soTimeout = left.toInt()
                val b = input.read()
                if (b == -1 || b == '\n'.code) break
                bytes.write(b)
            }
            return bytes.toString(Charsets.UTF_8).trimEnd('\r')
        }

        // Constant time, so the token can't be guessed a character at a time.
        private fun matches(line: String, expected: String): Boolean =
            MessageDigest.isEqual(line.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

        private fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
