package com.naeblis11.mealplanner.desktop.peers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P6-R7: our own record, who yields, a tie, the same household, and the bounds. */
class PeerDecisionTest {
    private fun id(n: Int) = "%032x".format(n)

    private fun peer(name: String, hash: Char, since: Long, n: Int) = PeerRecord(name, hash.toString().repeat(16), since, id(n), "1.0")

    private val me = peer("THIS-PC", '5', 500L, 0)

    @Test
    fun ourOwnRecordAndRepeatsAreNotPeers() {
        val kitchen = peer("KITCHEN", '9', 900L, 1)
        val status = PeerDecision.decide(me, listOf(me, me.copy(name = "THIS-PC (2)"), kitchen, kitchen))
        assertEquals(listOf("KITCHEN"), status.peers)
    }

    @Test
    fun nobodyElseMeansNoNotice() {
        assertEquals(PeerStatus(), PeerDecision.decide(me, listOf(me)))
        assertEquals(PeerStatus(), PeerDecision.decide(me, emptyList()))
    }

    @Test
    fun theNewerHouseholdYieldsToTheOlderAndBothAgree() {
        val older = peer("DEN", '9', 100L, 1)
        val mine = PeerDecision.decide(me, listOf(me, older))
        assertEquals("DEN", mine.yieldTo)
        assertEquals(PeerMessages.yielding("DEN"), mine.notice)
        val theirs = PeerDecision.decide(older, listOf(me, older))
        assertNull(theirs.yieldTo)
        assertEquals(PeerMessages.keeping("THIS-PC"), theirs.notice)
    }

    @Test
    fun aTieGoesToTheLowerHouseholdHash() {
        val lower = peer("DEN", '1', 500L, 1)
        assertEquals("DEN", PeerDecision.decide(me, listOf(lower)).yieldTo)
        assertNull(PeerDecision.decide(lower, listOf(me)).yieldTo)
    }

    @Test
    fun theSameHouseholdIsANoticeOnly() {
        val copy = peer("LAPTOP", '5', 100L, 1)
        val status = PeerDecision.decide(me, listOf(copy))
        assertNull(status.yieldTo)
        assertEquals(PeerMessages.sameHousehold("LAPTOP"), status.notice)
    }

    @Test
    fun yieldingWinsAndTheOldestHouseholdIsNamed() {
        val seen = listOf(peer("NEWER", '9', 600L, 1), peer("OLD", '8', 300L, 2), peer("OLDEST", '7', 100L, 3), peer("COPY", '5', 50L, 4))
        val status = PeerDecision.decide(me, seen)
        assertEquals("OLDEST", status.yieldTo)
        assertEquals(PeerMessages.yielding("OLDEST"), status.notice)
        assertEquals(listOf("NEWER", "OLD", "OLDEST", "COPY"), status.peers)
    }

    @Test
    fun theOlderHouseholdIsFoundEvenPastThe32Kept() {
        val seen = (1..40).map { peer("PC$it", '9', 900L + it, it) } + peer("OLDEST", '7', 100L, 41)
        val status = PeerDecision.decide(me, seen)
        assertEquals("OLDEST", status.yieldTo)
        assertEquals(PeerMessages.yielding("OLDEST"), status.notice)
        assertEquals(32, status.peers.size)
    }

    @Test
    fun atMost32PeersAreKept() {
        val many = (1..40).map { peer("PC$it", '9', 900L + it, it) }
        assertEquals(32, PeerDecision.decide(me, many).peers.size)
    }
}
