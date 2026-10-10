package com.naeblis11.mealplanner.backup

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImportReaderTest {
    private lateinit var tempRoot: File
    private lateinit var staging: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("import").toFile()
        // Not created yet: read() makes it when a zip has photos.
        staging = File(tempRoot, "staging")
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun read(fileName: String, bytes: ByteArray, maxUncompressedBytes: Long = ImportReader.MAX_UNCOMPRESSED_BYTES) =
        runBlocking { ImportReader.read(fileName, bytes.inputStream(), staging, maxUncompressedBytes) }

    private fun recipe(name: String) = "recipe_name: $name\ningredients:\n- Salt:\nsteps:\n- step: Season.\n".toByteArray()

    @Test
    fun readsOneYamlOrYmlFile() {
        val bundle = read("Soup.YML", recipe("Soup"))
        assertEquals(listOf("Soup"), bundle.recipes.map { it.title })
        assertEquals("Soup", bundle.recipes.single().doc["recipe_name"])
        assertEquals(emptyList<Pair<String, String>>(), bundle.errors)
    }

    @Test
    fun aBadYamlFileBecomesAnError() {
        val missing = read("a.yaml", "recipe_name: A\n".toByteArray())
        assertEquals(emptyList<IncomingRecipe>(), missing.recipes)
        assertEquals("a.yaml" to "Missing required field(s): steps, ingredients", missing.errors.single())

        val notUtf8 = read("b.yaml", byteArrayOf(0xC3.toByte(), 0x28))
        assertEquals("b.yaml", notUtf8.errors.single().first)
    }

    @Test
    fun aByteOrderMarkIsIgnored() {
        val bundle = read("bom.yaml", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + recipe("Bom"))
        assertEquals(listOf("Bom"), bundle.recipes.map { it.title })
    }

    @Test
    fun readsMealMaster() {
        val mmf = "MMMMM----- Recipe\n\n      Title: Rice\n\n      1 c  Rice\n\nCook it.\nMMMMM\n"
        val bundle = read("box.MMF", mmf.toByteArray(Charsets.ISO_8859_1))
        assertEquals(listOf("Rice"), bundle.recipes.map { it.title })
    }

    @Test
    fun rejectsOtherFileTypes() {
        assertThrows(ImportException::class.java) { read("photo.jpg", byteArrayOf(1)) }
    }

    @Test
    fun readsABackupZip() {
        val photo = byteArrayOf(1, 2, 3)
        val bundle = read(
            "backup.zip",
            zipOf(
                "recipes/soup.yaml" to recipe("Soup"),
                "recipes/" to ByteArray(0),
                "cake.yml" to recipe("Cake"),
                "Meal Planner/recipes/stew.yaml" to recipe("Stew"),
                "recipe-images/abc.jpg" to photo,
                "recipe-images/notes.txt" to byteArrayOf(9),
                "recipe-images/bad name.jpg" to photo,
                "__MACOSX/recipes/._soup.yaml" to byteArrayOf(0),
                "readme.txt" to byteArrayOf(9),
            ),
        )
        assertEquals(listOf("Soup", "Cake", "Stew"), bundle.recipes.map { it.title })
        assertEquals(setOf("abc.jpg"), bundle.images.keys)
        assertArrayEquals(photo, bundle.images["abc.jpg"]!!.readBytes())
        assertEquals(emptyList<Pair<String, String>>(), bundle.errors)
    }

    @Test
    fun aZipsPhotoIsStreamedToAFileInTheStagingFolder() {
        val photo = ByteArray(200_000) { (it % 251).toByte() }
        val bundle = read("backup.zip", zipOf("x/recipe-images/p.jpg" to photo, "recipes/soup.yaml" to recipe("Soup")))
        val file = bundle.images.getValue("p.jpg")
        assertEquals(File(staging, "p.jpg"), file)
        assertArrayEquals(photo, file.readBytes())
    }

    @Test
    fun theInputStreamIsLeftOpen() {
        var closed = false
        val input = object : ByteArrayInputStream(zipOf("recipes/soup.yaml" to recipe("Soup"))) {
            override fun close() {
                closed = true
            }
        }
        runBlocking { ImportReader.read("backup.zip", input, staging) }
        assertFalse(closed)
    }

    @Test
    fun refusesASingleFileOverTheLimit() {
        assertThrows(ImportException::class.java) { read("big.yaml", recipe("Big") + ByteArray(2_000) { ' '.code.toByte() }, maxUncompressedBytes = 1_000) }
        assertThrows(ImportException::class.java) { read("big.mmf", ByteArray(2_000) { 'a'.code.toByte() }, maxUncompressedBytes = 1_000) }
    }

    @Test
    fun photosCountTowardTheLimit() {
        val zip = zipOf("recipe-images/big.jpg" to ByteArray(10_000))
        assertThrows(ImportException::class.java) { read("big.zip", zip, maxUncompressedBytes = 1_000) }
    }

    @Test
    fun skipsUnsafePaths() {
        val bundle = read(
            "evil.zip",
            zipOf(
                "../evil.yaml" to recipe("Evil"),
                "/abs.yaml" to recipe("Abs"),
                "C:/drive.yaml" to recipe("Drive"),
                "recipes\\..\\..\\win.yaml" to recipe("Win"),
                "recipes/ok.yaml" to recipe("Ok"),
            ),
        )
        assertEquals(listOf("Ok"), bundle.recipes.map { it.title })
        assertEquals(4, bundle.errors.size)
        assertTrue(bundle.errors.all { it.second.contains("unsafe") })
    }

    @Test
    fun refusesAZipThatUnpacksTooLarge() {
        val zip = zipOf("recipes/big.yaml" to ByteArray(10_000) { 'a'.code.toByte() })
        assertThrows(ImportException::class.java) { read("big.zip", zip, maxUncompressedBytes = 1_000) }
    }

    @Test
    fun aCorruptZipIsAnImportException() {
        val zip = zipOf("recipes/soup.yaml" to recipe("Soup"))
        assertThrows(ImportException::class.java) { read("cut.zip", zip.copyOf(zip.size / 2)) }
    }

    @Test
    fun aFileThatIsNotAZipIsAnImportException() {
        assertThrows(ImportException::class.java) { read("empty.zip", byteArrayOf()) }
        assertThrows(ImportException::class.java) { read("notzip.zip", "not a zip".toByteArray()) }
    }
}
