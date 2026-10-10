package com.naeblis11.mealplanner.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.SubmittedRow
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportStagingTest {
    private lateinit var db: AppDatabase
    private lateinit var images: File
    private lateinit var staging: File
    private lateinit var repo: RecipeRepository
    private lateinit var committer: ImportCommitter
    private var uuids = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        images = Files.createTempDirectory("images").toFile()
        staging = Files.createTempDirectory("staging").toFile()
        val ids = { "uuid-${++uuids}" }
        repo = RecipeRepository(db, images, ids)
        committer = ImportCommitter(repo, images, ids)
    }

    @After
    fun tearDown() {
        db.close()
        images.deleteRecursively()
        staging.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun doc(yaml: String) = RecipeYaml.load(yaml) as YamlMap

    private fun recipeYaml(name: String, uuid: String? = null, image: String? = null, amount: String = "1") =
        (uuid?.let { "recipe_uuid: $it\n" } ?: "") + "recipe_name: $name\n" + (image?.let { "image: $it\n" } ?: "") +
            "ingredients:\n- Rice:\n    amounts:\n    - amount: '$amount'\n      unit: cup\nsteps:\n- step: Cook.\n"

    // Photos are files in the staging folder, as ImportReader leaves them.
    private fun bundle(vararg yamls: String, images: Map<String, ByteArray> = emptyMap()) = ImportBundle(
        "test.zip",
        yamls.map { y -> doc(y).let { IncomingRecipe(it["recipe_name"] as String, it) } },
        images.mapValues { (name, bytes) -> File(staging, name).apply { writeBytes(bytes) } },
        listOf("broken.yaml" to "Missing required field(s): steps"),
    )

    @Test
    fun stagingSortsNewDuplicateAndUpdate() {
        runBlocking {
            repo.save(doc(recipeYaml("Soup", uuid = "known")))
            repo.save(doc(recipeYaml("Stew")))
            val staged = ImportStager.stage(
                bundle(
                    recipeYaml("Soup v2", uuid = "known"),
                    recipeYaml("STEW"),
                    recipeYaml("Rice", amount = "a handful"),
                    recipeYaml("rice"),
                    recipeYaml("Copy", uuid = "same"),
                    recipeYaml("Copy two", uuid = "same"),
                ),
                repo,
            )
            assertEquals(
                listOf(StagedKind.UPDATE, StagedKind.DUPLICATE, StagedKind.NEW, StagedKind.DUPLICATE, StagedKind.NEW, StagedKind.NEW),
                staged.recipes.map { it.kind },
            )
            assertEquals(listOf(0, 0, 1, 0, 0, 0), staged.recipes.map { it.amountIssues })
            assertNull(staged.recipes[5].doc["recipe_uuid"])
            assertEquals(listOf("broken.yaml" to "Missing required field(s): steps"), staged.errors)
        }
    }

    @Test
    fun confirmImportsUpdatesAndIgnores() {
        runBlocking {
            val existingId = repo.save(doc(recipeYaml("Soup", uuid = "known")))
            repo.save(doc(recipeYaml("Stew")))
            val staged = ImportStager.stage(bundle(recipeYaml("Soup", uuid = "known", amount = "2"), recipeYaml("Stew"), recipeYaml("Rice")), repo)

            val outcome = committer.confirm(
                staged,
                listOf(
                    ImportDecision(0, ImportAction.UPDATE, "Soup"),
                    ImportDecision(1, ImportAction.IMPORT, "stew"),
                    ImportDecision(2, ImportAction.IMPORT, "Rice", category = "Side Dishes", servingsAmount = "4"),
                ),
            )

            assertEquals(ImportOutcome(1, 1, listOf("Stew"), staged.errors), outcome)
            assertEquals(listOf("Rice", "Soup", "Stew"), repo.allRecipes().map { it.name })
            assertEquals("2", db.recipeDao().ingredients(existingId).single().amount)
            val rice = repo.allRecipes().first { it.name == "Rice" }
            assertEquals("Side Dishes", rice.category)
            assertEquals("None", repo.doc(rice.id)!!["subcategory"])
            assertEquals("""[{"amount":4,"unit":"servings"}]""", rice.yieldsJson)
        }
    }

    @Test
    fun aRenamedDuplicateIsANewRecipeWithItsOwnPhoto() {
        runBlocking {
            repo.save(doc(recipeYaml("Stew", uuid = "old")))
            val photo = byteArrayOf(1, 2)
            val thumb = byteArrayOf(3)
            val staged = ImportStager.stage(
                bundle(recipeYaml("Stew", uuid = "other", image = "p.jpg"), images = mapOf("p.jpg" to photo, "p_thumb.jpg" to thumb)),
                repo,
            )
            assertEquals(StagedKind.DUPLICATE, staged.recipes.single().kind)

            committer.confirm(staged, listOf(ImportDecision(0, ImportAction.IMPORT, "Stew (Pi)")))

            val copy = repo.allRecipes().first { it.name == "Stew (Pi)" }
            assertTrue(copy.recipeUuid.startsWith("uuid-"))
            assertEquals("${copy.recipeUuid}.jpg", copy.imageFilename)
            assertArrayEquals(photo, File(images, "${copy.recipeUuid}.jpg").readBytes())
            assertArrayEquals(thumb, File(images, "${copy.recipeUuid}_thumb.jpg").readBytes())
        }
    }

    @Test
    fun aMissingPhotoIsDroppedFromTheRecipe() {
        runBlocking {
            val staged = ImportStager.stage(bundle(recipeYaml("Rice", image = "gone.jpg")), repo)
            committer.confirm(staged, listOf(ImportDecision(0, ImportAction.IMPORT, "Rice")))
            val rice = repo.allRecipes().single()
            assertNull(rice.imageFilename)
            assertFalse(repo.doc(rice.id)!!.containsKey("image"))
        }
    }

    @Test
    fun ingredientRowsReplaceTheIngredients() {
        runBlocking {
            val staged = ImportStager.stage(bundle(recipeYaml("Rice", amount = "a handful")), repo)
            committer.confirm(
                staged,
                listOf(ImportDecision(0, ImportAction.IMPORT, "Rice", ingredientRows = listOf(SubmittedRow("e0", SubmittedRow.INGREDIENT, "Rice", "250", "ml")))),
            )
            val row = db.recipeDao().ingredients(repo.allRecipes().single().id).single()
            assertEquals("1 1/24" to "cup", row.amount to row.unit)
        }
    }

    @Test
    fun aNameClashWritesNothing() {
        runBlocking {
            repo.save(doc(recipeYaml("Stew")))
            File(images, "keep.txt").writeText("x")
            val staged = ImportStager.stage(
                bundle(recipeYaml("Rice", image = "p.jpg"), recipeYaml("Beans"), images = mapOf("p.jpg" to byteArrayOf(1))),
                repo,
            )
            val error = assertThrows(ImportValidationException::class.java) {
                runBlocking {
                    committer.confirm(
                        staged,
                        listOf(ImportDecision(0, ImportAction.IMPORT, "Rice"), ImportDecision(1, ImportAction.IMPORT, "stew")),
                    )
                }
            }
            assertEquals("Can't import 'Beans' as 'stew': that name is already in your library. Pick another name.", error.message)
            assertEquals(listOf("Stew"), repo.allRecipes().map { it.name })
            assertEquals(setOf("keep.txt"), images.list()!!.toSet())
        }
    }

    @Test
    fun confirmingTheSameImportTwiceIsRefused() {
        runBlocking {
            val staged = ImportStager.stage(bundle(recipeYaml("Rice"), recipeYaml("Beans")), repo)
            val decisions = listOf(ImportDecision(0, ImportAction.IMPORT, "Rice"), ImportDecision(1, ImportAction.IMPORT, "Beans"))
            assertEquals(2, committer.confirm(staged, decisions).imported)

            val error = assertThrows(ImportValidationException::class.java) {
                runBlocking { committer.confirm(staged, decisions) }
            }
            assertEquals(
                "'Rice' is already in your library. The library changed since this import was read; read the file again.",
                error.message,
            )
            assertEquals(listOf("Beans", "Rice"), repo.allRecipes().map { it.name })
        }
    }

    @Test
    fun anEmptyIngredientListIsRefused() {
        runBlocking {
            val staged = ImportStager.stage(bundle(recipeYaml("Rice")), repo)
            val error = assertThrows(ImportValidationException::class.java) {
                runBlocking {
                    committer.confirm(staged, listOf(ImportDecision(0, ImportAction.IMPORT, "Rice", ingredientRows = emptyList())))
                }
            }
            assertEquals("'Rice' needs at least one ingredient.", error.message)
        }
    }

    @Test
    fun skippedAndUndecidedRecipesAreIgnored() {
        runBlocking {
            val staged = ImportStager.stage(bundle(recipeYaml("Rice"), recipeYaml("Beans")), repo)
            val outcome = committer.confirm(staged, listOf(ImportDecision(0, ImportAction.SKIP, "Rice")))
            assertEquals(listOf("Rice", "Beans"), outcome.ignored)
            assertEquals(emptyList<Any>(), repo.allRecipes())
        }
    }

    @Test
    fun anUpdateCannotTakeAnotherRecipesName() {
        runBlocking {
            repo.save(doc(recipeYaml("Soup", uuid = "known")))
            repo.save(doc(recipeYaml("Stew")))
            val staged = ImportStager.stage(bundle(recipeYaml("Stew", uuid = "known")), repo)
            assertEquals(StagedKind.UPDATE, staged.recipes.single().kind)
            assertThrows(ImportValidationException::class.java) {
                runBlocking { committer.confirm(staged, listOf(ImportDecision(0, ImportAction.UPDATE, "Stew"))) }
            }
            assertEquals(listOf("Soup", "Stew"), repo.allRecipes().map { it.name })
        }
    }

    @Test
    fun aNewRecipesPhotoNeverOverwritesAnotherRecipesPhoto() {
        runBlocking {
            val mine = byteArrayOf(9, 9, 9)
            val myThumb = byteArrayOf(8)
            File(images, "photo.jpg").writeBytes(mine)
            File(images, "photo_thumb.jpg").writeBytes(myThumb)
            repo.save(doc(recipeYaml("Stew", uuid = "stew", image = "photo.jpg")))
            val theirs = byteArrayOf(1, 2)
            val staged = ImportStager.stage(
                bundle(recipeYaml("Rice", uuid = "rice", image = "photo.jpg"), images = mapOf("photo.jpg" to theirs, "photo_thumb.jpg" to byteArrayOf(3))),
                repo,
            )
            assertEquals(StagedKind.NEW, staged.recipes.single().kind)

            committer.confirm(staged, listOf(ImportDecision(0, ImportAction.IMPORT, "Rice")))

            assertArrayEquals(mine, File(images, "photo.jpg").readBytes())
            assertArrayEquals(myThumb, File(images, "photo_thumb.jpg").readBytes())
            val rice = repo.allRecipes().first { it.name == "Rice" }
            assertEquals("rice", rice.recipeUuid)
            assertEquals("rice.jpg", rice.imageFilename)
            assertArrayEquals(theirs, File(images, "rice.jpg").readBytes())
            assertArrayEquals(byteArrayOf(3), File(images, "rice_thumb.jpg").readBytes())
            assertEquals("photo.jpg", repo.allRecipes().first { it.name == "Stew" }.imageFilename)
        }
    }

    @Test
    fun aNewRecipeWithoutAUuidGetsOneToNameItsPhoto() {
        runBlocking {
            File(images, "photo.jpg").writeBytes(byteArrayOf(9))
            val staged = ImportStager.stage(bundle(recipeYaml("Rice", image = "photo.jpg"), images = mapOf("photo.jpg" to byteArrayOf(1))), repo)
            committer.confirm(staged, listOf(ImportDecision(0, ImportAction.IMPORT, "Rice")))
            val rice = repo.allRecipes().single()
            assertEquals("${rice.recipeUuid}.jpg", rice.imageFilename)
            assertArrayEquals(byteArrayOf(9), File(images, "photo.jpg").readBytes())
            assertArrayEquals(byteArrayOf(1), File(images, rice.imageFilename!!).readBytes())
        }
    }

    @Test
    fun anUpdateMayReplaceItsOwnPhoto() {
        runBlocking {
            File(images, "photo.jpg").writeBytes(byteArrayOf(9))
            val id = repo.save(doc(recipeYaml("Soup", uuid = "known", image = "photo.jpg")))
            val staged = ImportStager.stage(bundle(recipeYaml("Soup", uuid = "known", image = "photo.jpg"), images = mapOf("photo.jpg" to byteArrayOf(1))), repo)
            committer.confirm(staged, listOf(ImportDecision(0, ImportAction.UPDATE, "Soup")))
            assertEquals("photo.jpg", db.recipeDao().recipe(id)!!.imageFilename)
            assertArrayEquals(byteArrayOf(1), File(images, "photo.jpg").readBytes())
        }
    }

    @Test
    fun aDatabaseFailureRollsBackThePhotos() {
        runBlocking {
            File(images, "photo.jpg").writeBytes(byteArrayOf(9))
            repo.save(doc(recipeYaml("Soup", uuid = "known", image = "photo.jpg")))
            val staged = ImportStager.stage(
                bundle(
                    recipeYaml("Soup", uuid = "known", image = "photo.jpg"),
                    recipeYaml("Rice", uuid = "rice", image = "r.jpg"),
                    images = mapOf("photo.jpg" to byteArrayOf(1), "r.jpg" to byteArrayOf(2)),
                ),
                repo,
            )
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_insert BEFORE INSERT ON recipe BEGIN SELECT RAISE(ABORT, 'disk full'); END",
            )
            assertThrows(Exception::class.java) {
                runBlocking {
                    committer.confirm(staged, listOf(ImportDecision(0, ImportAction.UPDATE, "Soup"), ImportDecision(1, ImportAction.IMPORT, "Rice")))
                }
            }
            assertArrayEquals(byteArrayOf(9), File(images, "photo.jpg").readBytes())
            assertEquals(setOf("photo.jpg"), images.list()!!.toSet())
            assertEquals(listOf("Soup"), repo.allRecipes().map { it.name })
        }
    }

    @Test
    fun aRenamedUpdateKeepsItsUuid() {
        runBlocking {
            val id = repo.save(doc(recipeYaml("Soup", uuid = "known")))
            val staged = ImportStager.stage(bundle(recipeYaml("Soup", uuid = "known", amount = "2")), repo)
            committer.confirm(staged, listOf(ImportDecision(0, ImportAction.UPDATE, "Better soup")))
            val row = repo.allRecipes().single()
            assertEquals(id to "known", row.id to row.recipeUuid)
            assertEquals("Better soup", row.name)
        }
    }

    @Test
    fun anUpdateMayKeepItsOwnName() {
        runBlocking {
            val id = repo.save(doc(recipeYaml("Soup", uuid = "known")))
            val staged = ImportStager.stage(bundle(recipeYaml("Soup v2", uuid = "known")), repo)
            committer.confirm(staged, listOf(ImportDecision(0, ImportAction.UPDATE, "Soup")))
            val all = repo.allRecipes()
            assertEquals(listOf("Soup"), all.map { it.name })
            assertEquals(id, all.single().id)
        }
    }

    @Test
    fun anUpdatedPhotoLeavesNoCopyBehind() {
        runBlocking {
            File(images, "photo.jpg").writeBytes(byteArrayOf(9))
            repo.save(doc(recipeYaml("Soup", uuid = "known", image = "photo.jpg")))
            val staged = ImportStager.stage(bundle(recipeYaml("Soup", uuid = "known", image = "photo.jpg"), images = mapOf("photo.jpg" to byteArrayOf(1))), repo)
            committer.confirm(staged, listOf(ImportDecision(0, ImportAction.UPDATE, "Soup")))
            assertArrayEquals(byteArrayOf(1), File(images, "photo.jpg").readBytes())
            assertEquals(setOf("photo.jpg"), images.list()!!.toSet())
        }
    }
}
