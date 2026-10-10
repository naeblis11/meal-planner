package com.naeblis11.mealplanner.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseManifestTest {
    private val sha = "0123456789abcdef".repeat(4)
    private val manifest = ReleaseManifest(
        tag = "release-2",
        desktop = DesktopRelease("1.0.1", "MealPlanner-1.0.1.msi", 94_000_000, sha),
        android = AndroidRelease("1.0.1", 2, "MealPlanner-1.0.1.apk", 12_000_000, sha),
    )

    @Test
    fun whatTheWriterWritesTheReaderReads() {
        assertEquals(manifest, ReleaseManifest.parse(manifest.toJson()))
    }

    @Test
    fun itReadsTheFormatAndIgnoresFieldsItDoesntKnow() {
        val text = """{"format": 1, "tag": "release-2", "notes": "ignored",
            "desktop": {"version": "1.0.1", "file": "MealPlanner-1.0.1.msi", "size": 94000000, "sha256": "$sha", "extra": true},
            "android": {"versionName": "1.0.1", "versionCode": 2, "file": "MealPlanner-1.0.1.apk", "size": 12000000, "sha256": "$sha"}}"""
        assertEquals(manifest, ReleaseManifest.parse(text))
    }

    @Test
    fun oneAppAloneIsAList() {
        val onlyAndroid = manifest.copy(desktop = null)
        assertEquals(onlyAndroid, ReleaseManifest.parse(onlyAndroid.toJson()))
    }

    @Test
    fun anEntryThatIsWrongInAnyWayIsRefused() {
        val d = manifest.desktop!!
        val a = manifest.android!!
        val cases: List<() -> Any> = listOf(
            { manifest.copy(tag = "") },
            { manifest.copy(tag = "release 2") },
            { manifest.copy(tag = "../release-2") },
            { manifest.copy(desktop = null, android = null) },
            { manifest.copy(desktop = d.copy(file = "../MealPlanner.msi")) },
            { manifest.copy(desktop = d.copy(file = "dir\\MealPlanner.msi")) },
            { manifest.copy(desktop = d.copy(file = "dir/MealPlanner.msi")) },
            { manifest.copy(desktop = d.copy(file = "MealPlanner-1.0.1.exe")) },
            { manifest.copy(android = a.copy(file = "MealPlanner-1.0.1.msi")) },
            { manifest.copy(desktop = d.copy(size = 0)) },
            { manifest.copy(desktop = d.copy(size = ReleaseManifest.MAX_FILE_BYTES + 1)) },
            { manifest.copy(desktop = d.copy(sha256 = sha.uppercase())) },
            { manifest.copy(desktop = d.copy(sha256 = sha.dropLast(1))) },
            { manifest.copy(desktop = d.copy(version = "1.0")) },
            { manifest.copy(desktop = d.copy(version = "0.9.0")) },
            { manifest.copy(android = a.copy(versionCode = 0)) },
            { manifest.copy(android = a.copy(versionCode = ReleaseManifest.MAX_VERSION_CODE + 1)) },
            { manifest.copy(android = a.copy(versionName = "")) },
            { manifest.copy(android = a.copy(versionName = "1.0 beta")) },
        )
        cases.forEachIndexed { i, case -> assertThrows("case $i", ManifestException::class.java) { case() } }
    }

    @Test
    fun aListThatIsntOneIsRefused() {
        val good = """{"version": "1.0.1", "file": "MealPlanner-1.0.1.msi", "size": 94000000, "sha256": "$sha"}"""
        val texts = listOf(
            "not json",
            "[]",
            "{}",
            """{"format": 2, "tag": "release-2", "desktop": $good}""",
            """{"format": "1", "tag": "release-2", "desktop": $good}""",
            """{"format": 1, "tag": "release-2", "desktop": "MealPlanner.msi"}""",
            """{"format": 1, "tag": "release-2", "desktop": ${good.replace("94000000", "\"94000000\"")}}""",
            """{"format": 1, "tag": "release-2", "desktop": ${good.replace("\"file\": \"MealPlanner-1.0.1.msi\", ", "")}}""",
        )
        for (text in texts) assertThrows(text, ManifestException::class.java) { ReleaseManifest.parse(text) }
    }
}
