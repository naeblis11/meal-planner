package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPhotosTest {
    private val dir: File = Files.createTempDirectory("mp-photos").toFile()
    private val images = File(dir, "images").apply { mkdirs() }
    private val db = AppDatabase.openAt(File(dir, "test.db"))
    private val recipes = RecipeRepository(db, images)

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun jpeg(w: Int = 40, h: Int = 30): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Suppress("UNCHECKED_CAST")
    private suspend fun soup(image: String? = "u-1.jpg"): Long = recipes.save(
        RecipeYaml.load("recipe_name: Soup\nrecipe_uuid: u-1\n" + (image?.let { "image: $it\n" } ?: "") + "ingredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n") as YamlMap,
    )

    @Test
    fun aReceivedPhotoIsKeptByteForByteWithItsThumbnailAndHash() = runBlocking {
        val id = soup()
        val before = db.syncDao().change(SyncTables.RECIPE, "u-1")!!
        val bytes = jpeg()
        assertTrue(SyncPhotos.receive(db, images, "u-1", bytes))
        assertArrayEquals(bytes, File(images, "u-1.jpg").readBytes())
        assertTrue(File(images, "u-1_thumb.jpg").isFile)
        assertEquals(sha256(bytes), db.recipeDao().recipe(id)!!.imageHash)
        val after = db.syncDao().change(SyncTables.RECIPE, "u-1")!!
        assertEquals(before.updatedAt to before.updatedBy, after.updatedAt to after.updatedBy)
        assertTrue(after.changeSeq > before.changeSeq)
        assertArrayEquals(bytes, SyncPhotos.bytes(db, images, "u-1"))
    }

    @Test
    fun whatIsNotAPhotoOrTooBigIsRefused() = runBlocking {
        soup()
        assertFalse(SyncPhotos.receive(db, images, "u-1", "not a jpeg".toByteArray()))
        assertFalse(SyncPhotos.receive(db, images, "u-1", ByteArray(SyncPhotos.MAX_BYTES + 1)))
        assertFalse(SyncPhotos.receive(db, images, "unknown", jpeg()))
        assertFalse(File(images, "u-1.jpg").exists())
    }

    @Test
    fun aMissingHashIsFilledInWithoutCountingAsAChange() = runBlocking {
        val bytes = jpeg()
        File(images, "u-1.jpg").writeBytes(bytes)
        val id = soup()
        db.recipeDao().setImageHash(id, null) // a library saved before schema 6
        val stamp = db.syncDao().change(SyncTables.RECIPE, "u-1")
        SyncPhotos.backfillHashes(db, images)
        assertEquals(sha256(bytes), db.recipeDao().recipe(id)!!.imageHash)
        assertEquals(stamp, db.syncDao().change(SyncTables.RECIPE, "u-1"))
    }

    private suspend fun sentHash(): String? {
        val records = SyncRecords(db)
        val changed = records.changedSince(0L).single { it.table == SyncTables.RECIPE }
        val all = records.everything("me").single { it.table == SyncTables.RECIPE }
        assertEquals(SyncWire.textOrNull(changed.data!!, SyncData.IMAGE_HASH), SyncWire.textOrNull(all.data!!, SyncData.IMAGE_HASH))
        return SyncWire.textOrNull(changed.data!!, SyncData.IMAGE_HASH)
    }

    @Test
    fun aWishOutlivesAnEditOfTheRecipeAndIsTheHashTheRecipeIsSentWith() = runBlocking {
        File(images, "u-1.jpg").writeBytes(jpeg())
        val id = soup()
        SyncPhotos.want(db, "u-1", "wanted", db.recipeDao().recipe(id)!!.imageHash)
        recipes.setRating(id, 3) // this device's own edit
        assertEquals(mapOf("u-1" to "wanted"), SyncPhotos.wanted(db))
        assertEquals("wanted", sentHash())
    }

    @Test
    fun aWishIsDroppedWhenThePhotoHereIsReplaced() = runBlocking {
        File(images, "u-1.jpg").writeBytes(jpeg())
        val id = soup()
        SyncPhotos.want(db, "u-1", "wanted", db.recipeDao().recipe(id)!!.imageHash)
        val mine = jpeg(60, 60)
        File(images, "u-1.jpg").writeBytes(mine)
        recipes.setImage(id, "u-1.jpg")
        assertEquals(emptyMap<String, String>(), SyncPhotos.wanted(db))
        assertEquals(sha256(mine), sentHash())
    }

    @Test
    fun aPhotoNobodyAskedForIsRefused() = runBlocking {
        soup()
        assertFalse(SyncPhotos.receiveWanted(db, images, "u-1", jpeg()))
        assertFalse(File(images, "u-1.jpg").exists())
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun aPhotoIsNeverStoredUnderANameAnotherRecipeAlsoUses() = runBlocking {
        soup(image = "shared.jpg")
        recipes.save(
            RecipeYaml.load("recipe_name: Stew\nrecipe_uuid: u-2\nimage: shared.jpg\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n") as YamlMap,
        )
        assertFalse(SyncPhotos.receive(db, images, "u-1", jpeg()))
        assertFalse(File(images, "shared.jpg").exists())
    }
}
