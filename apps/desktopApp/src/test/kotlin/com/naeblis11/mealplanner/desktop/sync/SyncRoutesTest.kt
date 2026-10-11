package com.naeblis11.mealplanner.desktop.sync

import androidx.room.useWriterConnection
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.desktop.server.jpegBytes
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.sync.ExchangeRequest
import com.naeblis11.mealplanner.sync.PairRequest
import com.naeblis11.mealplanner.sync.PairingProof
import com.naeblis11.mealplanner.sync.PairingWire
import com.naeblis11.mealplanner.sync.SyncErrors
import com.naeblis11.mealplanner.sync.SyncHub
import com.naeblis11.mealplanner.sync.SyncPaths
import com.naeblis11.mealplanner.sync.SyncWire
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2b (D6): every rule of the sync port's routes, through SyncHandler, with a real hub on a temp database. No sockets. */
class SyncRoutesTest {
    private val dir: File = Files.createTempDirectory("mp-routes").toFile()
    private val images = File(dir, "images").apply { mkdirs() }
    private val db = AppDatabase.openAt(File(dir, "pc.db"))
    private val recipes = RecipeRepository(db, images)
    private val hub = SyncHub(db, recipes, images)
    private var now = 1_000L
    private val window = PairingWindow(clock = { now })
    private val devices = PairedDevices(db.pairedDeviceDao(), clock = { now })
    private val logs = CopyOnWriteArrayList<String>()
    private var lanAllowed = true
    private var paired = 0
    private var exchanged = 0
    private var household: suspend () -> String = { HOUSEHOLD }
    private val routes = SyncRoutes(
        hub, window, devices,
        fingerprint = { FP },
        household = { household() },
        pcName = { "KITCHEN" },
        lanAllowed = { lanAllowed },
        onPaired = { paired++ },
        onExchanged = { exchanged++ },
        log = { logs += it },
    )

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun call(method: String, path: String, body: ByteArray = ByteArray(0), auth: String? = null, declared: Long = body.size.toLong()): SyncHttpResponse =
        runBlocking {
            routes.handle(
                SyncHttpRequest(method, path, auth, declared, body = { limit -> if (body.size > limit) throw BodyTooLargeException(limit) else body }, remoteAddress = "203.0.113.30"),
            )
        }

    // A request whose body is [body] itself, for a route that must answer before (or without) reading it.
    private fun callWith(method: String, path: String, auth: String?, declared: Long, body: (Int) -> ByteArray): SyncHttpResponse =
        runBlocking { routes.handle(SyncHttpRequest(method, path, auth, declared, body, remoteAddress = "203.0.113.30")) }

    private val neverRead: (Int) -> ByteArray = { throw AssertionError("The body was read before the key was checked.") }

    private fun SyncHttpResponse.text() = body.toString(Charsets.UTF_8)

    private fun error(code: String) = "{\"error\":\"$code\"}"

    private fun pairBody(code: String, fingerprint: String = FP) =
        PairingWire.encode(PairRequest(DEVICE, "Pixel", "phone", NONCE, PairingProof.device(code, fingerprint, NONCE))).toByteArray()

    private fun pairedKey(): String {
        val response = call("POST", SyncPaths.PAIR, pairBody(window.open().code))
        assertEquals(200, response.status)
        return PairingWire.decodeReply(response.text()).deviceKey
    }

    private fun emptyExchange() = SyncWire.encode(ExchangeRequest(deviceTime = System.currentTimeMillis(), cursor = 0L, full = false, records = emptyList(), hubId = null)).toByteArray()

    @Suppress("UNCHECKED_CAST")
    private fun doc(name: String, uuid: String, image: String) = RecipeYaml.load(
        "recipe_name: $name\nrecipe_uuid: $uuid\nimage: $image\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n",
    ) as YamlMap

    @Test
    fun aPairedDeviceLeavesWithItsKeyAndNoBodyIsRead() {
        val key = pairedKey()
        val response = callWith("POST", SyncPaths.LEAVE, "Bearer $key", 0, neverRead)
        assertEquals(204, response.status)
        assertEquals(0, response.body.size)
        assertNull(runBlocking { devices.authenticate("Bearer $key") })
        assertEquals(0, runBlocking { db.pairedDeviceDao().count() })
    }

