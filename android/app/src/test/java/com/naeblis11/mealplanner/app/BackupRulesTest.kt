package com.naeblis11.mealplanner.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.R
import com.naeblis11.mealplanner.calendar.CalendarChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android 12+ copies app data to a new phone even with allowBackup off. The database (whose
 * calendar_event ids are the old phone's provider row ids) and the calendar choice must not
 * travel: on the new phone those ids could name the family's own events.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRulesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun theMergedManifestPointsAtTheDataExtractionRules() {
        // The merged manifest AGP hands Robolectric (com/android/tools/test_config.properties).
        val config = Properties().apply {
            BackupRulesTest::class.java.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties").use { load(it) }
        }
        val manifest = File(config.getProperty("android_merged_manifest"))
        val application = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest).getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttributeNS(androidNs, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(androidNs, "dataExtractionRules"))
    }

    @Test
    fun theDatabaseAndCalendarChoiceStayOutOfCloudBackupAndDeviceTransfer() {
        val excluded = mutableMapOf<String, MutableSet<String>>()
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        var section: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "exclude" -> excluded.getOrPut(section!!) { mutableSetOf() } +=
                    parser.getAttributeValue(null, "domain") + ":" + parser.getAttributeValue(null, "path")
            }
        }
        val expected = setOf("database:.", "sharedpref:${CalendarChoice.FILE}.xml")
        assertEquals(setOf("cloud-backup", "device-transfer"), excluded.keys)
        excluded.forEach { (where, rules) -> assertTrue("$where: $rules", rules.containsAll(expected)) }
    }
}
