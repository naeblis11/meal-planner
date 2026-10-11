package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.SyncPendingEntity
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.data.inTransaction
import com.naeblis11.mealplanner.photos.PhotoProcessor
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Recipe photos for sync (LAN sync spec, section 3): matched by image_hash, moved by recipe uuid, kept byte for byte so
 * the hash agrees everywhere, and only into the file the recipe names (a safe `.jpg` name).
 */
object SyncPhotos {
    const val MAX_BYTES = 10 * 1024 * 1024

    /** The detail photo of the recipe [uuid], or null when it has none here. */
    suspend fun bytes(db: AppDatabase, imagesDir: File, uuid: String): ByteArray? {
        val name = photoName(db, uuid) ?: return null
        return File(imagesDir, name).takeIf { it.isFile }?.readBytes()
    }

    /**
     * Keeps [bytes] as the photo of the recipe [uuid]. Its change keeps the sender's stamp but takes a new counter step,
     * so a device that synced before the photo arrived fetches it. False when refused (too big, not an image, no such
     * recipe, no safe photo name, a name another recipe also uses, or the write failed).
     */
    suspend fun receive(db: AppDatabase, imagesDir: File, uuid: String, bytes: ByteArray): Boolean {
        if (bytes.size > MAX_BYTES) return false
        val name = photoName(db, uuid) ?: return false
        // Never into another recipe's photo: an imported library can have two recipes naming one file.
        if (db.recipeDao().countImageUsers(name) > 1) return false
        try {
            PhotoProcessor.store(bytes, imagesDir, name)
        } catch (e: IOException) {
            return false
        }
        val sync = db.syncDao()
        db.inTransaction {
            val id = db.recipeDao().idForUuid(uuid) ?: return@inTransaction
            val before = sync.change(SyncTables.RECIPE, uuid)
            db.recipeDao().setImageHash(id, sha256(bytes))
            if (before != null) {
                sync.bumpChangeSeq()
                sync.putChange(before.copy(changeSeq = SyncSchema.changeSeq(db)))
            }
        }
        return true
    }

    /** Fills in the hash of photos saved before schema 6, putting each recipe's change back as it was: not an edit. */
    suspend fun backfillHashes(db: AppDatabase, imagesDir: File) {
        val sync = db.syncDao()
        for (row in db.recipeDao().missingImageHashes()) {
            val name = row.imageFilename?.takeIf { RecipeRepository.isSafeImageName(it) } ?: continue
            val file = File(imagesDir, name).takeIf { it.isFile } ?: continue
            val hash = sha256(file.readBytes())
            db.inTransaction {
                val before = sync.change(SyncTables.RECIPE, row.recipeUuid)
                db.recipeDao().setImageHash(row.id, hash)
                if (before == null) sync.deleteChange(SyncTables.RECIPE, row.recipeUuid) else sync.putChange(before)
            }
        }
    }

    /**
     * Records that the photo with [hash] is wanted for the recipe [uuid], replacing any earlier wish for it. [here] is
     * the hash of the photo this device has for it now (null: none), kept to tell later whether the user replaced it.
     * The row's record is "wanted|here".
     */
    suspend fun want(db: AppDatabase, uuid: String, hash: String, here: String?) =
        db.syncDao().putPending(SyncPendingEntity(SyncApplier.PHOTO_TABLE, uuid, SyncApplier.PHOTO, "$hash|${here.orEmpty()}"))

    /** The hash wished for the recipe [uuid], as recorded (no check), or null when there is no wish. */
    suspend fun wishedHash(db: AppDatabase, uuid: String): String? =
        db.syncDao().pendingFor(SyncApplier.PHOTO_TABLE, uuid)?.let { parse(it.record).first }

    /** No photo is wanted for the recipe [uuid] any more. */
    suspend fun forget(db: AppDatabase, uuid: String) = db.syncDao().dropPending(SyncApplier.PHOTO_TABLE, uuid)

    /**
     * The photos still wanted here, recipe uuid to image_hash. While a wish stands, this device sends the wished hash
     * as the recipe's image_hash (SyncRecords), so an edit made here before the photo arrives never sends the old photo
     * back as the newer one. A wish ends only when the wished photo is here, when the photo here was replaced by
     * something else since the wish was made (the user's own photo), or when its recipe is gone; an edit of the
     * recipe never ends it.
     */
    suspend fun wanted(db: AppDatabase): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (p in db.syncDao().pending().filter { it.reason == SyncApplier.PHOTO }) {
            standing(db, p.syncKey, p.record)?.let { out[p.syncKey] = it }
        }
        return out
    }

    /** The hash still wished for the recipe [uuid], or null (a wish that has ended is dropped). */
    suspend fun wishFor(db: AppDatabase, uuid: String): String? =
        db.syncDao().pendingFor(SyncApplier.PHOTO_TABLE, uuid)?.let { standing(db, uuid, it.record) }

    private suspend fun standing(db: AppDatabase, uuid: String, record: String): String? {
        val (wanted, atWish) = parse(record)
        val row = db.recipeDao().idForUuid(uuid)?.let { db.recipeDao().recipe(it) }
        val here = row?.imageHash
        // atWish is null (unknown) only for a wish recorded without the photo here.
        val replaced = atWish != null && here != atWish.ifEmpty { null }
        if (row == null || here == wanted || replaced) {
            forget(db, uuid)
            return null
        }
        return wanted
    }

    // "wanted|here" ("" for no photo here); a bare hash is a wish recorded without the photo here (unknown).
    private fun parse(record: String): Pair<String, String?> =
        if ('|' in record) record.substringBefore('|') to record.substringAfter('|') else record to null

    /**
     * Keeps [bytes] for a wanted photo: only when they hash to the wanted image_hash, and the wish is then done. With no
     * wish for [uuid] they are refused: a photo is only ever taken when this device asked for it.
     */
    suspend fun receiveWanted(db: AppDatabase, imagesDir: File, uuid: String, bytes: ByteArray): Boolean {
        val wanted = wanted(db)[uuid] ?: return false
        if (sha256(bytes) != wanted || !receive(db, imagesDir, uuid, bytes)) return false
        forget(db, uuid)
        return true
    }

    private suspend fun photoName(db: AppDatabase, uuid: String): String? {
        val row = db.recipeDao().idForUuid(uuid)?.let { db.recipeDao().recipe(it) } ?: return null
        return row.imageFilename?.takeIf { RecipeRepository.isSafeImageName(it) && it.endsWith(".jpg") }
    }

    /** The image_hash of [bytes]: their SHA-256 as lower-case hex. */
    fun hash(bytes: ByteArray): String = sha256(bytes)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
