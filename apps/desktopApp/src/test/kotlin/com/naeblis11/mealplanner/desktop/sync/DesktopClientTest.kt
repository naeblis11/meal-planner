package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.data.GoogleEventEntity
import com.naeblis11.mealplanner.data.Household
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.desktop.DesktopApp
import com.naeblis11.mealplanner.desktop.MapSettings
import com.naeblis11.mealplanner.desktop.google.FakeProtector
import com.naeblis11.mealplanner.desktop.peers.FakeDiscovery
import com.naeblis11.mealplanner.desktop.peers.FakeNetwork
import com.naeblis11.mealplanner.desktop.peers.PeerRecord
import com.naeblis11.mealplanner.desktop.peers.PeerTxt
import com.naeblis11.mealplanner.desktop.peers.PeerWatch
import com.naeblis11.mealplanner.desktop.server.ApiToken
import com.naeblis11.mealplanner.desktop.server.DesktopServerControls
import com.naeblis11.mealplanner.desktop.server.FakeEngine
import com.naeblis11.mealplanner.desktop.server.MealPlannerServer
import com.naeblis11.mealplanner.desktop.server.SecretsFile
import com.naeblis11.mealplanner.desktop.server.ServerRoutes
import com.naeblis11.mealplanner.desktop.server.eventually
import com.naeblis11.mealplanner.desktop.sync.SyncRig.Companion.LOOPBACK
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.sync.FirstSync
import com.naeblis11.mealplanner.sync.FoundPc
import com.naeblis11.mealplanner.sync.PairingWire
import com.naeblis11.mealplanner.sync.PcFinder
import com.naeblis11.mealplanner.sync.SyncMessages
import com.naeblis11.mealplanner.sync.UnpairedException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LAN sync stage 4a end to end: a real main PC (SyncRig: HTTPS on 127.0.0.1, an ephemeral port) and a second PC wired as
 * Main wires it (DesktopApp with its recipe folder, the server over FakeEngine, DesktopClient over HttpSyncConnector and a
 * pairing sealed with FakeProtector). The PC joins by typed code, syncs recipe files both ways, and leaves or is removed.
 */
class DesktopClientTest {
    private val rig = SyncRig().start()
    private val dir: File = Files.createTempDirectory("mp-client-pc").toFile()
    private val secrets = File(dir, ".env").apply { writeText("MEAL_PLANNER_API_TOKEN=${"t".repeat(32)}\n") }
    private val trashed = CopyOnWriteArrayList<String>()
    private val roleChanges = AtomicInteger()
    private val main = FoundPc(SyncRig.PC_NAME, LOOPBACK, rig.port)
    private val finder = object : PcFinder {
        override suspend fun find(timeoutMillis: Long) = listOf(main)
    }
    private val apps = mutableListOf<DesktopApp>()

    @After
    fun tearDown() {
        apps.forEach { assertTrue("closed", it.close()) }
        rig.close()
        dir.deleteRecursively()
    }

    private fun app(engine: FakeEngine = FakeEngine(), peers: ((DesktopApp) -> PeerWatch)? = null): DesktopApp = DesktopApp(
        dir,
        moveToTrash = { f -> trashed += f.name; f.delete() },
        settingsFactory = { MapSettings() },
        serverFactory = { desktop ->
            MealPlannerServer(ApiToken(SecretsFile(secrets), log = {}), ServerRoutes(), engine, port = 5055, log = {}, lanAllowed = desktop::serverOnLan)
        },
        peersFactory = peers,
        clientFactory = { desktop ->
            DesktopClient(
                database = { desktop.container.database },
                recipes = { desktop.container.recipes },
                imagesDir = desktop.imagesDir,
                store = DpapiPairingStore(File(dir, DpapiPairingStore.FILE_NAME), FakeProtector()),
                finder = finder,
                deviceName = "LAPTOP",
                onRoleChanged = {
                    roleChanges.incrementAndGet()
                    desktop.server?.rebind()
                },
            )
        },
    ).also { apps += it }

    private fun started(engine: FakeEngine = FakeEngine()): DesktopApp = app(engine).also { runBlocking { it.start().join() } }

