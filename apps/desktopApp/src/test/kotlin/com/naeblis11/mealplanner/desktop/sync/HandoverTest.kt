package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.desktop.DesktopApp
import com.naeblis11.mealplanner.desktop.MapSettings
import com.naeblis11.mealplanner.desktop.google.FakeProtector
import com.naeblis11.mealplanner.desktop.server.ApiToken
import com.naeblis11.mealplanner.desktop.server.PortProbe
import com.naeblis11.mealplanner.desktop.server.SecretsFile
import com.naeblis11.mealplanner.desktop.server.eventually
import com.naeblis11.mealplanner.desktop.server.freeLoopbackPort
import com.naeblis11.mealplanner.desktop.sync.SyncRig.Companion.LOOPBACK
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.settings.SyncServerState
import com.naeblis11.mealplanner.sync.FirstSync
import com.naeblis11.mealplanner.sync.FoundPc
import com.naeblis11.mealplanner.sync.HandoverBundle
import com.naeblis11.mealplanner.sync.HandoverRequest
import com.naeblis11.mealplanner.sync.HttpSyncConnector
import com.naeblis11.mealplanner.sync.PairingWire
import com.naeblis11.mealplanner.sync.PcFinder
import com.naeblis11.mealplanner.sync.PhoneSync
import com.naeblis11.mealplanner.sync.SyncConnector
import com.naeblis11.mealplanner.sync.SyncHttp
import com.naeblis11.mealplanner.sync.SyncHttpException
import com.naeblis11.mealplanner.sync.SyncMessages
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LAN sync stage 4b end to end, over real HTTPS on 127.0.0.1 and ephemeral ports: a main PC (KITCHEN) with a phone and a
 * client PC (STUDY), each PC wired as Main wires it (DesktopApp, DesktopSync over JdkHttpsEngine, DesktopClient over
 * HttpSyncConnector, FakeProtector for DPAPI). KITCHEN offers STUDY its role; STUDY's next Sync takes it.
 */
class HandoverTest {
    private val root: File = Files.createTempDirectory("mp-handover").toFile()
    private val apps = mutableListOf<DesktopApp>()
    private val kitchenSkew = AtomicLong(0)

    private inner class Pc(val name: String, val connector: (SyncConnector) -> SyncConnector = { it }) {
        val dir = File(root, name).apply { mkdirs() }
        val port = freeLoopbackPort()
        val token = ApiToken(SecretsFile(File(dir, ".env")), log = {})
        var app: DesktopApp = start()

        fun start(): DesktopApp = DesktopApp(
            dir,
            moveToTrash = { it.delete() },
            settingsFactory = { MapSettings() },
            syncFactory = { desktop ->
                DesktopSync(
                    database = { desktop.container.database },
                    recipes = { desktop.container.recipes },
                    imagesDir = desktop.imagesDir,
                    certificate = SyncCertificate(dir, FakeProtector(), log = {}),
                    household = { desktop.identity.household.id() },
                    pcName = { name },
                    lanAllowed = desktop::syncOnLan,
                    addresses = { listOf(LOOPBACK) },
                    engine = JdkHttpsEngine(portFree = { PortProbe.isFree(it, InetAddress.getLoopbackAddress()) }, log = {}),
                    port = port,
                    lanHost = LOOPBACK,
                    clock = { System.currentTimeMillis() + (if (name == KITCHEN) kitchenSkew.get() else 0L) },
                    log = {},
                    alexaToken = { token.value },
                    onHandedOver = { handed ->
                        desktop.client?.joinByHandover(handed)
                        token.clear()
                    },
                )
            },
            clientFactory = { desktop ->
                DesktopClient(
                    database = { desktop.container.database },
                    recipes = { desktop.container.recipes },
                    imagesDir = desktop.imagesDir,
                    store = DpapiPairingStore(File(dir, DpapiPairingStore.FILE_NAME), FakeProtector()),
                    finder = finderFor { others(name) },
                    deviceName = name,
                    connector = connector(HttpSyncConnector),
                    onRoleChanged = { desktop.sync?.rebind() },
                    taker = object : HandoverTaker {
                        override suspend fun here() = HandoverHere(name, listOf(LOOPBACK), port, desktop.sync!!.hub.hubId())

                        override suspend fun adopt(bundle: HandoverBundle, masterKeyHash: String) {
                            desktop.sync!!.adopt(bundle, masterKeyHash)
                            bundle.alexaToken?.let(token::adopt)
                        }
                    },
                    pending = HandoverPending(File(dir, HandoverPending.FILE_NAME), FakeProtector()),
                    log = {},
                )
            },
        ).also { apps += it; runBlocking { it.start().join() } }

        fun restart() {
            assertTrue(app.close())
            apps.remove(app)
            app = start()
        }

        val sync get() = app.sync!!
        val client get() = app.client!!
        val found get() = FoundPc(name, LOOPBACK, port)
    }

