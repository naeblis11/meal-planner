package com.naeblis11.mealplanner.desktop.peers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6-R3, P6-R11: the record's six TXT keys, and every way a peer's record is refused. */
class PeerTxtTest {
    private val record = PeerRecord("KITCHEN", "0123456789abcdef", 1_700_000_000_000L, "0123456789abcdef0123456789abcdef", "1.0")

    @Test
    fun aRecordGoesOutAsSixKeysAndComesBack() {
        val txt = PeerTxt.encode(record)
        assertEquals(listOf("v", "role", "hh", "since", "id", "app"), txt.keys.toList())
        assertEquals("1", txt["v"])
        assertEquals("master", txt["role"])
        assertEquals("1700000000000", txt["since"])
        assertEquals(record, PeerTxt.decode("KITCHEN", txt))
    }

    @Test
    fun aMissingOrGarbledValueMeansThePeerIsIgnored() {
        val good = PeerTxt.encode(record)
        for (key in good.keys) assertNull(key, PeerTxt.decode("KITCHEN", good - key))
        val garbled = listOf(
            "v" to "2", "v" to null, "role" to "client",
            "hh" to "0123456789ABCDEF", "hh" to "0123456789abcde", "hh" to "0123456789abcdeg",
            "since" to "-5", "since" to "12x", "since" to "", "since" to "1".repeat(19),
            "id" to "0123456789abcdef", "id" to "0123456789abcdef0123456789abcdeZ",
            "app" to "", "app" to "1.0\u0007", "app" to "x".repeat(65),
        )
        for ((key, value) in garbled) assertNull("$key=$value", PeerTxt.decode("KITCHEN", good + (key to value)))
        assertNull(PeerTxt.decode(null, good))
        assertNull(PeerTxt.decode(" \u0007 ", good))
    }

    @Test
    fun keysItDoesNotKnowAreIgnored() {
        assertEquals(record, PeerTxt.decode("KITCHEN", PeerTxt.encode(record) + ("extra" to "anything")))
    }

    @Test
    fun namesAreCleanedAndBoundedTo63Bytes() {
        val txt = PeerTxt.encode(record)
        assertEquals("A".repeat(63), PeerTxt.decode("A".repeat(70), txt)!!.name)
        assertEquals("Kitchen", PeerTxt.decode(" Kit\u0007chen\n", txt)!!.name)
    }

    @Test
    fun bidiAndInvisibleCharactersAreDroppedFromNames() {
        val txt = PeerTxt.encode(record)
        assertEquals("Kitchen", PeerTxt.decode("Kit\u202Echen", txt)!!.name)
        assertEquals("Kitchen", PeerTxt.decode("Kit\u2028chen", txt)!!.name)
        assertEquals("Kitchen", PeerTxt.decode("Kit\u2029chen", txt)!!.name)
        assertEquals("Kitchen", PeerTxt.decode("Kit\u200Bchen", txt)!!.name)
        assertEquals("Kitchen", PeerTxt.decode("Kit\uE000chen", txt)!!.name)
        assertEquals("Kitchen", PeerTxt.decode("Kit\u0378chen", txt)!!.name)
        assertEquals("Kitchen", PeerTxt.decode("Kit\uD800chen", txt)!!.name)
        assertNull(PeerTxt.decode("\u200B\u200B", txt))
        assertNull(PeerTxt.cleanName("\u202E \u2028"))
    }

    @Test
    fun combiningMarksAreDroppedFromNamesButComposedLettersStay() {
        // Stacked non-spacing marks (Mn) and enclosing marks (Me) can pile over the banner's text around the name.
        val txt = PeerTxt.encode(record)
        assertEquals("Kitchen", PeerTxt.decode("Kit\u0336\u0336\u0336chen", txt)!!.name)
        assertEquals("Kitchen", PeerTxt.decode("K\u0489itchen\u20DD", txt)!!.name)
        assertEquals("Kitchen", PeerTxt.decode("Kitchen" + "\u030D".repeat(40), txt)!!.name)
        assertNull(PeerTxt.cleanName("\u0301\u20DD"))
        // An accent written as a letter and a mark becomes the one accented letter, as Windows would show it.
        assertEquals("Jos\u00E9", PeerTxt.cleanName("Jose\u0301"))
        assertEquals("Jos\u00E9", PeerTxt.cleanName("Jos\u00E9"))
    }

    @Test
    fun sinceMustBePositive() {
        val good = PeerTxt.encode(record)
        for (value in listOf("0", "000", "000000000000000000")) {
            assertNull(value, PeerTxt.decode("KITCHEN", good + ("since" to value)))
        }
        assertEquals(5L, PeerTxt.decode("KITCHEN", good + ("since" to "0005"))!!.since)
        assertEquals(999_999_999_999_999_999L, PeerTxt.decode("KITCHEN", good + ("since" to "999999999999999999"))!!.since)
    }

    @Test
    fun truncationNeverCutsACharacterInTwo() {
        val accented = PeerTxt.truncateUtf8("\u00E9".repeat(40), 63)
        assertEquals(31, accented.length)
        val soup = PeerTxt.truncateUtf8("\uD83C\uDF72".repeat(20), 63)
        assertEquals("\uD83C\uDF72".repeat(15), soup)
        for (text in listOf(accented, soup)) assertTrue(text.toByteArray(Charsets.UTF_8).size <= 63)
        assertEquals("abc", PeerTxt.truncateUtf8("abc", 63))
    }

    @Test
    fun theHouseholdHashIsTheStartOfItsSha256() {
        assertEquals("d65df89f702eec58", PeerTxt.householdHash("000102030405060708090a0b0c0d0e0f"))
    }

    @Test
    fun thePcNameIsComputernameElseTheHostName() {
        val env = mapOf("COMPUTERNAME" to "KITCHEN-PC")
        assertEquals("KITCHEN-PC", PeerTxt.pcName(env = { env[it] }, host = { error("not asked") }))
        assertEquals("den", PeerTxt.pcName(env = { null }, host = { "den" }))
        assertEquals(PeerTxt.FALLBACK_NAME, PeerTxt.pcName(env = { null }, host = { throw java.net.UnknownHostException("none") }))
        assertEquals("B".repeat(63), PeerTxt.pcName(env = { "B".repeat(80) }, host = { null }))
    }
}
