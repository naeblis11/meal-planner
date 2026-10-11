package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.google.FakeProtector
import com.naeblis11.mealplanner.desktop.server.PortInUseException
import com.naeblis11.mealplanner.desktop.server.PortProbe
import com.naeblis11.mealplanner.desktop.server.eventually
import com.naeblis11.mealplanner.desktop.server.freeLoopbackPort
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2b (D1): the JDK's HttpsServer behind SyncEngine, on 127.0.0.1 ephemeral ports only, TLS 1.2 and 1.3 only. */
class JdkHttpsEngineTest {
    private val dir: File = Files.createTempDirectory("mp-https").toFile()
    private val certificate = SyncCertificate(dir, FakeProtector())
    private val logs = CopyOnWriteArrayList<String>()
    private val engine = JdkHttpsEngine(portFree = { PortProbe.isFree(it, InetAddress.getLoopbackAddress()) }, log = { logs += it })
    private val port = freeLoopbackPort()

    @After
    fun tearDown() {
        engine.stop()
        dir.deleteRecursively()
    }

    // A test client that trusts exactly this certificate (SyncHttp, Task 7, pins it instead).
    private fun client(): SSLContext {
        val trusted = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("pc", certificate.certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
    }

    private fun connect(path: String): HttpsURLConnection =
        (URI("https://127.0.0.1:$port$path").toURL().openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = client().socketFactory
            hostnameVerifier = HostnameVerifier { _, _ -> true }
            connectTimeout = 5_000
            readTimeout = 5_000
        }

    private fun start(handler: SyncHandler) = engine.start("127.0.0.1", port, certificate.sslContext(), handler)

    @Test
    fun aRequestReachesTheHandlerAndItsReplyComesBack() {
        val seen = AtomicReference<SyncHttpRequest>()
        val body = AtomicReference<ByteArray>()
        start { request ->
            seen.set(request)
            body.set(request.body(1024))
            SyncHttpResponse.json(201, "{\"ok\":true}")
        }
        assertTrue(engine.listening)
        val c = connect("/sync/echo")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer k")
        c.outputStream.use { it.write("hello".toByteArray()) }
        assertEquals(201, c.responseCode)
        assertEquals(SyncHttpResponse.JSON, c.contentType)
        assertEquals("{\"ok\":true}", c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
        val request = seen.get()
        assertEquals("POST", request.method)
        assertEquals("/sync/echo", request.path)
        assertEquals("Bearer k", request.authorization)
        assertEquals(5L, request.contentLength)
        assertEquals("127.0.0.1", request.remoteAddress)
        assertEquals("hello", body.get().toString(Charsets.UTF_8))
    }

    @Test
    fun aBodyOverItsLimitIsStoppedWhileReading() {
        start { request ->
            try {
                request.body(4)
                SyncHttpResponse.NO_CONTENT
            } catch (e: BodyTooLargeException) {
                SyncHttpResponse.error(413, "too_large")
            }
        }
        val c = connect("/x")
        c.requestMethod = "PUT"
        c.doOutput = true
        c.outputStream.use { it.write(ByteArray(10)) }
        assertEquals(413, c.responseCode)
    }

    @Test
    fun a204HasNoBody() {
        start { SyncHttpResponse.NO_CONTENT }
        val c = connect("/x")
        assertEquals(204, c.responseCode)
    }

    @Test
    fun tls12AndTls13AreSpokenAndNothingOlder() {
        start { SyncHttpResponse.NO_CONTENT }
        val factory = client().socketFactory
        (factory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
            socket.enabledProtocols = arrayOf("TLSv1.1")
            assertThrows(IOException::class.java) { socket.startHandshake() }
        }
        for (protocol in listOf("TLSv1.2", "TLSv1.3")) {
            (factory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
                socket.enabledProtocols = arrayOf(protocol)
                socket.startHandshake()
                assertEquals(protocol, socket.session.protocol)
            }
        }
    }

    @Test
    fun aPortHeldElsewhereIsPortInUse() {
        ServerSocket(port, 50, InetAddress.getLoopbackAddress()).use {
            assertThrows(PortInUseException::class.java) { start { SyncHttpResponse.NO_CONTENT } }
            assertFalse(engine.listening)
        }
    }

    @Test
    fun aBindThatFailsIsPortInUseEvenWhenTheProbeMissedIt() {
        val unprobed = JdkHttpsEngine(portFree = { true }, log = { logs += it })
        ServerSocket(port, 50, InetAddress.getLoopbackAddress()).use {
            assertThrows(PortInUseException::class.java) {
                unprobed.start("127.0.0.1", port, certificate.sslContext()) { SyncHttpResponse.NO_CONTENT }
            }
            assertFalse(unprobed.listening)
        }
    }

    @Test
    fun afterStopNothingAnswers() {
        start { SyncHttpResponse.NO_CONTENT }
        engine.stop()
        assertFalse(engine.listening)
        assertThrows(IOException::class.java) { connect("/x").responseCode }
        engine.stop()
    }

    @Test
    fun aChunkedBodyHasNoDeclaredLengthAndIsStillRead() {
        val seen = AtomicReference<SyncHttpRequest>()
        val body = AtomicReference<ByteArray>()
        start { request ->
            seen.set(request)
            body.set(request.body(1024))
            SyncHttpResponse.NO_CONTENT
        }
        val c = connect("/sync/echo")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setChunkedStreamingMode(0)
        c.outputStream.use { it.write("chunked".toByteArray()) }
        assertEquals(204, c.responseCode)
        assertEquals(-1L, seen.get().contentLength)
        assertEquals("chunked", body.get().toString(Charsets.UTF_8))
    }

    @Test
    fun aHandlerThatThrowsIsAnswered500AndOnlyItsClassIsLogged() {
        start { request ->
            request.body(1024)
            throw IllegalStateException("secret-detail")
        }
        val c = connect("/sync/exchange")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer secret-key")
        c.outputStream.use { it.write("secret-body".toByteArray()) }
        assertEquals(500, c.responseCode)
        assertEquals("{\"error\":\"failed\"}", c.errorStream.use { it.readBytes() }.toString(Charsets.UTF_8))
        eventually { logs.any { it.contains("IllegalStateException") } }
        assertTrue(logs.toString(), logs.any { it == "Meal Planner: POST /sync/exchange failed: IllegalStateException" })
        for (secret in listOf("secret-detail", "secret-key", "secret-body", "Bearer")) {
            assertFalse(logs.toString(), logs.any { it.contains(secret) })
        }
    }

    @Test
    fun atMostTHREADSHandlersRunAtOnce() {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val release = CountDownLatch(1)
        start {
            val now = running.incrementAndGet()
            most.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            release.await(10, TimeUnit.SECONDS)
            running.decrementAndGet()
            SyncHttpResponse.NO_CONTENT
        }
        val codes = CopyOnWriteArrayList<Int>()
        val clients = (1..JdkHttpsEngine.THREADS + 2).map {
            thread { codes += connect("/x").apply { readTimeout = 15_000 }.responseCode }
        }
        eventually { running.get() == JdkHttpsEngine.THREADS }
        Thread.sleep(300) // a short negative wait: no fifth handler starts
        assertEquals(JdkHttpsEngine.THREADS, most.get())
        release.countDown()
        clients.forEach { it.join(15_000) }
        assertEquals(List(JdkHttpsEngine.THREADS + 2) { 204 }, codes.toList())
        assertEquals(JdkHttpsEngine.THREADS, most.get())
    }

    private fun shortLimits() = JdkHttpsEngine(
        portFree = { PortProbe.isFree(it, InetAddress.getLoopbackAddress()) },
        requestMillis = 1_000,
        responseMillis = 2_000,
        log = { logs += it },
    )

    // A stall on every thread: each raw socket sends one byte of a ClientHello and then nothing.
    @Test
    fun stalledConnectionsOnEveryThreadDoNotStopARealClient() {
        val guarded = shortLimits()
        guarded.start("127.0.0.1", port, certificate.sslContext()) { SyncHttpResponse.NO_CONTENT }
        val stalled = (1..JdkHttpsEngine.THREADS).map {
            Socket(InetAddress.getLoopbackAddress(), port).apply { getOutputStream().apply { write(0x16); flush() } }
        }
        try {
            val began = System.nanoTime()
            val c = connect("/x").apply { readTimeout = 10_000 }
            assertEquals(204, c.responseCode)
            val tookMillis = (System.nanoTime() - began) / 1_000_000
            assertTrue("took $tookMillis ms", tookMillis < 8_000)
        } finally {
            stalled.forEach { it.close() }
            guarded.stop()
        }
    }

    @Test
    fun aClientThatStopsMidBodyFreesItsThread() {
        val guarded = shortLimits()
        val failures = CopyOnWriteArrayList<String>()
        guarded.start("127.0.0.1", port, certificate.sslContext()) { request ->
            if (request.path == "/upload") {
                try {
                    request.body(1024)
                } catch (e: Exception) {
                    failures += e.javaClass.simpleName
                    throw e
                }
            }
            SyncHttpResponse.NO_CONTENT
        }
        val stalled = (1..JdkHttpsEngine.THREADS).map {
            (client().socketFactory.createSocket("127.0.0.1", port) as SSLSocket).apply {
                outputStream.write(STALLED_UPLOAD.toByteArray())
                outputStream.flush()
            }
        }
        try {
            eventually(8_000) { failures.size == JdkHttpsEngine.THREADS }
            assertEquals(List(JdkHttpsEngine.THREADS) { "StalledException" }, failures.toList())
            assertEquals(204, connect("/x").apply { readTimeout = 10_000 }.responseCode)
        } finally {
            stalled.forEach { it.close() }
            guarded.stop()
        }
    }

    @Test
    fun theJdkLimitsAreSetOnlyWhereUnset() {
        val req = System.getProperty(JdkHttpsEngine.JDK_MAX_REQ_TIME)
        val rsp = System.getProperty(JdkHttpsEngine.JDK_MAX_RSP_TIME)
        try {
            System.clearProperty(JdkHttpsEngine.JDK_MAX_REQ_TIME)
            System.setProperty(JdkHttpsEngine.JDK_MAX_RSP_TIME, "7")
            JdkHttpsEngine.applyJdkLimits()
            assertEquals("300", System.getProperty(JdkHttpsEngine.JDK_MAX_REQ_TIME))
            assertEquals("7", System.getProperty(JdkHttpsEngine.JDK_MAX_RSP_TIME))
        } finally {
            if (req == null) System.clearProperty(JdkHttpsEngine.JDK_MAX_REQ_TIME) else System.setProperty(JdkHttpsEngine.JDK_MAX_REQ_TIME, req)
            if (rsp == null) System.clearProperty(JdkHttpsEngine.JDK_MAX_RSP_TIME) else System.setProperty(JdkHttpsEngine.JDK_MAX_RSP_TIME, rsp)
        }
    }

    @Test
    fun theServerOffersOnlyTls12And13() {
        assertEquals(setOf("TLSv1.2", "TLSv1.3"), JdkHttpsEngine.PROTOCOLS.toSet())
    }
}

// Headers promising 100 bytes, then 10 of them and nothing more.
private val STALLED_UPLOAD = listOf("POST /upload HTTP/1.1", "Host: x", "Content-Length: 100", "", "0123456789").joinToString(CRLF)

private const val CRLF = "\r\n"
