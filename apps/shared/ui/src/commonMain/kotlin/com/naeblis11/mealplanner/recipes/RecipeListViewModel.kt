package com.naeblis11.mealplanner.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.RecipeSummary
import com.naeblis11.mealplanner.domain.CategoryGroup
import com.naeblis11.mealplanner.domain.RecipeGrouping
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecipeListViewModel(private val repository: RecipeRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _book = MutableStateFlow<String?>(null)

    /** The cookbook the list is narrowed to, or null for every recipe. */
    val book: StateFlow<String?> = _book.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** A rating that couldn't be saved; the list's snackbar says so once. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Every cookbook named in the library, for the filter; not narrowed by the search, so it doesn't jump around. */
    val books: StateFlow<List<String>> = repository.summaries("")
        .map { all -> all.mapNotNull { it.book }.distinct().sortedBy { it.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The library grouped like the web app's; null until the first load. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val groups: StateFlow<List<CategoryGroup<RecipeSummary>>?> = combine(_query, _book) { query, book -> query to book }
        .flatMapLatest { (query, book) ->
            repository.summaries(query).map { found -> if (book == null) found else found.filter { it.book == book } }
        }
        .map { RecipeGrouping.group(it, { r -> r.name }, { r -> r.category }, { r -> r.subcategory }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(text: String) {
        _query.value = text
    }

    fun setBook(book: String?) {
        _book.value = book
    }

    /** P5-R7: the list's stars, 1 to 5, or 0 to clear, written to the recipe as the recipe page's are. */
    fun rate(id: Long, rating: Int) {
        viewModelScope.launch {
            try {
                repository.setRating(id, rating)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Why, on the desktop: the recipe file is missing, or was changed outside the app.
                _message.value = e.message ?: "The rating could not be saved."
            } catch (e: Exception) {
                _message.value = "The rating could not be saved."
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }
}
