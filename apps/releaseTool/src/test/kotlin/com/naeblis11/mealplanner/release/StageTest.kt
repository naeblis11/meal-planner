package com.naeblis11.mealplanner.release

import com.naeblis11.mealplanner.update.AndroidRelease
import com.naeblis11.mealplanner.update.DesktopRelease
import com.naeblis11.mealplanner.update.ManifestException
import com.naeblis11.mealplanner.update.ReleaseManifest
import com.naeblis11.mealplanner.update.Sha256
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTest {
    private val dir: File = Files.createTempDirectory("mp-release-stage").toFile()
    private val props = File(dir, "gradle.properties").apply {
        writeText("mealplanner.desktopVersion=1.0.1\nmealplanner.androidVersionCode=2\nmealplanner.androidVersionName=1.0.1\n")
    }
    private val msi = File(dir, "Meal Planner-1.0.1.msi").apply { writeBytes(ByteArray(5_000) { 1 }) }
    private val apk = File(dir, "androidApp-release.apk").apply { writeBytes(ByteArray(3_000) { 2 }) }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun itCopiesTheFilesUnderTheirReleaseNamesAndListsThem() {
        val out = File(dir, "stage")
        val manifest = Stage.stage(props, msi, apk, out)
        assertEquals(setOf("MealPlanner-1.0.1.msi", "MealPlanner-1.0.1.apk", "latest.json"), out.list()!!.toSet())
        val read = ReleaseManifest.parse(File(out, "latest.json").readText())
        assertEquals(manifest, read)
        assertEquals("release-2", read.tag)
        assertEquals(DesktopRelease("1.0.1", "MealPlanner-1.0.1.msi", 5_000, Sha256.of(msi)), read.desktop)
        assertEquals(AndroidRelease("1.0.1", 2, "MealPlanner-1.0.1.apk", 3_000, Sha256.of(apk)), read.android)
        assertArrayEquals(msi.readBytes(), File(out, "MealPlanner-1.0.1.msi").readBytes())
        assertArrayEquals(apk.readBytes(), File(out, "MealPlanner-1.0.1.apk").readBytes())
    }

    @Test
    fun aMissingFileABadVersionOrAFullFolderStops() {
        assertThrows(ReleaseToolException::class.java) { Stage.stage(props, File(dir, "none.msi"), apk, File(dir, "a")) }
        assertThrows(ReleaseToolException::class.java) { Stage.stage(props, msi, File(dir, "none.apk"), File(dir, "b")) }
        val bad = File(dir, "bad.properties").apply {
            writeText("mealplanner.desktopVersion=1.0\nmealplanner.androidVersionCode=2\nmealplanner.androidVersionName=1.0.1\n")
        }
        assertThrows(ManifestException::class.java) { Stage.stage(bad, msi, apk, File(dir, "c")) }
        assertFalse(File(dir, "c").exists())
        val full = File(dir, "d").apply { mkdirs(); File(this, "left.txt").writeText("x") }
        assertThrows(ReleaseToolException::class.java) { Stage.stage(props, msi, apk, full) }
    }

    @Test
    fun theListNamesTheStagedCopiesNotTheSources() {
        // A copy that comes out different (here, one byte longer) is what gets published, so it is what is listed.
        val out = File(dir, "stage")
        val manifest = Stage.stage(props, msi, apk, out) { from, to -> to.writeBytes(from.readBytes() + 9) }
        val stagedMsi = File(out, "MealPlanner-1.0.1.msi")
        val stagedApk = File(out, "MealPlanner-1.0.1.apk")
        assertEquals(DesktopRelease("1.0.1", stagedMsi.name, 5_001, Sha256.of(stagedMsi)), manifest.desktop)
        assertEquals(AndroidRelease("1.0.1", 2, stagedApk.name, 3_001, Sha256.of(stagedApk)), manifest.android)
        assertEquals(manifest, ReleaseManifest.parse(File(out, "latest.json").readText()))
    }

    @Test
    fun aFailedCopyTakesBackOnlyWhatThisRunMade() {
        val failing: (File, File) -> Unit = { from, to ->
            if (from == apk) {
                to.writeBytes(ByteArray(10))
                throw java.io.IOException("disk full")
            }
            from.copyTo(to)
        }
        // A folder this run made (and its new parent) goes again.
        val made = File(dir, "new/stage")
        val e = assertThrows(ReleaseToolException::class.java) { Stage.stage(props, msi, apk, made, failing) }
        assertTrue(e.message, e.message!!.contains("disk full") && e.message!!.contains("Removed"))
        assertFalse(File(dir, "new").exists())
        // A folder that was already there (empty) stays, and is empty again.
        val kept = File(dir, "kept").apply { mkdirs() }
        assertThrows(ReleaseToolException::class.java) { Stage.stage(props, msi, apk, kept, failing) }
        assertTrue(kept.isDirectory)
        assertEquals(0, kept.list()!!.size)
        // The sources are never touched.
        assertTrue(msi.isFile && apk.isFile)
    }
}
