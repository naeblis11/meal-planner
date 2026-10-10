package com.naeblis11.mealplanner.update

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import com.naeblis11.mealplanner.app.MealPlannerApplication
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.robolectric.Shadows.shadowOf

/** Throwaway P-256 keys, made in memory for one test; never the owner's release key. */
internal fun testKeys(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

/** The URI the app's FileProvider gives [name] in cache/updates on a phone (it maps nothing on a Windows test host). */
internal fun providerUri(context: Context, name: String): Uri =
    Uri.parse("content://${AndroidUpdateInstaller.authority(context)}/updates/$name")

/** The real installer and the app's own foreground count, with the provider's URI stood in for. */
internal fun phoneInstaller(context: Context): AndroidUpdateInstaller =
    AndroidUpdateInstaller(
        context,
        (context.applicationContext as MealPlannerApplication).foreground::any,
        uriFor = { providerUri(context, it.name) },
    )

/**
 * Tells Robolectric's package manager that [pkg] opens [uri] as an APK; a system app when [system]. Nothing is
 * installed: it only answers queryIntentActivities.
 */
internal fun addApkHandler(context: Context, uri: Uri, pkg: String, system: Boolean) {
    val probe = Intent(Intent.ACTION_VIEW).setDataAndType(uri, AndroidUpdateInstaller.APK_TYPE)
    val info = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.InstallStart"
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        }
    }
    @Suppress("DEPRECATION")
    shadowOf(context.packageManager).addResolveInfoForIntent(probe, info)
}

const val SYSTEM_INSTALLER = "com.android.packageinstaller"
const val OTHER_INSTALLER = "com.example.apkgrabber"

/**
 * GitHub Releases for the phone's tests, on 127.0.0.1 on a port the system picks; never GitHub. Serves a release whose
 * latest.json (signed by [keys], made in the test) offers versionCode 2 and its APK. With [holdList], latest.json waits
 * until [release] is counted down.
 */
internal class PhoneReleases(keys: KeyPair, holdList: Boolean = false) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val requests = CopyOnWriteArrayList<String>()
    val listAsked = CountDownLatch(1)
    val release = CountDownLatch(if (holdList) 1 else 0)

    val apk = ByteArray(50_000) { (it % 241).toByte() }
    private val list = ReleaseManifest(
        TAG,
        DesktopRelease(VERSION, "MealPlanner-$VERSION.msi", 10, "b".repeat(64)),
        AndroidRelease(VERSION, VERSION_CODE, APK, apk.size.toLong(), Sha256.of(apk)),
    ).toJson().encodeToByteArray()
    private val signature = ReleaseSignature.sign(list, keys.private).encodeToByteArray()

    val endpoints: ReleaseEndpoints get() = ReleaseEndpoints("http://127.0.0.1:${server.address.port}/r")

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                requests += path
                val body = when (path) {
                    "/r/latest/download/${Updates.MANIFEST}" -> {
                        listAsked.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        list
                    }
                    "/r/latest/download/${Updates.SIGNATURE}" -> signature
                    "/r/download/$TAG/$APK" -> apk
                    else -> null
                }
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    /** The update client as the tests use it: 127.0.0.1 only, plain http, short timeouts. */
    fun http(): ReleaseHttp = ReleaseHttp(hosts = setOf("127.0.0.1"), httpsOnly = false, connectTimeoutMillis = 2_000, readTimeoutMillis = 15_000)

    fun assetRequests(): Int = requests.count { it == "/r/download/$TAG/$APK" }

    /** The release build's Updates on this server, with [installer] (the phone's, by default). */
    fun updates(context: Context, keys: KeyPair, installer: UpdateInstaller = phoneInstaller(context)): Updates =
        AndroidUpdates.build(context, keys.public, release = true, http = http(), endpoints = endpoints, installer = installer)

    override fun close() {
        release.countDown()
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        // Far above any real version, so raising the app's own versionCode in apps/gradle.properties (as each release
        // does) never makes this release "not newer" and the tests quietly offer nothing.
        const val VERSION_CODE = 999_999L
        const val VERSION = "99.0.0" // the Windows half must stay an MSI version (major at most 255)
        const val TAG = "release-999999"
        const val APK = "MealPlanner-$VERSION.apk"

        /** The name Updates gives the verified download in cache/updates. */
        const val CACHED = Updates.FILE_PREFIX + APK
    }
}
