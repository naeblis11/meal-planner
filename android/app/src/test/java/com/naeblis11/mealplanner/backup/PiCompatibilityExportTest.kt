package com.naeblis11.mealplanner.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.ParityFixtures
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.domain.obj
import com.naeblis11.mealplanner.domain.str
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Saves every parseable ORF parity case through the repository and exports
 * the library to build/compat/export.zip, for tests/check_android_export.py
 * to read with the Pi's own parser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PiCompatibilityExportTest {
    @Test
    fun writesAnExportForThePiToCheck() {
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val images = Files.createTempDirectory("images").toFile()
            try {
                val repo = RecipeRepository(db, images)
                val yamls = ParityFixtures.load("orf.json").jsonArray
                    .filter { it.obj["error"] == null }
                    .map { it.obj["yaml"]!!.str()!! }
                @Suppress("UNCHECKED_CAST")
                repo.saveAll(yamls.map { RecipeYaml.load(it) as YamlMap })

                val outDir = File(System.getProperty("compatOut")!!).apply { mkdirs() }
                val summary = File(outDir, "export.zip").outputStream().use { BackupExporter(repo, images).export(it) }
                assertEquals(yamls.size, summary.recipes)
            } finally {
                db.close()
                images.deleteRecursively()
            }
        }
    }
}
