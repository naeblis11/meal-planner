package com.naeblis11.mealplanner.recipes

import com.naeblis11.mealplanner.ui.LocalLeaveGuard
import com.naeblis11.mealplanner.ui.PlatformBackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.domain.RecipeDefaults
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.SectionLabel
import com.naeblis11.mealplanner.ui.theme.MealColors

data class RowActions(
    val change: (String, (EditorRowState) -> EditorRowState) -> Unit,
    val remove: (String) -> Unit,
    val move: (String, Int) -> Unit,
    val addIngredient: () -> Unit,
    val addSection: () -> Unit,
    /** P5-R9: a drag's drop, the row's key and the index it lands at; null keeps Up and Down alone. */
    val moveTo: ((String, Int) -> Unit)? = null,
)

data class StepActions(
    val change: (String, String) -> Unit,
    val remove: (String) -> Unit,
    val move: (String, Int) -> Unit,
    val add: () -> Unit,
    /** P5-R8, step-editor.js's "Split here": the step's key and the cursor's place in its text. */
    val split: (String, Int) -> Unit = { _, _ -> },
    /** P5-R9: a drag's drop, the step's key and the index it lands at; null keeps Up and Down alone. */
    val moveTo: ((String, Int) -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditScreen(
    state: EditState,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onEdit: ((RecipeForm) -> RecipeForm) -> Unit,
    rowActions: RowActions,
    stepActions: StepActions,
    /**
     * Discard on a switch of section: closes the form, then runs the switch only if the form did close (a close
     * dropped because the page wasn't resumed must not leave the form, and its changes, behind in another section).
     */
    onLeave: (switchSection: () -> Unit) -> Unit = { switchSection -> onCancel(); switchSection() },
) {
    val listState = rememberLazyListState()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    // A switch of section (the rail or the tabs) waiting on the discard prompt; it goes ahead after Discard.
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val leave: () -> Unit = { if (state.dirty) confirmDiscard = true else onCancel() }
    // Back never drops a save in flight, and never drops unsaved changes without asking.
    PlatformBackHandler(enabled = state.saving || state.dirty) { if (!state.saving) confirmDiscard = true }
    // Nor does a tap on the rail or a tab, which would otherwise leave the form behind.
    val leaveGuard = LocalLeaveGuard.current
    val saving by rememberUpdatedState(state.saving)
    if (leaveGuard != null && (state.saving || state.dirty)) {
        DisposableEffect(leaveGuard) {
            val release = leaveGuard.hold { proceed ->
                if (!saving) {
                    pendingLeave = proceed
                    confirmDiscard = true
                }
            }
            onDispose { release() }
        }
    }
    // The error is the list's first item: bring it into view when Save fails from further down.
    LaunchedEffect(state.error) { if (state.error != null && state.form != null) listState.animateScrollToItem(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New recipe" else "Edit recipe") },
                navigationIcon = { TextButton(onClick = leave, enabled = !state.saving) { Text("Cancel") } },
                actions = { TextButton(onClick = onSave, enabled = state.form != null && !state.saving) { Text("Save") } },
            )
        },
    ) { padding ->
        val form = state.form
        if (form == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.Center) {
                // A recipe that could not be loaded shows why instead of spinning.
                if (state.error != null) AttentionBanner(state.error) else CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let { item { AttentionBanner(it) } }
            item { Field("Title", form.name) { v -> onEdit { it.copy(name = v) } } }
            item { Field("Category", form.category) { v -> onEdit { it.copy(category = v) } } }
            item { Suggestions(RecipeDefaults.CATEGORIES) { v -> onEdit { it.copy(category = v) } } }
            item { Field("Subcategory", form.subcategory) { v -> onEdit { it.copy(subcategory = v) } } }
            item { Suggestions(RecipeDefaults.SUBCATEGORIES) { v -> onEdit { it.copy(subcategory = v) } } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Servings", form.servingsAmount, Modifier.weight(1f)) { v -> onEdit { it.copy(servingsAmount = v) } }
                    Field("Unit", form.servingsUnit, Modifier.weight(1f)) { v -> onEdit { it.copy(servingsUnit = v) } }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Oven temperature", form.ovenTempAmount, Modifier.weight(1f)) { v -> onEdit { it.copy(ovenTempAmount = v) } }
                    Field("F or C", form.ovenTempUnit, Modifier.weight(1f)) { v -> onEdit { it.copy(ovenTempUnit = v) } }
                }
            }
            item { Field("Oven time", form.ovenTime) { v -> onEdit { it.copy(ovenTime = v) } } }
            item { Field("Author", form.author) { v -> onEdit { it.copy(author = v) } } }
            item { Field("Source link", form.sourceUrl) { v -> onEdit { it.copy(sourceUrl = v) } } }
            item { Field("Notes (one per line)", form.notes, singleLine = false) { v -> onEdit { it.copy(notes = v) } } }
            item { SectionLabel("Ingredients") }
            item {
                IngredientEditor(
                    form.rows,
                    rowActions.change,
                    rowActions.remove,
                    rowActions.move,
                    rowActions.addIngredient,
                    rowActions.addSection,
                    onMoveTo = rowActions.moveTo,
                )
            }
            item { SectionLabel("Instructions") }
            // One item, so each step row can be measured (P5-R9's drag) wherever the list is scrolled.
            item { StepEditor(form.steps, stepActions) }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false; pendingLeave = null },
            title = { Text("Discard changes?") },
            text = { Text("Your changes to this recipe haven't been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    val next = pendingLeave
                    pendingLeave = null
                    confirmDiscard = false
                    // The form closes first, so the section it leaves for never keeps it (and its changes) behind.
                    if (next == null) onCancel() else onLeave(next)
                }) { Text("Discard", color = MealColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false; pendingLeave = null }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, modifier = modifier)
}

@Composable
private fun Suggestions(options: List<String>, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { option -> AssistChip(onClick = { onPick(option) }, label = { Text(option) }) }
    }
}
