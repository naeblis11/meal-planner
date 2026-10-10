package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.importing.ImportInbox
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-R3, P4-R4: app.py's recipes_import_extension, staging into the inbox instead of .import_staging.json. */
class ExtensionImportTest {
    private val dir: File = Files.createTempDirectory("mp-extension").toFile()
    private var received = 0
    private var stagings = 0

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun importer(inbox: ImportInbox, fetch: (String) -> ByteArray = { throw IOException("no network in tests") }) = ExtensionImport(
        newStagingDir = { File(dir, "staging-${stagings++}").apply { mkdirs() } },
        fetchImage = fetch,
        inbox = inbox,
        onReceived = { received++ },
        newUuid = { "uuid-1" },
        log = {},
    )

    private fun payload(vararg overrides: Pair<String, Any?>): Map<Any?, Any?> = linkedMapOf<Any?, Any?>(
        "name" to "Extracted Soup",
        "ingredients" to listOf("2 cups flour, sifted", "Kosher salt, to taste"),
        "steps" to listOf("Boil water.", "Add flour."),
        "yield_text" to "4 servings",
        "author" to "Jane Doe",
        "source_url" to "https://www.example.com/soup",
        "image_url" to null,
    ).apply { putAll(overrides) }

    @Test
    fun aCompleteRecipeIsStagedForTheReview() {
        val inbox = ImportInbox()
        assertEquals(JsonReply.ok(), importer(inbox).receive(payload()))
        assertEquals(1, inbox.waiting.value)
        assertEquals(1, received)
        val staged = inbox.take()!!
        assertEquals("www.example.com", staged.bundle.sourceName)
        val recipe = staged.bundle.recipes.single()
        assertEquals("Extracted Soup", recipe.title)
        assertEquals("uuid-1", recipe.doc["recipe_uuid"])
        assertEquals("https://www.example.com/soup", recipe.doc["source_url"])
        assertTrue(staged.bundle.images.isEmpty())
        assertTrue(staged.dir.isDirectory)
    }

    @Test
    fun withNoSourceItSaysBrowserExtension() {
        val inbox = ImportInbox()
        importer(inbox).receive(payload("source_url" to null))
        assertEquals("browser extension", inbox.take()!!.bundle.sourceName)
    }

    @Test
    fun aMissingPartIsRefusedAndNothingIsStaged() {
        val inbox = ImportInbox()
        val missing = JsonReply(400, JsonObject(mapOf("ok" to JsonPrimitive(false), "error" to JsonPrimitive("Missing name, ingredients, or steps."))))
        for (bad in listOf(
            payload("name" to ""),
            payload("ingredients" to emptyList<String>()),
            payload("steps" to emptyList<String>()),
            payload("ingredients" to listOf(5L)),
            emptyMap(),
        )) {
            assertEquals(missing, importer(inbox).receive(bad))
        }
        assertEquals(0, inbox.waiting.value)
        assertEquals(0, received)
        assertEquals(0, stagings)
    }

    @Test
    fun aPhotoIsProcessedIntoTheStagingFolder() {
        val inbox = ImportInbox()
        assertEquals(JsonReply.ok(), importer(inbox) { jpegBytes() }.receive(payload("image_url" to "https://www.example.com/soup.jpg")))
        val staged = inbox.take()!!
        assertEquals("uuid-1.jpg", staged.bundle.recipes.single().doc["image"])
        assertEquals(listOf("uuid-1.jpg", "uuid-1_thumb.jpg"), staged.bundle.images.keys.toList())
        for (file in staged.bundle.images.values) {
            assertTrue(file.isFile)
            assertEquals(staged.dir, file.parentFile)
        }
    }

    @Test
    fun aPhotoThatCantBeHadStillStagesTheRecipeWithAWarning() {
        val inbox = ImportInbox()
        val warned = JsonReply.ok("warning" to "Could not download photo")
        assertEquals(warned, importer(inbox).receive(payload("image_url" to "https://www.example.com/broken.jpg")))
        assertEquals(warned, importer(inbox) { "not a photo".toByteArray() }.receive(payload("image_url" to "https://www.example.com/page")))
        assertEquals(warned, importer(inbox).receive(payload("image_url" to 5L)))
        assertEquals(3, inbox.waiting.value)
        assertFalse(inbox.take()!!.bundle.recipes.single().doc.containsKey("image"))
    }

    @Test
    fun anOverlongAddressOrFieldSaysSoAndStagesNothing() {
        val inbox = ImportInbox()
        assertEquals(
            JsonReply.error(400, ExtensionImport.ADDRESS_TOO_LONG),
            importer(inbox).receive(payload("source_url" to "https://www.example.com/soup?" + "a".repeat(9000))),
        )
        for (field in listOf("yield_text", "author")) {
            assertEquals(field, JsonReply.error(400, ExtensionImport.FIELD_TOO_LONG), importer(inbox).receive(payload(field to "a".repeat(2001))))
        }
        assertEquals("That page's address is too long. Remove the part after ? and send it again.", ExtensionImport.ADDRESS_TOO_LONG)
        assertEquals("That recipe has a field that is too long to import.", ExtensionImport.FIELD_TOO_LONG)
        assertEquals(0, inbox.waiting.value)
        assertEquals(0, stagings)
        assertEquals(0, received)
    }

    @Test
    fun aPhotoFailureIsLoggedByItsClassOnly() {
        // An exception's message may hold the photo's address (what the user browsed): never logged.
        val logs = mutableListOf<String>()
        val importer = ExtensionImport(
            newStagingDir = { File(dir, "staging-${stagings++}").apply { mkdirs() } },
            fetchImage = { throw IOException("https://private.example/secret.jpg") },
            inbox = ImportInbox(),
            onReceived = {},
            newUuid = { "uuid-1" },
            log = { logs += it },
        )
        importer.receive(payload("image_url" to "https://private.example/secret.jpg"))
        assertEquals(1, logs.size)
        assertTrue(logs.single(), logs.single().contains("java.io.IOException"))
        assertFalse(logs.single(), logs.single().contains("secret"))
    }

    @Test
    fun aFullInboxSaysSoAndKeepsNothing() {
        val inbox = ImportInbox(capacity = 1)
        importer(inbox).receive(payload())
        val reply = importer(inbox).receive(payload())
        assertEquals(JsonReply.error(503, ExtensionImport.QUEUE_FULL), reply)
        assertFalse(File(dir, "staging-1").exists())
        assertEquals(1, received)
    }
}
