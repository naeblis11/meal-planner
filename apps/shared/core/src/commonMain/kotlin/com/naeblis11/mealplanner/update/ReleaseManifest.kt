package com.naeblis11.mealplanner.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The Windows app's entry in latest.json: its MSI. */
data class DesktopRelease(val version: String, val file: String, val size: Long, val sha256: String)

/** The Android app's entry: its APK. [versionCode] goes up with every release; [versionName] is what people see. */
data class AndroidRelease(val versionName: String, val versionCode: Long, val file: String, val size: Long, val sha256: String)

/** latest.json, or one of its entries, is wrong in some way; the whole list is then ignored. */
class ManifestException(message: String) : Exception(message)

/**
 * latest.json (spec "Updates"): the GitHub release [tag] and each app's version, file name, size and SHA-256. The apps
 * read it only after its signature has verified (ReleaseSignature), and even then every field is checked here, in the
 * constructor, because a file name becomes a download address and a file in the app's cache: one path segment of
 * letters, digits, dots, dashes and underscores. The release tool builds it with the same constructor, so it can't
 * write a list the apps would refuse.
 */
data class ReleaseManifest(val tag: String, val desktop: DesktopRelease?, val android: AndroidRelease?) {
    init {
        if (!TAG.matches(tag)) throw ManifestException("The release tag '${tag.take(40)}' isn't one Meal Planner uses.")
        if (desktop == null && android == null) throw ManifestException("The list names neither app.")
        if (desktop != null) {
            if (DesktopVersions.parse(desktop.version) == null) {
                throw ManifestException("The Windows version '${desktop.version.take(40)}' isn't MAJOR.MINOR.BUILD.")
            }
            checkFile(desktop.file, ".msi", desktop.size, desktop.sha256)
        }
        if (android != null) {
            if (!VERSION_NAME.matches(android.versionName)) {
                throw ManifestException("The Android version name '${android.versionName.take(40)}' isn't one Meal Planner uses.")
            }
            if (android.versionCode !in 1L..MAX_VERSION_CODE) throw ManifestException("The Android versionCode ${android.versionCode} is out of range.")
            checkFile(android.file, ".apk", android.size, android.sha256)
        }
    }

    /** The list as the release tool writes it; these exact bytes are what the release key signs. */
    fun toJson(): String {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("tag", tag)
            if (desktop != null) {
                put(
                    "desktop",
                    buildJsonObject {
                        put("version", desktop.version)
                        put("file", desktop.file)
                        put("size", desktop.size)
                        put("sha256", desktop.sha256)
                    },
                )
            }
            if (android != null) {
                put(
                    "android",
                    buildJsonObject {
                        put("versionName", android.versionName)
                        put("versionCode", android.versionCode)
                        put("file", android.file)
                        put("size", android.size)
                        put("sha256", android.sha256)
                    },
                )
            }
        }
        return PRETTY.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    companion object {
        /** The list's format; an app refuses one it doesn't know. Raise it only for a change old apps can't read. */
        const val FORMAT = 1

        /** The most latest.json may be (the brief: 64 KB). */
        const val MAX_BYTES = 64 * 1024

        /** The most an MSI or APK may be (300 MB). */
        const val MAX_FILE_BYTES = 300L * 1024 * 1024

        /** Android's own ceiling for versionCode. */
        const val MAX_VERSION_CODE = 2_100_000_000L

        private val TAG = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        /**
         * The one rule for a release file's name (one path segment, the extension included): what the signed list may
         * name, what MsiInstaller hands over after Updates' prefix, and, as text, release.ps1's check of the staged list
         * (tests/test_release_scripts.py keeps the two the same).
         */
        const val FILE_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}"
        private val FILE = Regex(FILE_PATTERN)
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** The same rule as androidApp/build.gradle.kts's mealplanner.androidVersionName. */
        private val VERSION_NAME = Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,31}")
        private val PRETTY = Json { prettyPrint = true }

        /** Reads latest.json; throws ManifestException for anything that isn't a valid list of format FORMAT. */
        fun parse(text: String): ReleaseManifest {
            val element = try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw ManifestException("latest.json isn't JSON.")
            }
            val root = element as? JsonObject ?: throw ManifestException("latest.json isn't a JSON object.")
            val format = root.long("format")
            if (format != FORMAT.toLong()) throw ManifestException("latest.json is format $format; this app reads format $FORMAT.")
            val tag = root.text("tag") ?: throw ManifestException("latest.json has no tag.")
            val desktop = root.entry("desktop")?.let { d ->
                DesktopRelease(d.need("version"), d.need("file"), d.needLong("size"), d.need("sha256"))
            }
            val android = root.entry("android")?.let { a ->
                AndroidRelease(a.need("versionName"), a.needLong("versionCode"), a.need("file"), a.needLong("size"), a.need("sha256"))
            }
            return ReleaseManifest(tag, desktop, android)
        }

        private fun checkFile(file: String, extension: String, size: Long, sha256: String) {
            if (!FILE.matches(file) || !file.endsWith(extension)) throw ManifestException("The file name '${file.take(40)}' isn't a $extension Meal Planner uses.")
            if (size !in 1L..MAX_FILE_BYTES) throw ManifestException("$file's size $size is out of range.")
            if (!SHA256.matches(sha256)) throw ManifestException("$file's SHA-256 isn't 64 lowercase hex digits.")
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        private fun JsonObject.need(key: String): String = text(key) ?: throw ManifestException("An entry in latest.json has no $key.")

        private fun JsonObject.needLong(key: String): Long = long(key) ?: throw ManifestException("An entry in latest.json has no whole-number $key.")

        // Absent is fine (a list for one app); present, it must be an object.
        private fun JsonObject.entry(key: String): JsonObject? {
            val value = this[key] ?: return null
            return value as? JsonObject ?: throw ManifestException("latest.json's $key isn't an object.")
        }
    }
}