    @Test
    fun leavingWithoutAPairedKeyIsUnpaired() {
        val key = pairedKey()
        for (auth in listOf(null, "Bearer " + "0".repeat(64), "Bearer $key-x")) {
            val response = callWith("POST", SyncPaths.LEAVE, auth, 0, neverRead)
            assertEquals(401, response.status)
            assertEquals(error(SyncErrors.UNPAIRED), response.text())
        }
        assertEquals(1, runBlocking { db.pairedDeviceDao().count() })
        assertEquals(404, call("GET", SyncPaths.LEAVE).status)
    }

    @Test
    fun withTheWindowClosedPairingIsNotOpen() {
        val response = call("POST", SyncPaths.PAIR, pairBody("123456"))
        assertEquals(404, response.status)
        assertEquals(error(SyncErrors.NOT_PAIRING), response.text())
    }

    @Test
    fun theRightCodePairsTheDeviceAndClosesTheWindow() {
        val code = window.open().code
        val response = call("POST", SyncPaths.PAIR, pairBody(code))
        assertEquals(200, response.status)
        assertEquals(SyncHttpResponse.JSON, response.contentType)
        val reply = PairingWire.decodeReply(response.text())
        assertEquals(PairingProof.hub(code, FP, NONCE), reply.hubProof)
        assertEquals(runBlocking { hub.hubId() }, reply.hubId)
        assertEquals(HOUSEHOLD, reply.householdId)
        assertEquals("KITCHEN", reply.pcName)
        assertEquals(DEVICE, runBlocking { devices.authenticate("Bearer ${reply.deviceKey}") }!!.deviceId)
        assertNull(window.offer.value)
        assertEquals(1, paired)
    }

    @Test
    fun aWrongCodeIsRefusedAndCountedAndTheFifthClosesTheWindow() {
        val code = window.open().code
        repeat(PairingWindow.MAX_FAILURES - 1) {
            val response = call("POST", SyncPaths.PAIR, pairBody("999999"))
            assertEquals(403, response.status)
            assertEquals(error(SyncErrors.WRONG_CODE), response.text())
        }
        // The fifth closes the window, so it is answered as the window being shut.
        assertEquals(error(SyncErrors.NOT_PAIRING), call("POST", SyncPaths.PAIR, pairBody("999999")).text())
        assertNull(window.offer.value)
        assertEquals(404, call("POST", SyncPaths.PAIR, pairBody(code)).status)
        assertEquals(0, runBlocking { db.pairedDeviceDao().count() })
    }

    @Test
    fun aCodeAlreadyClaimedIsAWrongCodeWhileTheWindowIsStillOpen() {
        val code = window.open().code
        assertNotNull(window.claim(PairingProof.device(code, FP, NONCE), NONCE, FP))
        val response = call("POST", SyncPaths.PAIR, pairBody(code))
        assertEquals(403, response.status)
        assertEquals(error(SyncErrors.WRONG_CODE), response.text())
        assertEquals(0, runBlocking { db.pairedDeviceDao().count() })
        assertEquals(0, paired)
    }

    @Test
    fun aProofOverAnotherCertificateIsAWrongCode() {
        val code = window.open().code
        assertEquals(403, call("POST", SyncPaths.PAIR, pairBody(code, fingerprint = "cd".repeat(32))).status)
    }

    @Test
    fun aBadOrTooBigPairRequestIsRefused() {
        window.open()
        assertEquals(error(SyncErrors.BAD_REQUEST), call("POST", SyncPaths.PAIR, "not json".toByteArray()).text())
        assertEquals(413, call("POST", SyncPaths.PAIR, ByteArray(10), declared = 5_000).status)
        val big = call("POST", SyncPaths.PAIR, ByteArray(5_000), declared = -1)
        assertEquals(413, big.status)
        assertEquals(error(SyncErrors.TOO_LARGE), big.text())
    }

