package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.desktop.google.DpapiProtector
import com.naeblis11.mealplanner.desktop.peers.PeerTxt
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.File
import java.net.http.HttpClient
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.imageio.ImageIO
import javax.jmdns.ServiceInfo
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * P7-R6: the packaged app's self-check, for apps/desktopApp/smoke-packaged.ps1 only. With -Dmealplanner.selfCheck=on
 * (which the script adds through JAVA_TOOL_OPTIONS; the installed launcher never passes it), Main runs these checks once
 * the app has started, logs one line per check and a summary, keeps the app up for [HOLD_MILLIS] so the script can ask
 * /healthz, then hides the window and closes it as Quit does (P7-PF7) and exits: [EXIT_OK] when every check passed,
 * [EXIT_CHECK_FAILED] when one failed, [EXIT_CLOSE_TIMED_OUT] when the close ran out of time. Each check proves
 * something packaging can lose: an ImageIO plugin's META-INF/services entry (WebP), JNA's native library (DPAPI), a JDK
 * module (the TLS and EC providers and the CA certificates, java.net.http, jdk.httpserver), JmDNS's classes, and the
 * launcher's own path (P7-R3). None listens or connects (the HTTP client and the unbound server are made, then closed),
 * writes a file or touches the registry, and no line names a secret: DPAPI seals a made-up value, in memory.
 */
object SelfCheck {
    const val PROPERTY = "mealplanner.selfCheck"
    const val PREFIX = "Meal Planner: self-check"
    const val INSTALLED_EXE = "Meal Planner.exe"
    const val HOLD_MILLIS = 20_000L
    const val READY_MILLIS = 60_000L
    const val EXIT_OK = 0
    const val EXIT_CHECK_FAILED = 3
    const val EXIT_CLOSE_TIMED_OUT = 4
    private const val MAX_MESSAGE = 200

    /** DesktopPhotoProcessorTest's 8 x 6 lossy WebP, made with Pillow. */
    internal const val WEBP_8X6 = "UklGRjoAAABXRUJQVlA4IC4AAADQAQCdASoIAAYAAUAmJaACdLoB+AADsAD+pNf/TSPGkeNI+Yt/84ljqd3aAAAA"

    /** What [run] found: one line per check, then the summary; [passed] when no check failed. */
    data class Report(val lines: List<String>, val passed: Boolean)

    fun enabled(value: String? = System.getProperty(PROPERTY)): Boolean = value == "on"

    /** Runs every check, even after one failed. A failure is its class and a short, one-line message. */
    fun run(checks: List<Pair<String, () -> String>>): Report {
        var passed = 0
        val lines = checks.map { (name, check) ->
            try {
                val detail = check()
                passed++
                if (detail.isEmpty()) "$PREFIX $name ok" else "$PREFIX $name ok ($detail)"
            } catch (t: Throwable) {
                "$PREFIX $name FAILED: ${describe(t)}"
            }
        }
        return Report(lines + "$PREFIX done: $passed of ${checks.size} passed", passed == checks.size)
    }

    /** [run], [log] each line, wait [holdMillis], [close] the app, then [exit] with what happened. */
    fun runThenQuit(
        checks: List<Pair<String, () -> String>>,
        close: () -> Boolean,
        exit: (Int) -> Unit,
        holdMillis: Long = HOLD_MILLIS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        log: (String) -> Unit = { System.err.println(it) },
    ) {
        val report = run(checks)
        report.lines.forEach(log)
        sleep(holdMillis)
        val closed = try {
            close().also { if (!it) log("$PREFIX: closing timed out") }
        } catch (t: Throwable) {
            // An Error too: the window is hidden by now, so a close that skipped the exit would leave an invisible
            // process holding the single-instance lock.
            log("$PREFIX: closing failed: ${t.javaClass.name}")
            false
        }
        exit(
            when {
                !report.passed -> EXIT_CHECK_FAILED
                !closed -> EXIT_CLOSE_TIMED_OUT
                else -> EXIT_OK
            },
        )
    }

    /**
     * The tray's Quit (and a close with no tray). While the self-check runs ([enabled]) it does nothing but say so: the
     * self-check's own hide, close and exit end the app within [HOLD_MILLIS] and a bounded close, and a second quit
     * racing it could end the process mid-close or with the wrong exit code. Otherwise it is [quit].
     */
    fun userQuit(enabled: Boolean, quit: () -> Unit, log: (String) -> Unit = { System.err.println(it) }): () -> Unit =
        if (enabled) {
            { log("Meal Planner: Quit is off during the self-check, which quits the app by itself") }
        } else {
            quit
        }

    /** Everything but the server, which Main checks itself (it alone knows when startup is done). */
    fun standardChecks(
        appPath: () -> String? = { System.getProperty(StartWithWindows.APP_PATH_PROPERTY) },
        command: () -> String? = { ProcessHandle.current().info().command().orElse(null) },
    ): List<Pair<String, () -> String>> = listOf(
        "webp" to { webp() },
        "jna" to { dpapi() },
        "tls" to { tls() },
        "http-client" to { httpClient() },
        "http-server" to { httpServer() },
        "jmdns" to { jmdns() },
        "launcher" to { launcher(appPath(), command) },
    )

    /** P7-R3: the Run key's launcher must be the installed exe. The detail names all three sources, for the record. */
    internal fun launcher(appPath: String?, command: () -> String?): String {
        val resolved = StartWithWindows.launcherPath(appPath, command)
        check(resolved != null && File(resolved).name == INSTALLED_EXE) { "resolved=$resolved" }
        return "resolved=$resolved; app-path=$appPath; command=${command()}"
    }

    private fun webp(): String {
        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(WEBP_8X6)))
            ?: error("no ImageIO reader took the WebP")
        check(image.width == 8 && image.height == 6) { "decoded as ${image.width} x ${image.height}" }
        return "8 x 6"
    }

    // In memory only, with a made-up value: the user's own sign-in is never touched.
    private fun dpapi(): String {
        val protector = DpapiProtector()
        val plain = "not-a-real-secret".toByteArray(Charsets.US_ASCII)
        check(protector.unprotect(protector.protect(plain)).contentEquals(plain)) { "DPAPI gave back something else" }
        return "DPAPI"
    }

    private fun tls(): String {
        SSLContext.getInstance("TLSv1.3").init(null, null, null)
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        KeyAgreement.getInstance("X25519")
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val roots = factory.trustManagers.filterIsInstance<X509TrustManager>().sumOf { it.acceptedIssuers.size }
        check(roots > 0) { "no trusted root certificates" }
        return "TLSv1.3, EC, $roots trusted roots"
    }

    // Closed again where the runtime allows it (JDK 21+, which the packaged app has).
    private fun httpClient(): String {
        (HttpClient.newBuilder().build() as? AutoCloseable)?.close()
        return ""
    }

    // Created unbound, so it never listens, then stopped.
    private fun httpServer(): String {
        HttpServer.create().stop(0)
        return ""
    }

    private fun jmdns(): String {
        val info = ServiceInfo.create(PeerTxt.SERVICE_TYPE, "self-check", 1, 0, 0, mapOf("v" to "1"))
        check(info.getPropertyString("v") == "1") { "the TXT record didn't survive" }
        return ""
    }

    private fun describe(t: Throwable): String {
        val message = t.message?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")?.take(MAX_MESSAGE)
        return if (message.isNullOrEmpty()) t.javaClass.name else "${t.javaClass.name}: $message"
    }
}
