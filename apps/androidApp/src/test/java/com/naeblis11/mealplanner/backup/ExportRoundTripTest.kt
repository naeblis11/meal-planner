package com.naeblis11.mealplanner.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.JsonTree
import com.naeblis11.mealplanner.domain.Orf
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Saves every parseable ORF golden case through the repository, exports the library, and parses each exported
 * recipe file again: it must read exactly as the original did (apart from the recipe_uuid the phone assigns).
 * The golden cases (tests/fixtures/parity/orf.json) are the retired Python server's parses, now the reference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportRoundTripTest {
    private fun withoutUuid(parsed: JsonElement): JsonObject =
        JsonObject(parsed.jsonObject.filterKeys { it != "recipe_uuid" })

    @Test
    fun everyExportedRecipeParsesAsItsOriginal() {
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val images = Files.createTempDirectory("images").toFile()
            try {
                val repo = RecipeRepository(db, images)
                val cases = Json.parseToJsonElement(File(System.getProperty("parityDir")!!, "orf.json").readText(Charsets.UTF_8))
                    .jsonArray.filter { it.jsonObject["error"] == null }
                @Suppress("UNCHECKED_CAST")
                repo.saveAll(cases.map { RecipeYaml.load(it.jsonObject["yaml"]!!.jsonPrimitive.content) as YamlMap })

                val out = ByteArrayOutputStream()
                val summary = BackupExporter(repo, images).export(out)
                assertEquals(cases.size, summary.recipes)

                val exported = mutableListOf<JsonObject>()
                ZipInputStream(out.toByteArray().inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name.startsWith("recipes/") && entry.name.endsWith(".yaml")) {
                            val parsed = Orf.parse(zip.readBytes().toString(Charsets.UTF_8))
                            exported += withoutUuid(JsonTree.toJson(parsed))
                        }
                    }
                }
                val originals = cases.map { withoutUuid(it.jsonObject["expected"]!!) }
                // Counted, not sorted: JsonObject equality ignores key order.
                assertEquals(originals.groupingBy { it }.eachCount(), exported.groupingBy { it }.eachCount())
            } finally {
                db.close()
                images.deleteRecursively()
            }
        }
    }
}
