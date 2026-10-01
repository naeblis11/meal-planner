package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ServingsInputTest {
    @Test
    fun readsServings() {
        assertEquals(ServingsInput.Result.Blank, ServingsInput.read(""))
        assertEquals(ServingsInput.Result.Blank, ServingsInput.read("   "))
        assertEquals(ServingsInput.Result.Blank, ServingsInput.read(null))
        assertEquals(ServingsInput.Result.Valid("4"), ServingsInput.read(" 4 "))
        assertEquals(ServingsInput.Result.Valid("1 1/2"), ServingsInput.read("1.5"))
        assertEquals(ServingsInput.Result.Valid("1/2"), ServingsInput.read("1/2"))
        assertEquals(ServingsInput.Result.Invalid, ServingsInput.read("lots"))
        assertEquals(ServingsInput.Result.Invalid, ServingsInput.read("0"))
        assertEquals(ServingsInput.Result.Invalid, ServingsInput.read("-2"))
    }
}
