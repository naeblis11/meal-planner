package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.backup.ImportBundle
import com.naeblis11.mealplanner.importing.ImportInbox
import com.naeblis11.mealplanner.importing.PreparedImport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** P4-R4: recipes from the extension wait their turn for the review; a full inbox says no rather than dropping one. */
class ImportInboxTest {
    private fun prepared(name: String) = PreparedImport(ImportBundle(name, emptyList(), emptyMap(), emptyList()), File(name))

    @Test
    fun importsWaitInTheOrderTheyCame() {
        val inbox = ImportInbox()
        val first = prepared("a")
        val second = prepared("b")
        inbox.offer(first)
        inbox.offer(second)
        assertEquals(2, inbox.waiting.value)
        assertSame(first, inbox.take())
        assertSame(second, inbox.take())
        assertNull(inbox.take())
        assertEquals(0, inbox.waiting.value)
    }

    @Test
    fun aFullInboxRefusesMore() {
        val inbox = ImportInbox(capacity = 2)
        inbox.offer(prepared("a"))
        inbox.offer(prepared("b"))
        assertFalse(inbox.offer(prepared("c")))
        assertEquals(2, inbox.waiting.value)
    }
}
