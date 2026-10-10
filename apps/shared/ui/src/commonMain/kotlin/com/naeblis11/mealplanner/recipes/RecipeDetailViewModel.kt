package com.naeblis11.mealplanner.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.data.AddStatus
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.data.PantryRepository
import com.naeblis11.mealplanner.data.RecipeDetail
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.Stocked
import com.naeblis11.mealplanner.domain.PantryRule
import com.naeblis11.mealplanner.domain.Py
import com.naeblis11.mealplanner.domain.ServingsInput
import com.naeblis11.mealplanner.domain.Week
import com.naeblis11.mealplanner.photos.PhotoProcessor
import com.naeblis11.mealplanner.photos.PhotoWriteException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecipeDetailViewModel(
    private val id: Long,
    private val repository: RecipeRepository,
    private val imagesDir: File,
    private val photoDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val viewDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** P5-R7: the pantry, for the marks and the one-tap add; null leaves both out. */
    private val pantry: PantryRepository? = null,
    /** P5-R7: the meal plan, for Assign to calendar; null leaves it out. */
    private val plans: MealPlanRepository? = null,
    private val clock: () -> LocalDate = LocalDate::now,
    /** Owner, 2026-10-09: the shopping list, for "Add to shopping list" and each ingredient's "+ List"; null leaves both out. */
    private val shopping: ShoppingRepository? = null,
) : ViewModel() {
    private val requestedServings = MutableStateFlow<String?>(null)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _photoVersion = MutableStateFlow(0L)

    /** Bumped whenever the photo changes, so a same-named replacement is decoded again. */
    val photoVersion: StateFlow<Long> = _photoVersion.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)

    /** How many planned meals use this recipe; deleting it removes them too. */
    val plannedMeals: StateFlow<Int> = repository.plannedCount(id)
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    private val _deleted = MutableStateFlow(false)

    /**
     * True once the recipe is deleted; the destination leaves the page on it.
     * State rather than a callback, so a page recreated mid-delete still leaves.
     */
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    /** Set when the stored recipe can't be turned into a page; the screen shows it with a way to delete. */
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /**
     * The recipe page; null while loading, after the recipe is deleted, and while it
     * can't be shown. Built on [viewDispatcher], never on Main. A recipe that can't be
     * built sets [loadError] and the page comes back once the row is fixed; a database
     * error re-subscribes after a pause instead of ending the page for good.
     */
    // What the active pantry covers, by the shopping list's rule; a pantry that can't be read marks nothing.
    private val pantryRules: Flow<List<PantryRule>> = pantry?.observe()
        ?.map { items -> items.filter { it.active }.map { PantryRule(it.name, it.exactMatch) } }
        ?.catch { emit(emptyList()) }
        ?: flowOf(emptyList())

    val view: StateFlow<RecipeView?> = combine(repository.observeDetail(id), requestedServings, pantryRules) { detail, servings, rules ->
        Triple(detail, servings, rules)
    }
        .map { (detail, servings, rules) -> buildView(detail, servings, rules) }
        .flowOn(viewDispatcher)
        .retryWhen { cause, attempt ->
            _loadError.value = cantShow(cause)
            delay(RETRY_MS * (attempt + 1).coerceAtMost(5L))
            true
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun buildView(detail: RecipeDetail?, servings: String?, rules: List<PantryRule>): RecipeView? {
        if (detail == null) {
            _loadError.value = null
            return null
        }
        return try {
            RecipeViews.build(detail, servings, rules).also { _loadError.value = null }
        } catch (e: Exception) {
            _loadError.value = cantShow(e)
            null
        }
    }

    private fun cantShow(e: Throwable) = "This recipe can't be shown: ${e.message ?: e.javaClass.simpleName}"

    /** Scales the page to [servings]; blank goes back to the recipe's own yield. Unreadable text keeps the scale and says why. */
    fun scaleTo(servings: String) {
        when (val input = ServingsInput.read(servings)) {
            ServingsInput.Result.Blank -> requestedServings.value = null
            is ServingsInput.Result.Valid -> requestedServings.value = input.servings
            ServingsInput.Result.Invalid -> _message.value = ServingsInput.HINT
        }
    }

    fun setRating(rating: Int) {
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

    /** P5-R7: one tap adds the ingredient to the pantry (or puts it back), in the server's words. */
    fun addToPantry(name: String) {
        val repo = pantry ?: return
        viewModelScope.launch {
            try {
                val stocked = repo.stock(name)
                _message.value = when (stocked.status) {
                    Stocked.ADDED -> "Added '${stocked.name}' to your pantry."
                    Stocked.PUT_BACK -> "Added '${stocked.name}' back to your pantry."
                    Stocked.ALREADY_THERE -> "'${stocked.name}' is already in your pantry."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "That couldn't be added to your pantry."
            }
        }
    }

    /** Whether this page can add to the shopping list (the buttons show only then). */
    val canShop: Boolean get() = shopping != null

    /**
     * "Add to shopping list": every ingredient, scaled to the servings the page shows, merged into the list as "Add
     * this week" merges a planned meal; what the pantry has is marked on the list, not bought.
     */
    fun addRecipeToShoppingList() {
        val repo = shopping ?: return
        val servings = requestedServings.value
        viewModelScope.launch {
            _message.value = try {
                if (repo.addRecipe(id, servings)) {
                    val name = view.value?.name
                    if (name == null) ADDED_RECIPE_TO_LIST else "Added the ingredients for $name to your shopping list."
                } else {
                    SHOPPING_RECIPE_GONE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SHOPPING_ADD_FAILED
            }
        }
    }

    /** "+ List" on one ingredient: that ingredient at the amount the page shows, in the shopping list's own words. */
    fun addIngredientToShoppingList(ingredient: IngredientView) {
        val repo = shopping ?: return
        val name = ingredient.name.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            _message.value = try {
                when (repo.addItem(name, ingredient.amount, ingredient.unit).status) {
                    AddStatus.ADDED -> "Added '$name' to your shopping list."
                    AddStatus.MERGED -> "Added more '$name' to the one already on your list."
                    AddStatus.DUPLICATE -> "'$name' is already on your shopping list."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SHOPPING_ADD_FAILED
            }
        }
    }

    /** The assign dialog's first day. */
    fun today(): LocalDate = clock()

    /**
     * P5-R7: plans this recipe for [slot] on [date] at [servings] (blank: the recipe's own yield), replacing what was
     * there, as the server's Assign to Calendar. Says what it did, and the meal it replaced.
     */
    fun assign(date: LocalDate, slot: String, servings: String) {
        val repo = plans ?: return
        val chosen = when (val input = ServingsInput.read(servings)) {
            ServingsInput.Result.Blank -> null
            is ServingsInput.Result.Valid -> input.servings
            ServingsInput.Result.Invalid -> {
                _message.value = ServingsInput.HINT
                return
            }
        }
        viewModelScope.launch {
            try {
                val replaced = repo.assignment(date, slot)?.takeIf { it.recipeId != id }?.recipeName
                repo.assign(date, slot, id, chosen)
                _message.value = plannedMessage(slot, date, replaced)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                _message.value = e.message ?: ASSIGN_FAILED
            } catch (e: Exception) {
                _message.value = ASSIGN_FAILED
            }
        }
    }

    /** P5-R7: the server's quick category form. */
    fun setCategory(category: String, subcategory: String) {
        viewModelScope.launch {
            try {
                repository.setCategory(id, category, subcategory)
                _message.value = "Category updated."
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _message.value = e.message ?: "The category could not be saved."
            } catch (e: Exception) {
                _message.value = "The category could not be saved."
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                repository.delete(id)
                _deleted.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "The recipe could not be deleted."
            }
        }
    }

    /** Processes a photo from [open] (camera or gallery) and makes it the recipe's photo. */
    fun setPhoto(open: () -> InputStream) {
        viewModelScope.launch {
            try {
                val uuid = repository.doc(id)?.get("recipe_uuid")?.let { Py.str(it) }
                if (uuid == null) {
                    _message.value = "The photo could not be saved."
                    return@launch
                }
                val name = withContext(photoDispatcher) {
                    val bytes = open().use { it.readBytes() }
                    PhotoProcessor.save(bytes, imagesDir, uuid)
                }
                repository.setImage(id, name)
                _photoVersion.value += 1
                _message.value = "Recipe photo updated."
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                _message.value = "Could not read that photo."
            } catch (e: PhotoWriteException) {
                // On the desktop: a photo Windows refused is the block (how to allow the app, and the notice); any other
                // failure says why, with no path (P7-R10b). The phone says what it always said.
                _message.value = repository.libraryWriteMessage(e) ?: e.message ?: "The photo could not be saved."
            } catch (e: IOException) {
                _message.value = e.message ?: "That file is not a valid image."
            } catch (e: Exception) {
                _message.value = "The photo could not be saved."
            }
        }
    }

    fun removePhoto() {
        viewModelScope.launch {
            try {
                repository.removeImage(id)
                _photoVersion.value += 1
                _message.value = "Recipe photo removed."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "The photo could not be saved."
            }
        }
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun messageShown() {
        _message.value = null
    }

    private companion object {
        const val RETRY_MS = 1_000L
        const val ASSIGN_FAILED = "The meal could not be saved."
    }
}

/** What "Add to shopping list" says when the page has no name to put in it yet. */
const val ADDED_RECIPE_TO_LIST = "Added this recipe's ingredients to your shopping list."

/** "Add to shopping list" on a recipe deleted meanwhile. */
const val SHOPPING_RECIPE_GONE = "This recipe is no longer in your library."

/** Either shopping-list button, when the list couldn't be written. */
const val SHOPPING_ADD_FAILED = "That couldn't be added to your shopping list."

/** What Assign to calendar says: the slot and day, and the meal it replaced. */
fun plannedMessage(slot: String, date: LocalDate, replaced: String?): String =
    "Planned for $slot on ${Week.dayLabel(date)}" + (replaced?.let { ", in place of $it." } ?: ".")
