package com.naeblis11.mealplanner.app

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainActivity
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.settings.NEVER_SYNCED
import com.naeblis11.mealplanner.sync.ExchangeReply
import com.naeblis11.mealplanner.sync.ExchangeRequest
import com.naeblis11.mealplanner.sync.FoundPc
import com.naeblis11.mealplanner.sync.MemoryPairingStore
import com.naeblis11.mealplanner.sync.Paired
import com.naeblis11.mealplanner.sync.PcFinder
import com.naeblis11.mealplanner.sync.PhonePairing
import com.naeblis11.mealplanner.sync.PhoneSync
import com.naeblis11.mealplanner.sync.PhoneSyncControls
import com.naeblis11.mealplanner.sync.SyncConnector
import com.naeblis11.mealplanner.sync.SyncHub
import com.naeblis11.mealplanner.sync.SyncTransport
import com.naeblis11.mealplanner.sync.SyncWire
import com.naeblis11.mealplanner.ui.JOIN_HINT
import com.naeblis11.mealplanner.ui.MERGE
import com.naeblis11.mealplanner.ui.SYNC_ACTION_TAG
import com.naeblis11.mealplanner.ui.firstSyncTitle
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LAN sync stage 2c: the phone app's own wiring. A paired phone syncs from the Recipes bar with a hub in this process
 * (no socket): its connector stands in for SyncHttp, and everything else is the real app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class SyncWiringTest {
    private val app = ApplicationProvider.getApplicationContext<MealPlannerApplication>()
    private val hubDb = Room.inMemoryDatabaseBuilder<AppDatabase>(app).setDriver(AndroidSQLiteDriver()).addCallback(SyncSchema.Callback).build()
    private val hubImages: File = Files.createTempDirectory("hub-images").toFile()
    private val hub = SyncHub(hubDb, RecipeRepository(hubDb, hubImages), hubImages)
    private val pairing = PhonePairing(
        listOf("192.0.2.20"), 50443, "ab".repeat(32), "KITCHEN", "12".repeat(32), "f".repeat(32), "0123456789abcdef0123456789abcdef",
    )

    // The PC: this test's hub, in-process.
    private val connector = object : SyncConnector {
        override suspend fun pair(host: String, port: Int, pin: String?, code: String, deviceId: String, name: String, kind: String): Paired =
            error("This test doesn't pair.")

        override fun transport(host: String, port: Int, pin: String, deviceKey: String): SyncTransport = object : SyncTransport {
            override suspend fun exchange(request: ExchangeRequest): ExchangeReply {
                val phone = SyncSchema.deviceId(app.container.database)
                return SyncWire.decodeReply(SyncWire.encode(hub.exchange(phone, SyncWire.decodeRequest(SyncWire.encode(request)))))
            }

            override suspend fun photo(uuid: String): ByteArray? = hub.photo(uuid)

            override suspend fun sendPhoto(uuid: String, bytes: ByteArray) {
                hub.receivePhoto(uuid, bytes)
            }
        }

        override suspend fun leave(host: String, port: Int, pin: String, deviceKey: String) = Unit
    }

    private val nowhere = object : PcFinder {
        override suspend fun find(timeoutMillis: Long): List<FoundPc> = emptyList()
    }

    // How many times the app made its sync: once per process, whatever the activities do.
    private val made = AtomicInteger()

    private val compose = createAndroidComposeRule<MainActivity>()

    // Before the activity starts: a recipe on the phone, and its sync over the in-process hub.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            @Suppress("UNCHECKED_CAST")
            runBlocking { app.container.recipes.save(RecipeYaml.load("recipe_name: Stew\ningredients: []\nsteps: []\n") as YamlMap) }
            app.syncFactory = {
                made.incrementAndGet()
                val engine = PhoneSync(
                    app.container.database, app.container.recipes, app.container.imagesDir, MemoryPairingStore(pairing), nowhere, "Pixel", connector,
                )
                PhoneSyncControls(engine, app.container.recipes, app.container.imagesDir, wifi = { true })
            }
        }
    }).around(compose)

    @After
    fun tearDown() {
        hubDb.close()
        hubImages.deleteRecursively()
    }

    @Test
    fun aPairedPhoneSyncsFromTheRecipesBar() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(SYNC_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(SYNC_ACTION_TAG).assertContentDescriptionEquals(NEVER_SYNCED).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(firstSyncTitle("KITCHEN")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(MERGE).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Sent 1 change, got", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Just now").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("Stew"), runBlocking { RecipeRepository(hubDb, hubImages).allRecipes().map { it.name } })
        compose.onAllNodesWithText(JOIN_HINT).assertCountEquals(0)
    }

    @Test
    fun anOutcomeShowingWhenTheActivityIsRecreatedIsShownAgain() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(SYNC_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(SYNC_ACTION_TAG).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(firstSyncTitle("KITCHEN")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(MERGE).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Sent 1 change, got", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val said = app.syncControls.outcome.value
        assertNotNull(said)

        // A rotation mid-snackbar cancels its effect: that isn't the user having seen it, so the new activity says it again.
        compose.activityRule.scenario.recreate()
        assertEquals(said, app.syncControls.outcome.value)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(said!!).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aRecreatedActivityAndASecondOneShareTheOneSync() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(SYNC_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
        val first = compose.activity.appSync()
        assertSame(app.syncControls, first)

        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { assertSame(first, it.appSync()) }
        val second = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertSame(first, second.get().appSync())
        second.pause().stop().destroy()

        assertEquals(1, made.get())
    }
}

/** The real factory under Robolectric: a fresh phone isn't paired (and never touched the Keystore to know it). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class DefaultSyncTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun aFreshPhoneShowsTheHintAndNoSyncButton() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(JOIN_HINT).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag(SYNC_ACTION_TAG).assertCountEquals(0)
        val app = ApplicationProvider.getApplicationContext<MealPlannerApplication>()
        assertSame(app.syncControls, compose.activity.appSync())
    }
}