    @Test
    fun whileYieldingEveryRouteSaysThisIsNotTheMaster() {
        val key = pairedKey()
        lanAllowed = false
        window.open()
        assertEquals(error(SyncErrors.NOT_MASTER), call("POST", SyncPaths.PAIR, pairBody(window.offer.value!!.code)).text())
        val exchange = call("POST", SyncPaths.EXCHANGE, emptyExchange(), auth = "Bearer $key")
        assertEquals(409, exchange.status)
        assertEquals(error(SyncErrors.NOT_MASTER), exchange.text())
    }

    @Test
    fun anExchangeNeedsAPairedDevicesKey() {
        val none = call("POST", SyncPaths.EXCHANGE, emptyExchange())
        assertEquals(401, none.status)
        assertEquals(error(SyncErrors.UNPAIRED), none.text())
        assertEquals(401, call("POST", SyncPaths.EXCHANGE, emptyExchange(), auth = "Bearer " + "0".repeat(64)).status)
    }

    @Test
    fun aPairedDeviceExchangesAndItsSyncIsDated() {
        val key = pairedKey()
        now = 7_000L
        val response = call("POST", SyncPaths.EXCHANGE, emptyExchange(), auth = "Bearer $key")
        assertEquals(200, response.status)
        assertEquals(runBlocking { hub.hubId() }, SyncWire.decodeReply(response.text()).hubId)
        assertEquals(7_000L, runBlocking { db.pairedDeviceDao().byId(DEVICE) }!!.lastSyncAt)
        assertEquals(1, exchanged)
    }

    @Test
    fun anExchangeOverFiftyMegabytesIsRefusedBeforeOrWhileReading() {
        val key = pairedKey()
        val declared = call("POST", SyncPaths.EXCHANGE, ByteArray(0), auth = "Bearer $key", declared = SyncRoutes.EXCHANGE_MAX_BYTES + 1L)
        assertEquals(413, declared.status)
        val read = call("POST", SyncPaths.EXCHANGE, ByteArray(SyncRoutes.EXCHANGE_MAX_BYTES + 1), auth = "Bearer $key", declared = -1)
        assertEquals(413, read.status)
    }

    @Test
    fun aGarbledExchangeIsABadRequest() {
        val key = pairedKey()
        val response = call("POST", SyncPaths.EXCHANGE, "{\"records\":7}".toByteArray(), auth = "Bearer $key")
        assertEquals(400, response.status)
        assertEquals(error(SyncErrors.BAD_REQUEST), response.text())
    }

    @Test
    fun aPhotoIsSentAsJpegAndAMissingOneIsNotFound() = runBlocking<Unit> {
        val key = pairedKey()
        val photo = jpegBytes(40, 30)
        File(images, "u-1.jpg").writeBytes(photo)
        recipes.save(doc("Soup", "u-1", "u-1.jpg"))
        val response = call("GET", SyncPaths.PHOTO + "u-1", auth = "Bearer $key")
        assertEquals(200, response.status)
        assertEquals(SyncHttpResponse.JPEG, response.contentType)
        assertArrayEquals(photo, response.body)
        assertEquals(error(SyncErrors.NOT_FOUND), call("GET", SyncPaths.PHOTO + "u-none", auth = "Bearer $key").text())
        assertEquals(401, call("GET", SyncPaths.PHOTO + "u-1").status)
    }

    @Test
    fun aPhotoTheHubDidntAskForIsRefusedAndABigOneIsTooLarge() {
        val key = pairedKey()
        val refused = call("PUT", SyncPaths.PHOTO + "u-1", jpegBytes(10, 10), auth = "Bearer $key")
        assertEquals(409, refused.status)
        assertEquals(error(SyncErrors.REFUSED), refused.text())
        assertEquals(413, call("PUT", SyncPaths.PHOTO + "u-1", ByteArray(0), auth = "Bearer $key", declared = 10L * 1024 * 1024 + 1).status)
    }

    @Test
    fun anExchangeWithoutAKeyIsRefusedBeforeItsBodyIsRead() {
        val none = callWith("POST", SyncPaths.EXCHANGE, auth = null, declared = 10, body = neverRead)
        assertEquals(401, none.status)
        assertEquals(error(SyncErrors.UNPAIRED), none.text())
        assertEquals(401, callWith("POST", SyncPaths.EXCHANGE, auth = "Bearer " + "0".repeat(64), declared = -1, body = neverRead).status)
    }

