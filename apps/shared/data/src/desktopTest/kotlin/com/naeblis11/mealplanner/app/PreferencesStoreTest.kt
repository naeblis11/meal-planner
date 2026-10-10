package com.naeblis11.mealplanner.app

import java.util.prefs.Preferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferencesStoreTest {
    private val node = Preferences.userRoot().node("com/naeblis11/mealplanner/test-${System.nanoTime()}")

    @After
    fun tearDown() {
        node.removeNode()
    }

    @Test
    fun keepsLongsAndStringsAndReportsMissingKeysAsNull() {
        val store = PreferencesStore(node)
        assertNull(store.getLong("calendar_id"))
        store.put(mapOf("calendar_id" to 3L, "calendar_name" to "Family"))
        assertEquals(3L, PreferencesStore(node).getLong("calendar_id"))
        assertEquals("Family", PreferencesStore(node).getString("calendar_name"))
    }
}
