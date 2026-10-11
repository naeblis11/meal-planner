package com.naeblis11.mealplanner.app

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * LAN sync 2c: Google's code scanner (play-services-code-scanner) brings ML Kit's usage reporting with it, Google's
 * datatransport pipeline (its CCT backend, scheduler service and alarm receiver). The phone's promise is that only
 * ReleaseHttp and SyncHttp go online, so AndroidManifest.xml removes every one of them, and this fails if the merge
 * ever brings one (or another of its logging backends) back. The scanner's own ML Kit init stays: it runs nothing
 * online and the scanner needs it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestTelemetryTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    // The reporting pipeline's packages, matched anywhere in a name or value: a registration such as
    // "com.google.firebase.components:com.google.firebase.datatransport.TransportRegistrar" carries one after a prefix.
    private val reporting = listOf(
        "com.google.android.datatransport",
        "com.google.firebase.datatransport",
        "com.google.android.gms.measurement",
        "com.google.firebase.analytics",
    )

    @Test
    fun noUsageReportingComponentIsMergedIn() {
        // The merged manifest AGP hands Robolectric (com/android/tools/test_config.properties), as BackupRulesTest.
        val config = Properties().apply {
            ManifestTelemetryTest::class.java.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties").use { load(it) }
        }
        val manifest = File(config.getProperty("android_merged_manifest"))
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val found = mutableListOf<String>()
        for (tag in listOf("activity", "activity-alias", "service", "receiver", "provider", "meta-data")) {
            val nodes = document.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                val values = listOf(element.getAttributeNS(androidNs, "name"), element.getAttributeNS(androidNs, "value"))
                if (values.any { value -> reporting.any { value.contains(it) } }) found += "$tag ${values.first()}"
            }
        }
        assertEquals(emptyList<String>(), found)
        // The scanner itself is still there (its delegate activity), so the removal took only the reporting.
        val activities = document.getElementsByTagName("activity")
        val names = (0 until activities.length).map { (activities.item(it) as Element).getAttributeNS(androidNs, "name") }
        assertTrue(names.toString(), "com.google.mlkit.vision.codescanner.internal.GmsBarcodeScanningDelegateActivity" in names)
    }
}
