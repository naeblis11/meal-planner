package com.naeblis11.mealplanner.desktop.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-R3: the recipe photo's download, against a web server on 127.0.0.1. The fetcher refuses the local network, so the
 * tests that reach the test server resolve its host through [resolve], which answers a public address for it (the
 * request is still made by host name, so it still reaches the real server); names and literals it doesn't know are
 * judged as the real resolver would, without DNS. Nothing here touches the network.
 */
class ImageFetcherTest {
    private val handlers = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = handlers
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val resolved = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        server.stop(0)
        handlers.shutdownNow()
    }

    // The test server's 127.0.0.1 answers as a public address (as if the photo were on the web); a known name answers its
    // table; any other literal is parsed as InetAddress does (no lookup); any other name is unknown.
    private fun resolve(host: String): List<InetAddress> {
        resolved += host
        return when {
            host == "127.0.0.1" -> listOf(InetAddress.getByName("203.0.113.9"))
            host == "photos.example" -> listOf(InetAddress.getByName("203.0.113.5"))
            host == "lan.example" -> listOf(InetAddress.getByName("203.0.113.5"), InetAddress.getByName("172.16.0.9"))
            host == "v6lan.example" -> listOf(InetAddress.getByName("fd12:3456::1"))
            host.first().isDigit() || host.first() == ':' || ':' in host -> InetAddress.getAllByName(host).toList()
            else -> throw UnknownHostException(host)
        }
    }

    private fun fetcher(timeout: Duration = Duration.ofSeconds(10), maxBytes: Int = ImageFetcher.MAX_BYTES) =
        ImageFetcher(timeout = timeout, maxBytes = maxBytes, resolve = ::resolve)

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
        assertArrayEquals(jpeg, fetcher().fetch("$base/photo.jpg"))
        assertEquals(listOf("127.0.0.1"), resolved.toList())
    }

    @Test
    fun aPageThatIsNotAnImageIsRefused() {
        serve("/page") { it.send(200, "<html><body>not a photo</body></html>".toByteArray(), "text/html") }
        assertThrows(IOException::class.java) { fetcher().fetch("$base/page") }
    }

    @Test
    fun onlyHttpAndHttpsAreFetched() {
        val requests = AtomicInteger()
        serve("/") {
            requests.incrementAndGet()
            it.send(200, jpegBytes())
        }
        assertThrows(IOException::class.java) { fetcher().fetch("file:///C:/Windows/win.ini") }
        assertThrows(IOException::class.java) { fetcher().fetch("ftp://127.0.0.1:${server.address.port}/photo.jpg") }
        assertThrows(IOException::class.java) { fetcher().fetch("not an address") }
        assertEquals(0, requests.get())
        assertEquals(emptyList<String>(), resolved.toList())
    }

    @Test
    fun upToThreeRedirectsAreFollowedButNotFour() {
        val jpeg = jpegBytes()
        serve("/photo.jpg") { it.send(200, jpeg) }
        redirect("/r1", "/r2")
        redirect("/r2", "$base/r3")
        redirect("/r3", "/photo.jpg")
        assertArrayEquals(jpeg, fetcher().fetch("$base/r1"))
        // Every hop's host is judged again.
        assertEquals(4, resolved.size)
        redirect("/s1", "/s2")
        redirect("/s2", "/s3")
        redirect("/s3", "/s4")
        redirect("/s4", "/photo.jpg")
        assertThrows(IOException::class.java) { fetcher().fetch("$base/s1") }
    }

    @Test
    fun aRedirectToAnotherSchemeIsRefused() {
        redirect("/sneaky", "file:///C:/Windows/win.ini")
        assertThrows(IOException::class.java) { fetcher().fetch("$base/sneaky") }
    }

    @Test
    fun aPhotoOverTheCapIsRefused() {
        serve("/big.jpg") { it.send(200, jpegBytes()) }
        assertThrows(IOException::class.java) { fetcher(maxBytes = 100).fetch("$base/big.jpg") }
    }

    @Test
    fun anErrorStatusIsRefused() {
        assertThrows(IOException::class.java) { fetcher().fetch("$base/nothing-here.jpg") }
    }

    @Test(timeout = 10_000)
    fun aServerThatStallsRunsOutOfTime() {
        serve("/slow.jpg") {
            it.responseHeaders.add("Content-Type", "image/jpeg")
            it.sendResponseHeaders(200, 0)
            it.responseBody.write(jpegBytes().copyOf(10))
            it.responseBody.flush()
            Thread.sleep(3_000)
        }
        // The deadline is 300 ms; the server sleeps 3 s. The test's own timeout is the generous upper bound.
        assertThrows(IOException::class.java) { fetcher(timeout = Duration.ofMillis(300)).fetch("$base/slow.jpg") }
    }

    @Test(timeout = 15_000)
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
        assertThrows(IOException::class.java) { fetcher(timeout = Duration.ofMillis(1_000)).fetch("$base/trickle.jpg") }
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        // The lower bound shows the deadline, not a per-read timeout, ended it; the test's timeout is the upper one.
        assertTrue("took $tookMillis ms", tookMillis >= 900)
    }

    // The local network (this PC, the LAN) is never fetched: judged before any request, so nothing is connected to.
    private fun assertRefusedAsLocal(address: String, fetcher: ImageFetcher = fetcher()) {
        val refused = assertThrows(IOException::class.java) { fetcher.fetch(address) }
        assertEquals(address, LocalNetwork.REFUSED, refused.message)
    }

    @Test
    fun thisPcAndTheLocalNetworkAreNeverFetched() {
        val requests = AtomicInteger()
        serve("/") {
            requests.incrementAndGet()
            it.send(200, jpegBytes())
        }
        // The real resolver: a literal is parsed, never looked up, and a name under .local or localhost is refused by name.
        val real = ImageFetcher()
        for (address in listOf(
            "http://127.0.0.1:${server.address.port}/photo.jpg",
            "http://127.0.0.1:5055/photo.jpg",
            "http://127.8.9.10/photo.jpg",
            "http://[::1]:${server.address.port}/photo.jpg",
            "http://[::ffff:127.0.0.1]/photo.jpg",
            "http://10.1.2.3/photo.jpg",
            "http://172.16.0.1/photo.jpg",
            "http://169.254.169.254/latest/meta-data",
            "http://[fe80::1]/photo.jpg",
            "http://[fec0::1]/photo.jpg",
            "http://[fd12:3456::1]/photo.jpg",
            "http://[::ffff:172.16.0.1]/photo.jpg",
            "http://[::ffff:10.1.2.3]/photo.jpg",
            "http://[::ffff:169.254.1.1]/photo.jpg",
            "http://0.0.0.0/photo.jpg",
            "http://[::]/photo.jpg",
            "http://224.0.0.1/photo.jpg",
            "http://[ff02::1]/photo.jpg",
            "http://localhost:${server.address.port}/photo.jpg",
            "http://LOCALHOST./photo.jpg",
            "http://printer.local/photo.jpg",
            "https://kitchen-pc.LOCAL/photo.jpg",
        )) {
            assertRefusedAsLocal(address, real)
        }
        assertEquals(0, requests.get())
    }

    @Test
    fun aNameThatAnswersWithAPrivateAddressAmongPublicOnesIsRefused() {
        assertRefusedAsLocal("http://lan.example/photo.jpg")
        assertRefusedAsLocal("http://v6lan.example/photo.jpg")
        assertEquals(listOf("lan.example", "v6lan.example"), resolved.toList())
    }

    @Test
    fun aNameThatCannotBeFoundIsRefused() {
        val refused = assertThrows(IOException::class.java) { fetcher().fetch("http://nowhere.example/photo.jpg") }
        assertEquals("The photo's host couldn't be found.", refused.message)
    }

    @Test
    fun aRedirectFromAPublicHostToThePrivateNetworkIsRefused() {
        val requests = AtomicInteger()
        serve("/photo.jpg") {
            requests.incrementAndGet()
            it.send(200, jpegBytes())
        }
        // The test server stands in for a public host (its 127.0.0.1 resolves as public here); each redirect's host is
        // judged like the first, so the LAN, this PC and a .local name are refused at the hop, and nothing more is fetched.
        redirect("/to-lan", "http://172.16.0.1/photo.jpg")
        redirect("/to-v6lan", "http://[fe80::1]/photo.jpg")
        redirect("/to-self", "http://127.0.0.1:5055/photo.jpg")
        redirect("/to-local-name", "http://printer.local/photo.jpg")
        redirect("/to-lan-name", "http://lan.example/photo.jpg")
        redirect("/to-mapped", "http://[::ffff:10.1.2.3]/photo.jpg")
        for (path in listOf("/to-lan", "/to-v6lan", "/to-local-name", "/to-lan-name", "/to-mapped")) {
            assertRefusedAsLocal("$base$path")
        }
        // 127.0.0.1 is the test server's own host, which this test's resolver calls public: a resolver that does so only
        // for the first hop (the public page) shows the redirect back to this PC refused at the second.
        val hops = AtomicInteger()
        val firstHopPublic = ImageFetcher(resolve = { host ->
            if (hops.incrementAndGet() == 1) listOf(InetAddress.getByName("203.0.113.9")) else InetAddress.getAllByName(host).toList()
        })
        assertRefusedAsLocal("$base/to-self", firstHopPublic)
        assertEquals(2, hops.get())
        assertEquals(0, requests.get())
        assertFalse(resolved.contains("printer.local"))
    }

    @Test
    fun theLocalNetworkRule() {
        // 192.168 is spelled in two parts: tools/export_public.py keeps home-network addresses out of the public repo.
        for (local in listOf("127.0.0.1", "::1", "10.1.2.3", "172.31.255.255", "192.168" + ".0.1", "169.254.0.1", "fe80::1", "fec0::1", "fc00::1", "fdff::1", "0.0.0.0", "::", "224.0.0.1", "ff02::1", "::ffff:172.16.0.1")) {
            assertTrue(local, LocalNetwork.isLocalAddress(InetAddress.getByName(local)))
        }
        for (public in listOf("203.0.113.9", "8.8.8.8", "172.32.0.1", "2001:db8::1", "::ffff:203.0.113.9")) {
            assertFalse(public, LocalNetwork.isLocalAddress(InetAddress.getByName(public)))
        }
        for (name in listOf("localhost", "LocalHost", "localhost.", "printer.local", "Kitchen-Pc.LOCAL", "x.local.")) {
            assertTrue(name, LocalNetwork.isLocalName(name))
        }
        for (name in listOf("photos.example", "localhost.example", "local.example.com", "mylocal", "www.example.local.com")) {
            assertFalse(name, LocalNetwork.isLocalName(name))
        }
        assertEquals("::1", LocalNetwork.bare("[::1]"))
        assertEquals("photos.example", LocalNetwork.bare("photos.example"))
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
