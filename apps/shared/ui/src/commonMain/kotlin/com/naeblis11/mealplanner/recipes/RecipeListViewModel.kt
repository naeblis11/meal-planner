package com.naeblis11.mealplanner.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.app.MemorySettings
import com.naeblis11.mealplanner.app.SettingsStore
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.RecipeSummary
import com.naeblis11.mealplanner.domain.CategoryGroup
import com.naeblis11.mealplanner.domain.RecipeGrouping
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class RecipeListViewModel(
    private val repository: RecipeRepository,
    /** Where the collapsed categories are kept, so they are the same after a restart (owner, 2026-10-10). */
    private val settings: SettingsStore = MemorySettings(),
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _book = MutableStateFlow<BookFilter>(BookFilter.All)

    /** Which recipes the cookbook filter keeps: every one, one cookbook's, or the ones from no cookbook. */
    val book: StateFlow<BookFilter> = _book.asStateFlow()

    private val _minRating = MutableStateFlow(0)

    /** The fewest stars a recipe needs to show, 1 to 5; 0 shows unrated recipes too (owner, 2026-10-10). */
    val minRating: StateFlow<Int> = _minRating.asStateFlow()

    private val _collapsed = MutableStateFlow(readCollapsed())

    /**
     * The collapsed headings, by [categoryKey] and [subcategoryKey]. A heading whose category or subcategory is
     * renamed or emptied simply stops matching; nothing else depends on it.
     */
    val collapsed: StateFlow<Set<String>> = _collapsed.asStateFlow()

    /** Every cookbook named in the library, for the filter; not narrowed by the search, so it doesn't jump around. */
    val books: StateFlow<List<String>> = repository.summaries("")
        .map { all -> all.mapNotNull { it.book }.distinct().sortedBy { it.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The library grouped like the web app's; null until the first load. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val groups: StateFlow<List<CategoryGroup<RecipeSummary>>?> = combine(_query, _book, _minRating) { query, book, minRating -> Triple(query, book, minRating) }
        .flatMapLatest { (query, book, minRating) ->
            repository.summaries(query).map { found -> found.filter { book.keeps(it) && (it.rating ?: 0) >= minRating } }
        }
        .map { RecipeGrouping.group(it, { r -> r.name }, { r -> r.category }, { r -> r.subcategory }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(text: String) {
        _query.value = text
    }

    fun setBook(book: BookFilter) {
        _book.value = book
    }

    /** Keeps only the recipes rated [stars] or more; 0 lifts the filter. */
    fun setMinRating(stars: Int) {
        _minRating.value = stars.coerceIn(0, 5)
    }

    /** Collapses the heading [key] when open, opens it when collapsed. */
    fun toggle(key: String) = save { if (key in it) it - key else it + key }

    /** Collapses every category heading of the library as it is shown; subcategories keep their own state. */
    fun collapseAll(categories: List<String>) = save { it + categories.map(::categoryKey) }

    /** Opens every heading. */
    fun expandAll() = save { emptySet() }

    private fun save(change: (Set<String>) -> Set<String>) {
        _collapsed.update(change)
        settings.put(mapOf(COLLAPSED_KEY to _collapsed.value.sorted().joinToString(SEPARATOR)))
    }

    private fun readCollapsed(): Set<String> =
        settings.getString(COLLAPSED_KEY)?.split(SEPARATOR)?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    companion object {
        const val COLLAPSED_KEY = "recipe_list_collapsed"

        // A line break: no category or subcategory name holds one.
        private const val SEPARATOR = "\n"

        fun categoryKey(category: String) = "c:$category"

        fun subcategoryKey(category: String, subcategory: String) = "s:$category/$subcategory"
    }
}

/** Which recipes the list's cookbook filter keeps. */
sealed interface BookFilter {
    fun keeps(recipe: RecipeSummary): Boolean

    /** Every recipe. */
    data object All : BookFilter {
        override fun keeps(recipe: RecipeSummary) = true
    }

    /** Only the recipes from [book]. */
    data class Only(val book: String) : BookFilter {
        override fun keeps(recipe: RecipeSummary) = recipe.book == book
    }

    /** Only the recipes from no cookbook: the owner's own, with every cookbook put aside (owner, 2026-10-10). */
    data object NoBook : BookFilter {
        override fun keeps(recipe: RecipeSummary) = recipe.book == null
    }
}