    private fun onLan(app: DesktopApp, lan: Boolean) = app.server!!.status.value.let { it.state == ServerState.LISTENING && it.onLan == lan }

    @Suppress("UNCHECKED_CAST")
    private fun doc(name: String, uuid: String): YamlMap = RecipeYaml.load(
        "recipe_name: $name\nrecipe_uuid: $uuid\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n",
    ) as YamlMap

    private fun join(app: DesktopApp) = runBlocking { app.client!!.controls.pairTyped(rig.window.open().code, main) }

    private fun fileHolding(app: DesktopApp, text: String): File? = app.recipesDir.listFiles()?.firstOrNull { it.readText().contains(text) }

    @Test
    fun aPcJoinsByTypedCodeThenSyncsItsRecipeFilesBothWays() = runBlocking<Unit> {
        val app = started()
        eventually { onLan(app, lan = true) }
        val client = app.client!!
        assertFalse(client.isClient())
        assertTrue(app.syncOnLan())
        app.container.recipes.save(doc("Stew", "u-laptop"))
        app.container.database.googleEventDao().record(GoogleEventEntity("primary", "2026-10-16", "dinner", "e1", "h1"))
        rig.recipes.save(doc("Soup", "u-pc"))

        join(app)
        assertEquals(SyncRig.PC_NAME, client.master.value)
        assertEquals(1, roleChanges.get())
        assertEquals(PairingWire.KIND_PC, rig.db.pairedDeviceDao().all().single().kind)
        assertEquals("LAPTOP", rig.db.pairedDeviceDao().all().single().name)
        // R4: a client never answers on the home network; R2: it takes the main PC's household and forgets its events.
        eventually { onLan(app, lan = false) }
        assertFalse(app.syncOnLan())
        assertEquals(SyncRig.HOUSEHOLD, app.identity.household.id())
        assertEquals(emptyList<GoogleEventEntity>(), app.container.database.googleEventDao().all())

        client.engine.sync(FirstSync.MERGE)
        assertEquals(setOf("Soup", "Stew"), rig.recipes.allRecipes().map { it.name }.toSet())
        assertTrue("the main PC's recipe is a file here", fileHolding(app, "u-pc") != null)

        // An edit on this PC reaches the main PC, and one there reaches this PC's file.
        app.container.recipes.save(doc("Pea soup", "u-pc"))
        rig.recipes.save(doc("Beef stew", "u-laptop"))
        client.engine.sync()
        assertEquals(setOf("Pea soup", "Beef stew"), rig.recipes.allRecipes().map { it.name }.toSet())
        assertTrue(fileHolding(app, "u-laptop")!!.readText().contains("Beef stew"))
        assertEquals(setOf("Pea soup", "Beef stew"), app.container.recipes.allRecipes().map { it.name }.toSet())
    }

    @Test
    fun replaceOnAClientPcTrashesItsFilesAndTakesTheMainPcs() = runBlocking<Unit> {
        val app = started()
        app.container.recipes.save(doc("Stew", "u-laptop"))
        val stewFile = fileHolding(app, "u-laptop")!!.name
        rig.recipes.save(doc("Soup", "u-pc"))
        join(app)
        app.client!!.engine.sync(FirstSync.REPLACE)
        assertEquals(listOf(stewFile), trashed.toList())
        assertNull(fileHolding(app, "u-laptop"))
        // The folder doesn't bring it back, and the main PC never had it.
        app.folder.sync()
        assertEquals(listOf("Soup"), app.container.recipes.allRecipes().map { it.name })
        assertEquals(listOf("Soup"), rig.recipes.allRecipes().map { it.name })
    }

    @Test
    fun leavingGivesThisPcAHouseholdOfItsOwnAndMakesItAMainPcAgain() = runBlocking<Unit> {
        val app = started()
        join(app)
        app.client!!.engine.sync(FirstSync.MERGE)
        rig.recipes.save(doc("Soup", "u-pc"))
        app.client!!.engine.sync()
        eventually { onLan(app, lan = false) }

        val before = System.currentTimeMillis()
        app.client!!.controls.leave()
        assertNull(app.client!!.master.value)
        assertEquals(2, roleChanges.get())
        assertEquals(0, rig.db.pairedDeviceDao().count())
        val household = app.identity.household
        assertNotEquals(SyncRig.HOUSEHOLD, household.id())
        assertTrue("created now", household.created() in before..System.currentTimeMillis())
        eventually { onLan(app, lan = true) }
        assertTrue(app.syncOnLan())
        // Its data stays.
        assertEquals(listOf("Soup"), app.container.recipes.allRecipes().map { it.name })
    }