    private val pcs = mutableListOf<Pc>()

    private fun others(name: String): List<FoundPc> = pcs.filter { it.name != name && !it.client.isClient() }.map { it.found }

    private fun finderFor(found: () -> List<FoundPc>) = object : PcFinder {
        override suspend fun find(timeoutMillis: Long) = found()
    }

    // A phone: its own database, sealed pairing and the real connector; it finds whichever PC is a main PC now.
    private val phoneDir = File(root, "phone").apply { mkdirs() }
    private val phoneImages = File(phoneDir, "images").apply { mkdirs() }
    private val phoneDb = AppDatabase.openAt(File(phoneDir, "phone.db"))
    private val phoneRecipes = RecipeRepository(phoneDb, phoneImages)
    private val phoneStore = DpapiPairingStore(File(phoneDir, "pairing.bin"), FakeProtector())
    private val phone = PhoneSync(phoneDb, phoneRecipes, phoneImages, phoneStore, finderFor { others("phone") }, "Pixel", HttpSyncConnector)

    @After
    fun tearDown() {
        apps.forEach { it.close() }
        phoneDb.close()
        root.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun doc(name: String, uuid: String): YamlMap = RecipeYaml.load(
        "recipe_name: $name\nrecipe_uuid: $uuid\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n",
    ) as YamlMap

    private suspend fun code(pc: Pc): String {
        pc.sync.open()
        return pc.sync.offer.value!!.code
    }

    // KITCHEN with an Alexa token, a recipe and a phone; STUDY joined to it; both synced.
    private suspend fun household(studyConnector: (SyncConnector) -> SyncConnector = { it }): Pair<Pc, Pc> {
        val kitchen = Pc(KITCHEN).also { pcs += it }
        kitchen.token.adopt(TOKEN)
        kitchen.app.container.recipes.save(doc("Soup", "u-soup"))
        phone.pairTyped(code(kitchen), kitchen.found)
        phone.sync(FirstSync.MERGE)
        val study = Pc(STUDY, studyConnector).also { pcs += it }
        study.client.controls.pairTyped(code(kitchen), kitchen.found)
        study.client.engine.sync(FirstSync.MERGE)
        eventually { kitchen.sync.devices.value.any { it.kind == PairingWire.KIND_PC } }
        return kitchen to study
    }

    private suspend fun deviceId(pc: Pc) = SyncSchema.deviceId(pc.app.container.database)

    @Test
    fun theMainPcHandsItsRoleToAClientPcAndThePhoneFollows() = runBlocking<Unit> {
        val (kitchen, study) = household()
        val fingerprint = kitchen.sync.certificate.fingerprint
        val phoneId = SyncSchema.deviceId(phoneDb)

        kitchen.sync.offerHandover(deviceId(study))
        assertEquals(STUDY, kitchen.sync.handover.value!!.name)
        val result = study.client.engine.sync()
        assertEquals(SyncMessages.nowMainPc(KITCHEN), result.note)

        // STUDY is the main PC: KITCHEN's certificate, its devices (the phone, and KITCHEN as a PC) and its token.
        assertFalse(study.client.isClient())
        assertEquals(fingerprint, study.sync.certificate.fingerprint)
        val listed = study.app.container.database.pairedDeviceDao().all().associate { it.deviceId to it.kind }
        assertEquals(mapOf(phoneId to PairingWire.KIND_PHONE, deviceId(kitchen) to PairingWire.KIND_PC), listed)
        assertEquals(TOKEN, study.token.value)
        assertTrue(study.app.syncOnLan())
        eventually { study.sync.status.value.state == SyncServerState.LISTENING }

        // KITCHEN is STUDY's client, with no devices, certificate or token of its own, and has synced with STUDY.
        eventually { kitchen.client.master.value == STUDY }
        eventually { !kitchen.sync.certificate.storeFile.exists() && runBlocking { kitchen.app.container.database.pairedDeviceDao().count() } == 0 }
        assertNull(kitchen.token.value)
        eventually { kitchen.client.controls.status.value.lastSyncAt != null && !kitchen.client.controls.syncing.value }

        // The phone's stored PC is closed now: it finds STUDY by the same certificate and keeps its name.
        phoneRecipes.save(doc("Stew", "u-stew"))
        phone.sync()
        assertEquals(STUDY, phoneStore.load()!!.pcName)
        assertEquals(setOf("Soup", "Stew"), study.app.container.recipes.allRecipes().map { it.name }.toSet())
        // And on to KITCHEN, as STUDY's client.
        kitchen.client.engine.sync()
        assertEquals(setOf("Soup", "Stew"), kitchen.app.container.recipes.allRecipes().map { it.name }.toSet())
        assertTrue(kitchen.app.recipesDir.listFiles()!!.any { it.readText().contains("u-stew") })
    }

    @Test
    fun withoutAnOfferASyncChangesNothingAndOnlyTheOfferedPcMayTake() = runBlocking<Unit> {
        val (kitchen, study) = household()
        assertNull(study.client.engine.sync().note)
        assertTrue(study.client.isClient())
        // Offered to STUDY: the phone's key can't take it (a phone never can), nor can STUDY once it is cancelled.
        kitchen.sync.offerHandover(deviceId(study))
        val phoneKey = phoneStore.load()!!.deviceKey
        val ask = HandoverRequest("Pixel", listOf(LOOPBACK), 5001, "h".repeat(32), "ab".repeat(32))
        val refused = runCatching { SyncHttp(LOOPBACK, kitchen.port, kitchen.sync.certificate.fingerprint).takeHandover(phoneKey, ask) }
        assertTrue(refused.exceptionOrNull() is com.naeblis11.mealplanner.sync.HandoverRefusedException)
        kitchen.sync.cancelHandover()
        assertNull(study.client.engine.sync().note)
        assertTrue(study.client.isClient())
    }

    @Test
    fun aMainPcThatGaveUpWaitingKeepsItsRoleAndTheTakerStaysItsClient() = runBlocking<Unit> {
        val (kitchen, study) = household { real ->
            object : SyncConnector by real {
                override suspend fun handoverDone(host: String, port: Int, pin: String, deviceKey: String) {
                    // The wait runs out on KITCHEN before STUDY says it is done.
                    kitchenSkew.addAndGet(HandoverState.WAIT_MILLIS + 1_000)
                    real.handoverDone(host, port, pin, deviceKey)
                }
            }
        }
        kitchen.sync.offerHandover(deviceId(study))
        assertNull(study.client.engine.sync().note)
        assertTrue(study.client.isClient())
        assertFalse(File(study.dir, HandoverPending.FILE_NAME).exists())
        assertFalse(kitchen.client.isClient())
        assertTrue(kitchen.sync.certificate.storeFile.exists())
        assertEquals(TOKEN, kitchen.token.value)
        // KITCHEN serves again: the phone still syncs with it.
        phone.sync()
    }

    @Test
    fun aHandOverCutShortIsFinishedAtTheNextStart() = runBlocking<Unit> {
        var cut = true
        val (kitchen, study) = household { real ->
            object : SyncConnector by real {
                override suspend fun handoverDone(host: String, port: Int, pin: String, deviceKey: String) {
                    // STUDY stops (its app is closed) between taking the bundle and saying it is done.
                    if (cut) throw CancellationException("closed")
                    real.handoverDone(host, port, pin, deviceKey)
                }
            }
        }
        kitchen.sync.offerHandover(deviceId(study))
        runCatching { study.client.engine.sync() }
        assertTrue(File(study.dir, HandoverPending.FILE_NAME).exists())
        assertTrue(study.client.isClient())
        cut = false
        study.restart()
        eventually { !study.client.isClient() }
        assertFalse(File(study.dir, HandoverPending.FILE_NAME).exists())
        eventually { kitchen.client.master.value == STUDY }
        assertEquals(TOKEN, study.token.value)
    }

    @Test
    fun aLostDoneStillHandsOverWhenTheOldMainPcHasGone() = runBlocking<Unit> {
        val (kitchen, study) = household { real ->
            object : SyncConnector by real {
                override suspend fun handoverDone(host: String, port: Int, pin: String, deviceKey: String) {
                    real.handoverDone(host, port, pin, deviceKey)
                    // The 204 is lost on the way back.
                    throw SyncHttpException(SyncHttp.UNREACHABLE)
                }
            }
        }
        kitchen.sync.offerHandover(deviceId(study))
        assertEquals(SyncMessages.nowMainPc(KITCHEN), study.client.engine.sync().note)
        assertFalse(study.client.isClient())
        eventually { kitchen.client.master.value == STUDY }
    }

    companion object {
        const val KITCHEN = "KITCHEN"
        const val STUDY = "STUDY"
        val TOKEN = "t".repeat(64)
    }
}
