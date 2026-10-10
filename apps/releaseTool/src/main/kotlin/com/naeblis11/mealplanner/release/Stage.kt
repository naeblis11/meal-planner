package com.naeblis11.mealplanner.release

import com.naeblis11.mealplanner.update.AndroidRelease
import com.naeblis11.mealplanner.update.DesktopRelease
import com.naeblis11.mealplanner.update.ReleaseManifest
import com.naeblis11.mealplanner.update.Sha256
import java.io.File

/**
 * A release's files, in an empty folder, ready to sign and publish: the MSI and the APK under names without spaces
 * (GitHub turns a space in an asset's name into a dot), and latest.json listing both. The tag is
 * `release-<versionCode>`: versionCode goes up with every release (spec), so it is never used twice.
 */
object Stage {
    const val MANIFEST = "latest.json"

    fun tag(versions: ReleaseVersions): String = "release-${versions.androidCode}"

    fun msiName(versions: ReleaseVersions): String = "MealPlanner-${versions.desktop}.msi"

    fun apkName(versions: ReleaseVersions): String = "MealPlanner-${versions.androidName}.apk"

    /** Stands in for a hash in the check made before anything is copied; the list written has the copies' own. */
    private val UNHASHED = "0".repeat(64)

    /**
     * Checks the versions, names and sizes first (ReleaseManifest's constructor throws ManifestException) so a bad
     * input stops before anything is copied. Then copies the files into [folder], which must be empty or missing, and
     * lists the copies' own sizes and SHA-256 in latest.json: what is published is what was hashed. If a copy or the
     * write fails, only what this call made is removed again, and the error says so.
     */
    fun stage(properties: File, msi: File, apk: File, folder: File): ReleaseManifest =
        stage(properties, msi, apk, folder) { from, to -> from.copyTo(to) }

    /** [copy] is the tests' seam for a copy that fails part way. */
    internal fun stage(properties: File, msi: File, apk: File, folder: File, copy: (File, File) -> Unit): ReleaseManifest {
        val versions = ReleaseVersions.read(properties)
        if (!msi.isFile) throw ReleaseToolException("There is no MSI at $msi.")
        if (!apk.isFile) throw ReleaseToolException("There is no APK at $apk.")
        if (folder.exists() && !folder.isDirectory) throw ReleaseToolException("$folder isn't a folder.")
        if (!folder.list().isNullOrEmpty()) throw ReleaseToolException("$folder isn't empty.")
        manifest(versions, msi.length(), UNHASHED, apk.length(), UNHASHED)

        // The folders this call makes, deepest first, so a failure can take back exactly those.
        val madeFolders = generateSequence(folder.absoluteFile) { it.parentFile }.takeWhile { !it.exists() }.toList()
        val madeFiles = mutableListOf<File>()
        try {
            if (!folder.isDirectory && !folder.mkdirs()) throw ReleaseToolException("Couldn't make $folder.")
            val stagedMsi = File(folder, msiName(versions))
            madeFiles += stagedMsi
            copy(msi, stagedMsi)
            val stagedApk = File(folder, apkName(versions))
            madeFiles += stagedApk
            copy(apk, stagedApk)
            val manifest = manifest(versions, stagedMsi.length(), Sha256.of(stagedMsi), stagedApk.length(), Sha256.of(stagedApk))
            val list = File(folder, MANIFEST)
            madeFiles += list
            list.writeText(manifest.toJson(), Charsets.UTF_8)
            return manifest
        } catch (e: Exception) {
            val left = madeFiles.reversed().filter { it.exists() && !it.delete() } +
                madeFolders.filter { it.exists() && !it.delete() }
            val reason = e.message ?: e.javaClass.simpleName
            val cleanup = if (left.isEmpty()) {
                "Removed what this run had made in $folder."
            } else {
                "Couldn't remove what this run made: ${left.joinToString()}."
            }
            throw ReleaseToolException("Staging stopped: $reason $cleanup")
        }
    }

    private fun manifest(versions: ReleaseVersions, msiSize: Long, msiSha: String, apkSize: Long, apkSha: String) =
        ReleaseManifest(
            tag = tag(versions),
            desktop = DesktopRelease(versions.desktop, msiName(versions), msiSize, msiSha),
            android = AndroidRelease(versions.androidName, versions.androidCode, apkName(versions), apkSize, apkSha),
        )
}
