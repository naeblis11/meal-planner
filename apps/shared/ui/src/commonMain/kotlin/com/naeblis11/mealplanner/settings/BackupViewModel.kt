package com.naeblis11.mealplanner.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.backup.BackupExporter
import com.naeblis11.mealplanner.data.RecipeRepository
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BackupState {
    data object Idle : BackupState
    data object Working : BackupState
    data class Done(val message: String) : BackupState
    data class Failed(val message: String) : BackupState
}

class BackupViewModel(
    private val repository: RecipeRepository,
    private val imagesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    /**
     * Writes the backup zip to [open] (called off the main thread). On failure
     * the destination is deleted with [deleteDestination]: a half-written zip
     * can still look readable. Ignored while an export is already running.
     */
    fun export(open: () -> OutputStream, deleteDestination: () -> Unit) {
        if (_state.value == BackupState.Working) return
        _state.value = BackupState.Working
        viewModelScope.launch {
            try {
                val summary = withContext(ioDispatcher) { BackupExporter(repository, imagesDir).export(open()) }
                _state.value = BackupState.Done("Saved ${summary.recipes} recipe(s) and ${summary.images} photo(s).")
            } catch (e: CancellationException) {
                withContext(NonCancellable + ioDispatcher) { runCatching { deleteDestination() } }
                throw e
            } catch (e: Exception) {
                withContext(ioDispatcher) { runCatching { deleteDestination() } }
                _state.value = BackupState.Failed("The backup could not be saved: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun dismiss() {
        _state.value = BackupState.Idle
    }
}
