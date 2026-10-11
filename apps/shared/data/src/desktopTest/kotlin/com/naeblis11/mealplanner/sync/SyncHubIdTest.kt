package com.naeblis11.mealplanner.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2b: the pair reply carries the hub's id, so it must be the one every exchange reply carries. */
class SyncHubIdTest {
    @Test
    fun theHubIdIsMadeOnceAndIsTheOneEveryReplyCarries() = runBlocking<Unit> {
        SyncHarness().use { h ->
            val id = h.hub.hubId()
            assertEquals(id, h.hub.hubId())
            assertTrue(Regex("[0-9a-f]{32}").matches(id))
            h.client.sync(FirstSync.MERGE)
            assertEquals(id, h.lastReply!!.hubId)
        }
    }
}
