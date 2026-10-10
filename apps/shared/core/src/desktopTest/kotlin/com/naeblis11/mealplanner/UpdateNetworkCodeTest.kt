package com.naeblis11.mealplanner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec "Updates" and the Decisions table's Android row: the phone talks only to GitHub for updates (and, in phase 2,
 * to the paired PC and Google). So in code the phone runs (commonMain and androidMain of shared/core, shared/data and
 * shared/ui, and androidApp's main), only update/ReleaseHttp.kt may open a connection; its allowlist is pinned by
 * ReleaseHttpTest and AndroidUpdatesTest.
 */
class UpdateNetworkCodeTest {
    @Test
    fun onlyTheUpdateClientGoesOnlineInCodeThePhoneRuns() {
        val root = File(System.getProperty("srcDir") ?: error("srcDir is not set; run through Gradle"))
        val network = Regex(
            """^\s*import\s+(java\.net\..+|javax\.net\..+|okhttp3\..+|io\.ktor\.client\..+)\s*$|\.openConnection\(|\.openStream\(|\bjava\.net\.(URL|Socket|HttpURLConnection)\b|\bDownloadManager\b|\bWebView\b""",
        )
        val shipped = Regex("""^(shared/[a-z]+/src/(commonMain|androidMain)|androidApp/src/(?!test/|androidTest/)[^/]+)/""")
        val allowed = "shared/data/src/commonMain/kotlin/com/naeblis11/mealplanner/update/ReleaseHttp.kt"
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.relativeTo(root).invariantSeparatorsPath }
            .filter { (_, path) -> shipped.containsMatchIn(path) && path != allowed }
            .filter { (file, _) -> file.readLines(Charsets.UTF_8).any { network.containsMatchIn(it) } }
            .map { it.second }
            .toList()
        assertEquals("Only update/ReleaseHttp.kt may go online in code the phone runs", emptyList<String>(), offenders)
        assertTrue("ReleaseHttp.kt has moved: update this test", File(root, allowed).isFile)
    }
}
