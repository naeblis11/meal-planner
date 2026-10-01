package com.naeblis11.mealplanner.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.data.RecipeDetail
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.Py
import com.naeblis11.mealplanner.domain.ServingsInput
import com.naeblis11.mealplanner.photos.PhotoProcessor
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
    val view: StateFlow<RecipeView?> = combine(repository.observeDetail(id), requestedServings) { detail, servings -> detail to servings }
        .map { (detail, servings) -> buildView(detail, servings) }
        .flowOn(viewDispatcher)
        .retryWhen { cause, attempt ->
            _loadError.value = cantShow(cause)
            delay(RETRY_MS * (attempt + 1).coerceAtMost(5L))
            true
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun buildView(detail: RecipeDetail?, servings: String?): RecipeView? {
        if (detail == null) {
            _loadError.value = null
            return null
        }
        return try {
            RecipeViews.build(detail, servings).also { _loadError.value = null }
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
            } catch (e: Exception) {
                _message.value = "The rating could not be saved."
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
    }
}
