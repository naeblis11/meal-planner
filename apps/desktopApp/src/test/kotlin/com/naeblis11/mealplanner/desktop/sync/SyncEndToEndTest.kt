package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.desktop.server.jpegBytes
import com.naeblis11.mealplanner.desktop.sync.SyncRig.Companion.LOOPBACK
import com.naeblis11.mealplanner.desktop.sync.SyncRig.Companion.PHONE_ID
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.sync.FirstSync
import com.naeblis11.mealplanner.sync.NotPairingException
import com.naeblis11.mealplanner.sync.PairingQr
import com.naeblis11.mealplanner.sync.PairingWire
import com.naeblis11.mealplanner.sync.SyncClient
import com.naeblis11.mealplanner.sync.SyncHttp
import com.naeblis11.mealplanner.sync.UnpairedException
import com.naeblis11.mealplanner.sync.WrongCodeException
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * LAN sync stage 2b (D8): a real sync port on 127.0.0.1, a real hub on a temp PC database, and a "phone" (a second
 * database and repository) that pairs through SyncHttp by QR and by typed code, then syncs both ways with photos.
 */
class SyncEndToEndTest {
    private val rig = SyncRig().start()
    private val phoneDir: File = Files.createTempDirectory("mp-phone").toFile()
    private val phoneImages = File(phoneDir, "images").apply { mkdirs() }
    private val phoneDb = AppDatabase.openAt(File(phoneDir, "phone.db"))
    private val phoneRecipes = RecipeRepository(phoneDb, phoneImages)

    @After
    fun tearDown() {
        rig.close()
        phoneDb.close()
        phoneDir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun doc(name: String, uuid: String): YamlMap = RecipeYaml.load(
        "recipe_name: $name\nrecipe_uuid: $uuid\nimage: $uuid.jpg\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n",
    ) as YamlMap

    @Test
    fun aPhoneThatLeavesIsForgottenByThePcAndItsNextSyncIsUnpaired() = runBlocking<Unit> {
        phoneRecipes.save(doc("Stew", "u-phone"))
        val phoneId = SyncSchema.deviceId(phoneDb)
        val offer = rig.window.open()
        val paired = SyncHttp(LOOPBACK, rig.port, rig.certificate.fingerprint).pair(offer.code, phoneId, "Pixel", PairingWire.KIND_PHONE)
        val http = SyncHttp(LOOPBACK, rig.port, paired.fingerprint)
        val client = SyncClient(phoneDb, phoneRecipes, phoneImages, http.transport(paired.reply.deviceKey))
        client.sync(FirstSync.MERGE)

        http.leave(paired.reply.deviceKey)
        assertEquals(0, rig.db.pairedDeviceDao().count())
        assertThrows(UnpairedException::class.java) { runBlocking { client.sync() } }
        // Leaving again is told the same; the phone keeps its data.
        assertThrows(UnpairedException::class.java) { runBlocking { http.leave(paired.reply.deviceKey) } }
        assertEquals(listOf("Stew"), phoneRecipes.allRecipes().map { it.name })
    }

    @Test
    fun aPhonePairsByQrAndByCodeThenSyncsBothWaysWithPhotos() = runBlocking<Unit> {
        val pcPhoto = jpegBytes(40, 30)
        File(rig.images, "u-pc.jpg").writeBytes(pcPhoto)
        rig.recipes.save(doc("Soup", "u-pc"))
        val phonePhoto = jpegBytes(50, 20)
        File(phoneImages, "u-phone.jpg").writeBytes(phonePhoto)
        phoneRecipes.save(doc("Stew", "u-phone"))
        val phoneId = SyncSchema.deviceId(phoneDb)

        // The QR path: the scanned text, then a connection pinned to its fingerprint.
        val offer = rig.window.open()
        val invite = PairingQr.decode(PairingQr.encode(listOf(LOOPBACK), rig.port, offer.code, rig.certificate.fingerprint))
        val byQr = SyncHttp(invite.addresses.first(), invite.port, invite.fingerprint).pair(invite.code, phoneId, "Pixel", PairingWire.KIND_PHONE)
        assertNull(rig.window.offer.value)

        // The typed path: no pin; the fingerprint is learnt and checked through the proofs.
        val code = rig.window.open().code
        val typed = SyncHttp(LOOPBACK, rig.port, pin = null).pair(code, phoneId, "Pixel", PairingWire.KIND_PHONE)
        assertEquals(rig.certificate.fingerprint, typed.fingerprint)
        assertEquals(byQr.reply.hubId, typed.reply.hubId)
        // Pairing again replaced the key.
        assertNull(rig.devices.authenticate("Bearer ${byQr.reply.deviceKey}"))

        val client = SyncClient(phoneDb, phoneRecipes, phoneImages, SyncHttp(LOOPBACK, rig.port, typed.fingerprint).transport(typed.reply.deviceKey))
        val result = client.sync(FirstSync.MERGE)
        assertEquals(listOf("Soup", "Stew"), phoneRecipes.allRecipes().map { it.name }.sorted())
        assertEquals(listOf("Soup", "Stew"), rig.recipes.allRecipes().map { it.name }.sorted())
        assertArrayEquals(pcPhoto, File(phoneImages, "u-pc.jpg").readBytes())
        assertArrayEquals(phonePhoto, File(rig.images, "u-phone.jpg").readBytes())
        assertEquals(2, result.photos)
        assertNotNull(rig.db.pairedDeviceDao().byId(phoneId)!!.lastSyncAt)

        // Removed on the PC: the next sync is told, and the phone keeps its data.
        rig.devices.remove(phoneId)
        assertThrows(UnpairedException::class.java) { runBlocking { client.sync() } }
        assertEquals(2, phoneRecipes.allRecipes().size)
    }

    @Test
    fun fiveWrongCodesCloseTheWindow() = runBlocking<Unit> {
        val code = rig.window.open().code
        val wrong = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        repeat(PairingWindow.MAX_FAILURES - 1) {
            assertThrows(WrongCodeException::class.java) { runBlocking { rig.client().pair(wrong, PHONE_ID, "Pixel", PairingWire.KIND_PHONE) } }
        }
        // The fifth failure shuts the window, and the route then answers not_pairing (the window closed meanwhile).
        assertThrows(NotPairingException::class.java) { runBlocking { rig.client().pair(wrong, PHONE_ID, "Pixel", PairingWire.KIND_PHONE) } }
        assertNull(rig.window.offer.value)
        assertThrows(NotPairingException::class.java) { runBlocking { rig.client().pair(code, PHONE_ID, "Pixel", PairingWire.KIND_PHONE) } }
        assertEquals(0, rig.db.pairedDeviceDao().count())
    }
}
