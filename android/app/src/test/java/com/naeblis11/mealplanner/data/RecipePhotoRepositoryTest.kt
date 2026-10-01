package com.naeblis11.mealplanner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecipePhotoRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var images: File
    private lateinit var repo: RecipeRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        images = Files.createTempDirectory("images").toFile()
        repo = RecipeRepository(db, images)
    }

    @After
    fun tearDown() {
        db.close()
        images.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun doc(yaml: String) = RecipeYaml.load(yaml) as YamlMap

    @Test
    fun aRatingAndAPhotoSetTogetherBothPersist() {
        runBlocking {
            val id = repo.save(doc("recipe_name: Soup\ningredients: []\nsteps: []\n"))
            repeat(25) { round ->
                val rating = round % 5 + 1
                val photo = "p$round.jpg"
                File(images, photo).writeText("x")
                coroutineScope {
                    launch(Dispatchers.IO) { repo.setRating(id, rating) }
                    launch(Dispatchers.IO) { repo.setImage(id, photo) }
                }
                val row = db.recipeDao().recipe(id)!!
                assertEquals("round $round", rating to photo, row.rating to row.imageFilename)
            }
        }
    }

    @Test
    fun replacingAPhotoDeletesTheOldUnusedOne() {
        runBlocking {
            listOf("old.jpg", "old_thumb.jpg", "new.jpg").forEach { File(images, it).writeText("x") }
            val id = repo.save(doc("recipe_name: Soup\nimage: old.jpg\ningredients: []\nsteps: []\n"))
            repo.setImage(id, "new.jpg")
            assertEquals("new.jpg", db.recipeDao().recipe(id)!!.imageFilename)
            assertFalse(File(images, "old.jpg").exists())
            assertFalse(File(images, "old_thumb.jpg").exists())
            assertTrue(File(images, "new.jpg").exists())
        }
    }

    @Test
    fun removingAPhotoWritesNoneAndDeletesTheFiles() {
        runBlocking {
            listOf("p.jpg", "p_thumb.jpg").forEach { File(images, it).writeText("x") }
            val id = repo.save(doc("recipe_name: Soup\nimage: p.jpg\ningredients: []\nsteps: []\n"))
            repo.removeImage(id)
            assertNull(db.recipeDao().recipe(id)!!.imageFilename)
            assertEquals("None", repo.doc(id)!!["image"])
            assertFalse(File(images, "p.jpg").exists())
        }
    }
}