    @Test
    fun aClientRemovedOnTheMainPcIsAMainPcAgainWithItsData() = runBlocking<Unit> {
        val app = started()
        rig.recipes.save(doc("Soup", "u-pc"))
        join(app)
        app.client!!.engine.sync(FirstSync.MERGE)
        rig.devices.remove(SyncSchema.deviceId(app.container.database))
        assertThrows(UnpairedException::class.java) { runBlocking { app.client!!.engine.sync() } }
        assertNull(app.client!!.master.value)
        assertNotEquals(SyncRig.HOUSEHOLD, app.container.database.appMetaDao().get(Household.KEY))
        eventually { onLan(app, lan = true) }
        assertEquals(listOf("Soup"), app.container.recipes.allRecipes().map { it.name })
    }

    @Test
    fun aClientIsAClientFromItsStartAndSyncsThenOnlyOnceItHasSyncedBefore() = runBlocking<Unit> {
        val first = started()
        first.client!!.startupSync()
        assertEquals("not paired: nothing asked", 0, rig.requests.get())
        join(first)
        val paired = rig.requests.get()
        first.client!!.startupSync()
        Thread.sleep(200)
        assertEquals("never synced: the first sync waits for Merge or Replace", paired, rig.requests.get())
        first.client!!.engine.sync(FirstSync.MERGE)
        assertTrue(first.close())
        apps.remove(first)

        // Started again: a client before anything binds, so the server never listens on the network, and one sync runs.
        val engine = FakeEngine()
        val before = rig.requests.get()
        val again = started(engine)
        assertEquals(SyncRig.PC_NAME, again.client!!.master.value)
        eventually { onLan(again, lan = false) }
        assertEquals(listOf("127.0.0.1"), engine.startedHosts())
        eventually { rig.requests.get() > before }
        eventually { again.client!!.controls.status.value.lastSyncAt != null && !again.client!!.controls.syncing.value }
    }

    @Test
    fun aClientMakesNoAlexaToken() {
        val app = started()
        join(app)
        val token = ApiToken(SecretsFile(File(dir, "other.env")), log = {})
        val controls = DesktopServerControls(app.server!!, token, refusal = { app.client!!.master.value?.let(SyncMessages::clientAlexa) })
        val e = assertThrows(IllegalStateException::class.java) { controls.createToken() }
        assertEquals("Alexa is handled by KITCHEN.", e.message)
        assertFalse(token.configured)
    }

    @Test
    fun theSyncPortStaysClosedWhileAnotherMainPcOfThisHouseholdIsSeen() = runBlocking<Unit> {
        val network = FakeNetwork()
        val yields = AtomicInteger()
        val app = app(
            peers = { desktop ->
                PeerWatch(
                    FakeDiscovery(network),
                    own = { desktop.identity.record("THIS-PC", "test") },
                    port = 5055,
                    onYieldChanged = { yields.incrementAndGet() },
                    log = {},
                )
            },
        )
        app.start().join()
        eventually { app.peers!!.status.value.peers.isEmpty() && app.syncOnLan() }
        val twin = PeerRecord("LAPTOP", PeerTxt.householdHash(app.identity.household.id()), 1L, "e".repeat(32), "1.0")
        network.add(twin)
        eventually {
            app.peers!!.browseNow()
            app.peers!!.twoMasters() == "LAPTOP"
        }
        assertFalse(app.syncOnLan())
        assertTrue("the server is unaffected", app.serverOnLan())
        // The other one joins this PC: it is a client now, and the sync port may listen again.
        network.clearAdded()
        network.add(twin.copy(role = PeerTxt.ROLE_CLIENT))
        eventually {
            app.peers!!.browseNow()
            app.peers!!.twoMasters() == null
        }
        assertTrue(app.syncOnLan())
        assertEquals(2, yields.get())
    }
}
