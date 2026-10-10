package com.naeblis11.mealplanner.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-R2: a meal's Google event id comes from the household, the day and the slot, in Google's own alphabet. */
class GoogleEventIdsTest {
    @Test
    fun base32HexIsRfc4648sInLowerCaseWithoutPadding() {
        val cases = listOf(
            "" to "", "f" to "co", "fo" to "cpng", "foo" to "cpnmu", "foob" to "cpnmuog", "fooba" to "cpnmuoj1", "foobar" to "cpnmuoj1e8",
        )
        for ((plain, encoded) in cases) assertEquals(plain, encoded, GoogleEventIds.base32Hex(plain.toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun anIdIsTheHouseholdDayAndSlotHashedIntoGooglesAlphabet() {
        val household = "0123456789abcdef0123456789abcdef"
        assertEquals("mprpff3kuv8linbj6ri8fnpol38k7oe4b10nq2fful", GoogleEventIds.forMeal(household, LocalDate.of(2026, 10, 5), "Dinner"))
        assertEquals("mp11ns0ve1o7d2dqq5du5e25jhlrdau6dpt3mv4ebu", GoogleEventIds.forMeal(household, LocalDate.of(2026, 10, 5), "Breakfast"))
        assertEquals("mpond5k4vcsslo5beac0oa79rvtpncq46ree4b555n", GoogleEventIds.forMeal(household, LocalDate.of(2026, 10, 6), "Dinner"))
    }

    @Test
    fun everyIdIsOneGoogleTakes() {
        val id = GoogleEventIds.forMeal("ffffffffffffffffffffffffffffffff", LocalDate.of(2027, 1, 1), "Lunch")
        assertEquals(42, id.length)
        assertTrue(id.startsWith(GoogleEventIds.PREFIX))
        assertTrue(GoogleEventIds.isValid(id))
        assertFalse(GoogleEventIds.isValid("mpw1"))
        assertFalse(GoogleEventIds.isValid("mpabc_def"))
        assertFalse(GoogleEventIds.isValid("MPABCDEF"))
    }
}
