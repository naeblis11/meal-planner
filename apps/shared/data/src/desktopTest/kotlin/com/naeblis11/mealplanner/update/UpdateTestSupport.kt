package com.naeblis11.mealplanner.update

import com.naeblis11.mealplanner.app.SettingsStore
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Settings in memory. */
class MemorySettings : SettingsStore {
    val values = ConcurrentHashMap<String, Any>()

    override fun getLong(key: String): Long? = values[key] as? Long

    override fun getString(key: String): String? = values[key] as? String

    override fun put(values: Map<String, Any>) {
        this.values.putAll(values)
    }
}

/** The system installer as a list: no msiexec, no package installer (spec "Updates", tests). */
class FakeInstaller(override val closesApp: Boolean = false) : UpdateInstaller {
    @Volatile
    var allowed = true

    /** Android's foreground rule (P8-PF11): false is the user having left the app during the download. */
    @Volatile
    var foreground = true

    @Volatile
    var fails: Exception? = null

    val files = CopyOnWriteArrayList<File>()
    val installed = CopyOnWriteArrayList<ByteArray>()
    val permissionAsks = AtomicInteger()

    override fun canInstall(): Boolean = allowed

    override fun canStartNow(): Boolean = foreground

    override fun openInstallPermission() {
        permissionAsks.incrementAndGet()
    }

    override fun install(file: File) {
        fails?.let { throw it }
        files += file
        installed += file.readBytes()
    }
}

/**
 * Makes [link] point at [target], both inside a test's temp folder: a symbolic link where the platform lets the test
 * make one, else (Windows without the privilege) a directory junction, which needs none. False when neither works.
 */
object TestLinks {
    fun make(link: File, target: File): Boolean {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            return true
        } catch (e: Exception) {
            // Not allowed here; try a junction below.
        }
        if (!target.isDirectory || !System.getProperty("os.name").orEmpty().startsWith("Windows")) return false
        return try {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.absolutePath, target.absolutePath)
                .redirectErrorStream(true)
                .start()
            process.inputStream.readBytes()
            process.waitFor() == 0 && Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (e: Exception) {
            false
        }
    }
}

/** Throwaway EC keys, made in memory for one test; never the owner's release key. */
object TestKeys {
    fun pair(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
}

/** A release on [releases]: an MSI, an APK, latest.json and its signature by [keys] (made here). */
class ReleaseFixture(private val releases: FakeReleases, val keys: KeyPair = TestKeys.pair()) {
    val msi: ByteArray = ByteArray(200_000) { (it % 251).toByte() }
    val apk: ByteArray = ByteArray(150_000) { (it % 241).toByte() }

    /**
     * Publishes release [tag] and points `latest` at it. [listedMsiSha] and [listedMsiSize] are what the list says,
     * for the mismatch cases; [signer] null leaves the signature out; [tamper] changes the list after it was signed.
     */
    fun publish(
        tag: String = "release-2",
        desktop: String = "1.0.1",
        androidCode: Long = 2,
        androidName: String = "1.0.1",
        listedMsiSha: String = Sha256.of(msi),
        listedMsiSize: Long = msi.size.toLong(),
        signer: PrivateKey? = keys.private,
        tamper: Boolean = false,
    ): ReleaseManifest {
        val manifest = ReleaseManifest(
            tag,
            DesktopRelease(desktop, "MealPlanner-$desktop.msi", listedMsiSize, listedMsiSha),
            AndroidRelease(androidName, androidCode, "MealPlanner-$androidName.apk", apk.size.toLong(), Sha256.of(apk)),
        )
        val list = manifest.toJson().encodeToByteArray()
        releases.latestTag = tag
        releases.publish(tag, "MealPlanner-$desktop.msi", msi)
        releases.publish(tag, "MealPlanner-$androidName.apk", apk)
        releases.publish(tag, Updates.MANIFEST, if (tamper) list + " ".encodeToByteArray() else list)
        if (signer == null) {
            releases.unpublish(tag, Updates.SIGNATURE)
        } else {
            releases.publish(tag, Updates.SIGNATURE, (ReleaseSignature.sign(list, signer) + "\n").encodeToByteArray())
        }
        return manifest
    }
}
