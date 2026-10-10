package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** app.py `_book_name`: the cookbook a recipe's ORF `source_book` names. */
class BooksTest {
    @Test
    fun readsATitle() = assertEquals("Flanders Family Cookbook", Books.name("\"Flanders Family Cookbook \""))

    @Test
    fun joinsAListOfTitles() = assertEquals("Book One, Book Two", Books.name("""["Book One", " ", "Book Two"]"""))

    @Test
    fun noBook() {
        assertNull(Books.name(null))
        assertNull(Books.name("\"  \""))
        assertNull(Books.name("""{"title": "x"}"""))
        assertNull(Books.name("[]"))
    }
}
