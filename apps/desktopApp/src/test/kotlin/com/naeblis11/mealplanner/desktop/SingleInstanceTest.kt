package com.naeblis11.mealplanner.desktop

import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-R2: one app per data folder; a second launch brings the first forward and ends. Loopback, ephemeral ports only. */
class SingleInstanceTest {
    private val cache: File = Files.createTempDirectory("mp-instance").toFile()
    private val opened = mutableListOf<SingleInstance>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        cache.deleteRecursively()
    }

    private fun acquire(dir: File = cache, onShow: () -> Unit = {}): SingleInstance? =
        SingleInstance.acquire(dir, onShow)?.also { opened += it }

    private fun tokenIn(dir: File): String = File(dir, SingleInstance.LOCK_FILE).readText().trim().split(' ')[1]

    private fun send(port: Int, bytes: ByteArray) {
        Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write(bytes) }
    }

    // The listener takes one caller at a time, so once this caller has been answered every earlier one has been handled.
    private fun waitForListener(port: Int, timeoutMillis: Int = 5_000) {
        Socket(InetAddress.getLoopbackAddress(), port).use {
            it.soTimeout = timeoutMillis
            it.getOutputStream().write("\n".toByteArray())
            val input = it.getInputStream()
            while (input.read() != -1) Unit
        }
    }

    @Test
    fun theFirstLaunchHoldsTheFolderAndSaysWhereItListens() {
        val first = acquire()!!
        val (port, token) = File(cache, SingleInstance.LOCK_FILE).readText().trim().split(' ')
        assertEquals(first.port, port.toInt())
        assertEquals(32, token.length)
    }

    @Test
    fun aSecondLaunchAsksTheFirstToShowAndBowsOut() {
        val shown = CountDownLatch(1)
        acquire { shown.countDown() }!!
        assertNull(acquire())
        assertTrue(shown.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun aWrongTokenIsIgnored() {
        val shows = AtomicInteger()
        val first = acquire { shows.incrementAndGet() }!!
        send(first.port, "show not-the-token\n".toByteArray())
        waitForListener(first.port)
        assertEquals(0, shows.get())

        send(first.port, "show ${tokenIn(cache)}\n".toByteArray())
        waitForListener(first.port)
        assertEquals(1, shows.get())
    }

    @Test
    fun garbageAndOverlongCallersAreIgnoredAndTheNextRealOneIsServed() {
        val shows = AtomicInteger()
        val first = acquire { shows.incrementAndGet() }!!
        val token = tokenIn(cache)
        send(first.port, byteArrayOf(0, -1, 7, 13, 10, -128, 65))
        send(first.port, ("x".repeat(300) + "\nshow $token\n").toByteArray())
        send(first.port, ("show $token" + " ".repeat(200) + "\n").toByteArray())
        Socket(InetAddress.getLoopbackAddress(), first.port).close() // says nothing at all
        waitForListener(first.port)
        assertEquals(0, shows.get())

        send(first.port, "show $token\n".toByteArray())
        waitForListener(first.port)
        assertEquals(1, shows.get())
    }

    @Test(timeout = 20_000)
    fun aSlowCallerCannotHoldTheListener() {
        val shows = AtomicInteger()
        val first = acquire { shows.incrementAndGet() }!!
        val token = tokenIn(cache)
        // Connected first, so it is served first; one byte every 200 ms would keep a per-read timeout happy for 30 s,
        // well past this test's own timeout: only the listener's deadline for one caller (3 s) lets the real one
        // through in time. The drip ends when the socket is closed below.
        val slow = Socket(InetAddress.getLoopbackAddress(), first.port)
        val drip = Thread {
            runCatching {
                repeat(150) {
                    slow.getOutputStream().apply {
                        write('x'.code)
                        flush()
                    }
                    Thread.sleep(200)
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            send(first.port, "show $token\n".toByteArray())
            waitForListener(first.port, timeoutMillis = 15_000)
            assertEquals(1, shows.get())
        } finally {
            slow.close()
            drip.join(5_000)
        }
    }

    @Test
    fun aLaunchThatFindsTheFolderFreedWhileWaitingBecomesTheInstance() {
        // A holder that never says where it listens, then goes away (a launch that died while starting).
        val holder = FileChannel.open(
            File(cache, SingleInstance.LOCK_FILE).toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE,
        )
        val held = holder.lock(SingleInstance.LOCK_POSITION, 1, false)
        val release = Thread {
            Thread.sleep(300)
            held.release()
            holder.close()
        }.apply { start() }
        try {
            assertNotNull(acquire())
        } finally {
            release.join()
        }
    }

    @Test
    fun afterCloseTheNextLaunchIsTheInstance() {
        acquire()!!.close()
        assertNotNull(acquire())
    }

    @Test
    fun differentDataFoldersNeverMeet() {
        val other = Files.createTempDirectory("mp-instance-other").toFile()
        try {
            assertNotNull(acquire())
            assertNotNull(acquire(other))
        } finally {
            opened.forEach { it.close() }
            opened.clear()
            other.deleteRecursively()
        }
    }
}
