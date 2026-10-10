package com.naeblis11.mealplanner.desktop.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-R3: the recipe photo's download, against a web server on 127.0.0.1. */
class ImageFetcherTest {
    private val handlers = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = handlers
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @After
    fun tearDown() {
        server.stop(0)
        handlers.shutdownNow()
    }

    private fun serve(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
    }

    private fun HttpExchange.send(status: Int, body: ByteArray, type: String = "image/jpeg") {
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(status, body.size.toLong())
        responseBody.write(body)
    }

    private fun redirect(from: String, to: String) = serve(from) {
        it.responseHeaders.add("Location", to)
        it.sendResponseHeaders(302, -1)
    }

    @Test
    fun aJpegComesBackAsItWasSent() {
        val jpeg = jpegBytes()
        serve("/photo.jpg") { it.send(200, jpeg) }
        assertArrayEquals(jpeg, ImageFetcher().fetch("$base/photo.jpg"))
    }

    @Test
    fun aPageThatIsNotAnImageIsRefused() {
        serve("/page") { it.send(200, "<html><body>not a photo</body></html>".toByteArray(), "text/html") }
        assertThrows(IOException::class.java) { ImageFetcher().fetch("$base/page") }
    }

    @Test
    fun onlyHttpAndHttpsAreFetched() {
        val requests = AtomicInteger()
        serve("/") {
            requests.incrementAndGet()
            it.send(200, jpegBytes())
        }
        assertThrows(IOException::class.java) { ImageFetcher().fetch("file:///C:/Windows/win.ini") }
        assertThrows(IOException::class.java) { ImageFetcher().fetch("ftp://127.0.0.1:${server.address.port}/photo.jpg") }
        assertThrows(IOException::class.java) { ImageFetcher().fetch("not an address") }
        assertEquals(0, requests.get())
    }

    @Test
    fun upToThreeRedirectsAreFollowedButNotFour() {
        val jpeg = jpegBytes()
        serve("/photo.jpg") { it.send(200, jpeg) }
        redirect("/r1", "/r2")
        redirect("/r2", "$base/r3")
        redirect("/r3", "/photo.jpg")
        assertArrayEquals(jpeg, ImageFetcher().fetch("$base/r1"))
        redirect("/s1", "/s2")
        redirect("/s2", "/s3")
        redirect("/s3", "/s4")
        redirect("/s4", "/photo.jpg")
        assertThrows(IOException::class.java) { ImageFetcher().fetch("$base/s1") }
    }

    @Test
    fun aRedirectToAnotherSchemeIsRefused() {
        redirect("/sneaky", "file:///C:/Windows/win.ini")
        assertThrows(IOException::class.java) { ImageFetcher().fetch("$base/sneaky") }
    }

    @Test
    fun aPhotoOverTheCapIsRefused() {
        serve("/big.jpg") { it.send(200, jpegBytes()) }
        assertThrows(IOException::class.java) { ImageFetcher(maxBytes = 100).fetch("$base/big.jpg") }
    }

    @Test
    fun anErrorStatusIsRefused() {
        assertThrows(IOException::class.java) { ImageFetcher().fetch("$base/nothing-here.jpg") }
    }

    @Test
    fun aServerThatStallsRunsOutOfTime() {
        serve("/slow.jpg") {
            it.responseHeaders.add("Content-Type", "image/jpeg")
            it.sendResponseHeaders(200, 0)
            it.responseBody.write(jpegBytes().copyOf(10))
            it.responseBody.flush()
            Thread.sleep(3_000)
        }
        val started = System.nanoTime()
        assertThrows(IOException::class.java) { ImageFetcher(timeout = Duration.ofMillis(300)).fetch("$base/slow.jpg") }
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMillis ms", tookMillis < 2_500)
    }

    @Test
    fun aServerThatTricklesOneByteAtATimeIsStoppedAtTheOverallDeadline() {
        // Every read succeeds within the timeout, so only an overall deadline stops this one.
        val jpeg = jpegBytes()
        serve("/trickle.jpg") {
            it.responseHeaders.add("Content-Type", "image/jpeg")
            it.sendResponseHeaders(200, 0)
            try {
                for (b in jpeg.copyOf(40)) {
                    it.responseBody.write(b.toInt())
                    it.responseBody.flush()
                    Thread.sleep(200)
                }
            } catch (e: IOException) {
                // The client gave up, as it should.
            }
        }
        val started = System.nanoTime()
        assertThrows(IOException::class.java) { ImageFetcher(timeout = Duration.ofMillis(1_000)).fetch("$base/trickle.jpg") }
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMillis ms", tookMillis in 900..3_000)
    }

    @Test
    fun aPhotoIsKnownByItsFirstBytes() {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        assertEquals("jpeg", ImageSniff.kind(jpegBytes()))
        assertEquals("png", ImageSniff.kind(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertEquals("gif", ImageSniff.kind("GIF89a....".toByteArray()))
        assertEquals("gif", ImageSniff.kind("GIF87a....".toByteArray()))
        assertEquals("webp", ImageSniff.kind("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(ImageSniff.kind("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(ImageSniff.kind("<html>".toByteArray()))
        assertNull(ImageSniff.kind(ByteArray(0)))
    }
}