    @Test
    fun aPhotoUploadWithoutAKeyIsRefusedBeforeItsBodyIsRead() {
        val none = callWith("PUT", SyncPaths.PHOTO + "u-1", auth = null, declared = 10, body = neverRead)
        assertEquals(401, none.status)
        assertEquals(error(SyncErrors.UNPAIRED), none.text())
    }

    @Test
    fun aPhotoUploadWithNoLengthIsCappedWhileReading() {
        val key = pairedKey()
        val response = call("PUT", SyncPaths.PHOTO + "u-1", ByteArray(10 * 1024 * 1024 + 1), auth = "Bearer $key", declared = -1)
        assertEquals(413, response.status)
        assertEquals(error(SyncErrors.TOO_LARGE), response.text())
    }

    @Test
    fun anExchangeThatAppliedIsAnsweredEvenWhenDatingItFails() = runBlocking<Unit> {
        val key = pairedKey()
        // Dating the device's sync (an UPDATE of paired_device) fails; the exchange itself has worked.
        db.useWriterConnection { connection ->
            connection.usePrepared("CREATE TRIGGER no_touch BEFORE UPDATE ON paired_device BEGIN SELECT RAISE(ABORT, 'secret-touch'); END") { it.step() }
        }
        val response = call("POST", SyncPaths.EXCHANGE, emptyExchange(), auth = "Bearer $key")
        assertEquals(200, response.status)
        assertEquals(runBlocking { hub.hubId() }, SyncWire.decodeReply(response.text()).hubId)
        assertEquals(1, exchanged)
        assertTrue(logs.toString(), logs.any { it.startsWith("Meal Planner: dating a device's sync failed: ") })
        assertFalse(logs.toString(), logs.any { it.contains("secret-touch") || it.contains(key) })
    }

    @Test
    fun anythingElseIsNotFound() {
        val key = pairedKey()
        for ((method, path) in listOf("GET" to SyncPaths.EXCHANGE, "GET" to "/", "POST" to "/sync", "DELETE" to SyncPaths.PHOTO + "u-1", "GET" to SyncPaths.PHOTO + "..%2Fx", "GET" to SyncPaths.PHOTO + "a/b", "GET" to SyncPaths.PHOTO + "x".repeat(65))) {
            val response = call(method, path, auth = "Bearer $key")
            assertEquals("$method $path", 404, response.status)
            assertEquals(error(SyncErrors.NOT_FOUND), response.text())
        }
    }

    @Test
    fun aFailureIsA500ThatLogsOnlyItsClass() {
        household = { throw IllegalStateException("secret-household") }
        val code = window.open().code
        val response = call("POST", SyncPaths.PAIR, pairBody(code))
        assertEquals(500, response.status)
        assertEquals(error(SyncErrors.FAILED), response.text())
        assertEquals(listOf("Meal Planner: POST /sync/pair failed: IllegalStateException"), logs.toList())
        assertFalse(logs.any { it.contains("secret-household") || it.contains(code) })
        // The code was used up, so the window is closed all the same, and no device was left paired without its key.
        assertNull(window.offer.value)
        assertEquals(0, runBlocking { db.pairedDeviceDao().count() })
        assertEquals(0, paired)
    }

    @Test
    fun aRequestNeverShowsItsKeyInToString() {
        val text = SyncHttpRequest("POST", SyncPaths.EXCHANGE, "Bearer secret-key", 0, { ByteArray(0) }, "203.0.113.30").toString()
        assertFalse(text.contains("secret-key"))
        assertTrue(text.contains(SyncPaths.EXCHANGE))
        assertNotNull(text)
    }

    private companion object {
        val FP = "ab".repeat(32)
        val NONCE = "0f".repeat(16)
        const val DEVICE = "fedcba9876543210fedcba9876543210"
        const val HOUSEHOLD = "0123456789abcdef0123456789abcdef"
    }
}
