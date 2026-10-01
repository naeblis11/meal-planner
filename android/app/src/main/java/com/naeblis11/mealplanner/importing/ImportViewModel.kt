package com.naeblis11.mealplanner.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.backup.ImportAction
import com.naeblis11.mealplanner.backup.ImportCommitter
import com.naeblis11.mealplanner.backup.ImportDecision
import com.naeblis11.mealplanner.backup.ImportException
import com.naeblis11.mealplanner.backup.ImportOutcome
import com.naeblis11.mealplanner.backup.ImportReader
import com.naeblis11.mealplanner.backup.ImportStager
import com.naeblis11.mealplanner.backup.ImportValidationException
import com.naeblis11.mealplanner.backup.StagedImport
import com.naeblis11.mealplanner.backup.StagedKind
import com.naeblis11.mealplanner.backup.StagedRecipe
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.OrfEditing
import com.naeblis11.mealplanner.domain.RecipeFormatException
import com.naeblis11.mealplanner.recipes.EditorRowState
import com.naeblis11.mealplanner.recipes.RecipeEdits
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One staged recipe as the review screen edits it. */
data class ReviewRow(
    val tempId: Int,
    val kind: StagedKind,
    val originalTitle: String,
    val title: String,
    val category: String,
    val subcategory: String,
    val servingsAmount: String,
    val servingsUnit: String,
    val action: ImportAction,
    val rows: List<EditorRowState>,
    val amountIssues: Int,
)

sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class Reviewing(
        val sourceName: String,
        val rows: List<ReviewRow>,
        val errors: List<Pair<String, String>>,
        val error: String? = null,
        val busy: Boolean = false,
    ) : ImportState
    data class Finished(val message: String) : ImportState
    data class Failed(val message: String) : ImportState
}

