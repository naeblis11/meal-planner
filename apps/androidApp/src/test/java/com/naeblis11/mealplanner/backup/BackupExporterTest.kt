package com.naeblis11.mealplanner.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupExporterTest {
    private lateinit var db: AppDatabase
    private lateinit var images: File
    private lateinit var staging: File
    private lateinit var repo: RecipeRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        images = Files.createTempDirectory("images").toFile()
        staging = Files.createTempDirectory("staging").toFile()
        repo = RecipeRepository(db, images)
    }

    @After
    fun tearDown() {
        db.close()
        images.deleteRecursively()
        staging.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun doc(yaml: String) = RecipeYaml.load(yaml) as YamlMap

    private fun entries(zip: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return out
    }

    @Test
    fun exportsYamlAndPhotosInThePiLayout() {
        runBlocking {
            File(images, "a.jpg").writeText("A")
            File(images, "a_thumb.jpg").writeText("a")
            File(images, "s.jpg").writeText("S")
            repo.save(doc("recipe_name: Soup\nimage: a.jpg\ningredients: []\nsteps: []\n"))
            repo.save(doc("recipe_name: soup\nimage: s.jpg\ningredients: []\nsteps: []\n"))
            repo.save(doc("recipe_name: Stew\nimage: s.jpg\ningredients: []\nsteps: []\n"))
            repo.save(doc("recipe_name: Toast\nimage: ../escape.jpg\ningredients: []\nsteps: []\n"))

            val out = ByteArrayOutputStream()
            val summary = BackupExporter(repo, images).export(out)
            val zip = entries(out.toByteArray())

            assertEquals(ExportSummary(recipes = 4, images = 3), summary)
            assertEquals(
                setOf(
                    "recipes/soup.yaml", "recipes/soup-2.yaml", "recipes/stew.yaml", "recipes/toast.yaml",
                    "recipe-images/a.jpg", "recipe-images/a_thumb.jpg", "recipe-images/s.jpg",
                ),
                zip.keys,
            )
            val soupRow = repo.allRecipes().first { it.name == "Soup" }
            assertEquals(soupRow.rawYaml, String(zip["recipes/soup.yaml"]!!, Charsets.UTF_8))
        }
    }

    @Test
    fun anExportReimportsAsUpdates() {
        runBlocking {
            File(images, "a.jpg").writeText("A")
            repo.save(doc("recipe_name: Soup\nimage: a.jpg\ningredients:\n- Salt:\nsteps:\n- step: Stir.\n"))
            val out = ByteArrayOutputStream()
            BackupExporter(repo, images).export(out)

            val bundle = ImportReader.read("backup.zip", out.toByteArray().inputStream(), staging)
            assertEquals(repo.doc(repo.allRecipes().single().id), bundle.recipes.single().doc)
            assertEquals(setOf("a.jpg"), bundle.images.keys)
            assertEquals(StagedKind.UPDATE, ImportStager.stage(bundle, repo).recipes.single().kind)
        }
    }

    @Test
    fun aFailedExportClosesTheStreamWithoutFinishingTheZip() {
        runBlocking {
            File(images, "a.jpg").writeText("A")
            repo.save(doc("recipe_name: Soup\nimage: a.jpg\ningredients: []\nsteps: []\n"))

            val closeCount = AtomicInteger(0)
            val capturedBytes = ByteArrayOutputStream()
            val trackingStream = object : FilterOutputStream(capturedBytes) {
                override fun close() {
                    closeCount.incrementAndGet()
                    super.close()
                }
            }

            // Close the database to cause allRecipes() to throw
            db.close()

            // Now try to export - it should throw
            var threw = false
            try {
                BackupExporter(repo, images).export(trackingStream)
            } catch (e: Exception) {
                threw = true
            }
            assertEquals(true, threw)

            // Verify close was called exactly once
            assertEquals(1, closeCount.get())

            // Verify the zip is not finished - ZipInputStream should find no entries
            val zippedEntries = entries(capturedBytes.toByteArray())
            assertEquals(emptySet<String>(), zippedEntries.keys)
        }
    }
}
