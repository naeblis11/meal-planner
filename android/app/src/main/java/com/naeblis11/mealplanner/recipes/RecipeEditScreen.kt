package com.naeblis11.mealplanner.recipes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
)

data class StepActions(
    val change: (String, String) -> Unit,
    val remove: (String) -> Unit,
    val move: (String, Int) -> Unit,
    val add: () -> Unit,
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
) {
    val listState = rememberLazyListState()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val leave: () -> Unit = { if (state.dirty) confirmDiscard = true else onCancel() }
    // Back never drops a save in flight, and never drops unsaved changes without asking.
    BackHandler(enabled = state.saving || state.dirty) { if (!state.saving) confirmDiscard = true }
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
                IngredientEditor(form.rows, rowActions.change, rowActions.remove, rowActions.move, rowActions.addIngredient, rowActions.addSection)
            }
            item { SectionLabel("Instructions") }
            items(form.steps, key = { it.key }) { step ->
                val number = form.steps.indexOf(step) + 1
                Column {
                    Field("Step $number", step.text, singleLine = false) { v -> stepActions.change(step.key, v) }
                    EditorRowButtons(
                        "step $number",
                        onUp = { stepActions.move(step.key, -1) },
                        onDown = { stepActions.move(step.key, 1) },
                        onRemove = { stepActions.remove(step.key) },
                    )
                }
            }
            item { OutlinedButton(onClick = stepActions.add) { Text("Add step") } }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your changes to this recipe haven't been saved.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onCancel() }) { Text("Discard", color = MealColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
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