/** Read a file, review what it holds, confirm or cancel: the Pi's staged import. Nothing is written before Confirm. */
class ImportViewModel(
    private val repository: RecipeRepository,
    private val imagesDir: File,
    private val newStagingDir: () -> File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var staged: StagedImport? = null
    @Volatile private var stagingDir: File? = null
    // Bumped by every start() and cancel(); a read from an older generation must not touch shared state.
    @Volatile private var generation = 0
    private val lock = Any()

    /** Test hook: waits for the current read or commit to finish. */
    internal suspend fun awaitJob() {
        job?.join()
    }

    fun start(fileName: String, open: () -> InputStream) = start({ fileName }, open)

    /**
     * Reads the file from [open]. [name] looks up its name ("box.mmf", for the
     * reader's type check and the messages); it is a content-resolver query, so
     * it runs on the IO dispatcher with the read, and its failure fails the read.
     */
    fun start(name: () -> String, open: () -> InputStream) {
        job?.cancel()
        discardStaging()
        val gen = ++generation
        _state.value = ImportState.Reading
        job = viewModelScope.launch {
            var dir: File? = null
            var fileName = "the file"
            try {
                val result = withContext(ioDispatcher) {
                    fileName = name()
                    val d = newStagingDir().also { dir = it; synchronized(lock) { if (gen == generation) stagingDir = it } }
                    val bundle = open().use { ImportReader.read(fileName, it, d) }
                    ImportStager.stage(bundle, repository)
                }
                ensureActive()
                if (gen != generation) {
                    dir?.deleteRecursively()
                    return@launch
                }
                if (result.recipes.isEmpty()) {
                    discardStaging()
                    _state.value = ImportState.Failed("No recipes found in $fileName." + errorList(result.errors).let { if (it.isEmpty()) "" else " $it" })
                } else {
                    staged = result
                    _state.value = ImportState.Reviewing(result.sourceName, result.recipes.map { reviewRow(it) }, result.errors)
                }
            } catch (e: CancellationException) {
                // A cancelled read owns nothing the new import or the screen still needs.
                dir?.deleteRecursively()
                throw e
            } catch (e: ImportException) {
                failRead(gen, dir, e.message ?: "Could not read $fileName.")
            } catch (e: IOException) {
                failRead(gen, dir, "Could not read $fileName: ${e.message}")
            } catch (e: Exception) {
                failRead(gen, dir, "Could not read $fileName: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun failRead(gen: Int, dir: File?, message: String) {
        if (gen != generation) {
            // Superseded or cancelled: clean only this run's own folder.
            dir?.deleteRecursively()
            return
        }
        discardStaging()
        dir?.deleteRecursively()
        _state.value = ImportState.Failed(message)
    }

    fun update(tempId: Int, change: (ReviewRow) -> ReviewRow) {
        _state.update { current ->
            if (current !is ImportState.Reviewing || current.busy) current
            else current.copy(rows = current.rows.map { if (it.tempId == tempId) change(it) else it }, error = null)
        }
    }

    fun confirm() {
        val review = _state.value as? ImportState.Reviewing ?: return
        if (review.busy) return
        val toCommit = staged ?: return
        val busy = review.copy(busy = true, error = null)
        if (!_state.compareAndSet(review, busy)) return
        job = viewModelScope.launch {
            fun restore(message: String) {
                _state.compareAndSet(busy, review.copy(error = message, busy = false))
            }
            try {
                val outcome = ImportCommitter(repository, imagesDir).confirm(toCommit, review.rows.map { it.toDecision() })
                discardStaging()
                _state.value = ImportState.Finished(summary(review.sourceName, outcome))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportValidationException) {
                restore(e.message ?: "The import could not be saved.")
            } catch (e: RecipeFormatException) {
                restore("A recipe could not be imported: ${e.message}")
            } catch (e: Exception) {
                restore("The import could not be saved: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancel() {
        val current = _state.value
        if (current is ImportState.Reviewing && current.busy) return
        job?.cancel()
        generation++
        discardStaging()
        _state.value = ImportState.Idle
    }

    fun done() {
        _state.value = ImportState.Idle
    }

    override fun onCleared() = discardStaging()

    private fun discardStaging() {
        synchronized(lock) {
            stagingDir?.deleteRecursively()
            stagingDir = null
            staged = null
        }
    }

    private fun reviewRow(recipe: StagedRecipe): ReviewRow {
        val firstYield = OrfEditing.firstYield(recipe.doc)
        return ReviewRow(
            tempId = recipe.tempId,
            kind = recipe.kind,
            originalTitle = recipe.title,
            title = recipe.title,
            category = RecipeEdits.display(recipe.doc["category"]),
            subcategory = RecipeEdits.display(recipe.doc["subcategory"]),
            servingsAmount = firstYield?.let { RecipeEdits.display(it["amount"]) } ?: "",
            servingsUnit = firstYield?.let { RecipeEdits.display(it["unit"]) }?.ifEmpty { null } ?: "servings",
            action = if (recipe.kind == StagedKind.UPDATE) ImportAction.UPDATE else ImportAction.IMPORT,
            rows = RecipeEdits.editorRowsFor(recipe.doc),
            amountIssues = recipe.amountIssues,
        )
    }

    private fun ReviewRow.toDecision() = ImportDecision(
        tempId = tempId,
        action = action,
        title = title,
        category = category,
        subcategory = subcategory,
        servingsAmount = servingsAmount,
        servingsUnit = servingsUnit,
        ingredientRows = rows.map { it.toSubmitted() },
    )

    // The Pi's flash message after an import.
    private fun summary(source: String, outcome: ImportOutcome): String {
        var text = "Imported ${outcome.imported} recipe(s) from $source"
        if (outcome.updated > 0) text += ", updated ${outcome.updated}"
        if (outcome.ignored.isNotEmpty()) text += ", ignored ${outcome.ignored.size}: ${cap(outcome.ignored)}"
        if (outcome.errors.isNotEmpty()) text += ", ${outcome.errors.size} could not be read: ${errorList(outcome.errors)}"
        return text
    }

    private fun cap(names: List<String>): String {
        val shown = names.take(10).joinToString(", ")
        return if (names.size > 10) "$shown, and ${names.size - 10} more" else shown
    }

    private fun errorList(errors: List<Pair<String, String>>): String {
        val shown = errors.take(10).joinToString("; ") { "${it.first}: ${it.second}" }
        return if (errors.size > 10) "$shown; and ${errors.size - 10} more" else shown
    }
}
