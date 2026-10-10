package com.naeblis11.mealplanner.desktop.peers

import com.naeblis11.mealplanner.data.AppMetaEntity
import com.naeblis11.mealplanner.data.Household
import com.naeblis11.mealplanner.desktop.DESKTOP_DB
import com.naeblis11.mealplanner.desktop.DesktopApp
import com.naeblis11.mealplanner.desktop.MapSettings
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** P6-R3, P6-R4: this PC's instance id, and its household's creation time from the database file. */
class PeerIdentityTest {
    private val dir: File = Files.createTempDirectory("mp-peer-identity").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })
    private val dbFile = File(dir, DESKTOP_DB)

    @After
    fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    @Test
    fun theInstanceIdIsMadeOnceAndKept() = runBlocking {
        val first = PeerIdentity(app.container.database, dbFile) { ByteArray(16) { it.toByte() } }.instanceId()
        assertEquals("000102030405060708090a0b0c0d0e0f", first)
        // Another instance, even with other random bytes, finds the one kept in app_meta.
        assertEquals(first, PeerIdentity(app.container.database, dbFile) { ByteArray(16) { 7 } }.instanceId())
        assertEquals(first, app.container.database.appMetaDao().get(PeerIdentity.INSTANCE_KEY))
    }

    @Test
    fun manyFirstAsksAtOnceAgreeOnOneId() = runBlocking {
        val made = AtomicInteger()
        val identity = PeerIdentity(app.container.database, dbFile) {
            val n = made.incrementAndGet()
            ByteArray(16) { n.toByte() }
        }
        val ids = (1..8).map { async(Dispatchers.IO) { identity.instanceId() } }.awaitAll()
        assertEquals(1, ids.toSet().size)
    }

    @Test
    fun aHouseholdMadeBeforeThisPlanDatesFromTheDatabaseFile() = runBlocking {
        app.container.database.appMetaDao().put(AppMetaEntity(Household.KEY, "000102030405060708090a0b0c0d0e0f"))
        val made = PeerIdentity.fileCreated(dbFile)!!
        assertEquals(made, app.identity.household.created())
        assertEquals(made.toString(), app.container.database.appMetaDao().get(Household.CREATED_KEY))
        assertNull(PeerIdentity.fileCreated(File(dir, "missing.db")))
    }

    @Test
    fun theRecordCarriesTheHashTheTimeAndTheIdButNeverTheHouseholdId() = runBlocking {
        val identity = PeerIdentity(app.container.database, dbFile)
        val record = identity.record(" KITCHEN\u0007 ", "1.2\u0001")
        val household = identity.household.id()
        assertEquals(
            PeerRecord("KITCHEN", PeerTxt.householdHash(household), identity.household.created(), identity.instanceId(), "1.2"),
            record,
        )
        assertFalse(PeerTxt.encode(record).values.any { it.contains(household) })
    }
}
