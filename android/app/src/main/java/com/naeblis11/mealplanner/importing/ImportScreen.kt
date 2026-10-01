package com.naeblis11.mealplanner.importing

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.naeblis11.mealplanner.backup.ImportAction
import com.naeblis11.mealplanner.backup.StagedKind
import com.naeblis11.mealplanner.recipes.IngredientEditor
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.Pill
import com.naeblis11.mealplanner.ui.theme.MealColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    state: ImportState,
    onUpdate: (Int, (ReviewRow) -> ReviewRow) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onBackToRecipes: () -> Unit = onDone,
) {
    val busy = state is ImportState.Reviewing && state.busy
    val listState = rememberLazyListState()
    val reviewError = (state as? ImportState.Reviewing)?.error
    // The error sits at the top of the review: bring it into view when Confirm fails from further down.
    LaunchedEffect(reviewError) { if (reviewError != null) listState.animateScrollToItem(0) }
    BackHandler { if (busy) return@BackHandler; if (state is ImportState.Reviewing || state is ImportState.Reading) onCancel() else onDone() }
    // A restored (or already finished) import has nothing to show; leave rather than spin.
    if (state is ImportState.Idle) LaunchedEffect(Unit) { onDone() }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Import recipes") }) },
        bottomBar = {
            if (state is ImportState.Reviewing) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = onConfirm, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Confirm") }
                }
            }
        },
    ) { padding ->
        when (state) {
            ImportState.Idle -> Box(Modifier.fillMaxSize().padding(padding))
            ImportState.Reading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ImportState.Finished -> Message(padding, state.message, "Back to recipes", onBackToRecipes)
            is ImportState.Failed -> Message(padding, state.message, "Back to recipes", onBackToRecipes)
            is ImportState.Reviewing -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text("From ${state.sourceName}", color = MealColors.Muted) }
                state.error?.let { item { AttentionBanner(it) } }
                items(state.rows, key = { it.tempId }) { row -> ReviewCard(row) { change -> onUpdate(row.tempId, change) } }
                if (state.errors.isNotEmpty()) {
                    item { Text("Could not be read", style = MaterialTheme.typography.titleMedium) }
                    items(state.errors.take(20)) { (item, reason) -> Text("$item: $reason", color = MealColors.Danger) }
                    if (state.errors.size > 20) item { Text("and ${state.errors.size - 20} more", color = MealColors.Muted) }
                }
            }
        }
    }
}

@Composable
private fun Message(padding: PaddingValues, text: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text)
        Button(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun ReviewCard(row: ReviewRow, change: ((ReviewRow) -> ReviewRow) -> Unit) {
    var showIngredients by rememberSaveable(row.tempId) { mutableStateOf(row.amountIssues > 0) }
    var next by rememberSaveable(row.tempId) { mutableStateOf(0) }
    Card(colors = CardDefaults.cardColors(containerColor = MealColors.Paper), border = CardDefaults.outlinedCardBorder()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (row.kind) {
                StagedKind.NEW -> Pill("New recipe")
                StagedKind.DUPLICATE -> Text("Already in your library. Change the title to import a copy; otherwise it is skipped.", color = MealColors.Muted)
                StagedKind.UPDATE -> Text("Updates the recipe already in your library.", color = MealColors.Muted)
            }
            // Counted live, so the banner drops as each amount is fixed.
            val amountIssues = row.rows.count { it.needsInput }
            if (amountIssues > 0) AttentionBanner("$amountIssues amount(s) need your attention")
            OutlinedTextField(row.title, { v -> change { it.copy(title = v) } }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val primary = if (row.kind == StagedKind.UPDATE) ImportAction.UPDATE else ImportAction.IMPORT
                FilterChip(selected = row.action == primary, onClick = { change { it.copy(action = primary) } },
                    label = { Text(if (primary == ImportAction.UPDATE) "Update" else "Import") })
                FilterChip(selected = row.action == ImportAction.SKIP, onClick = { change { it.copy(action = ImportAction.SKIP) } },
                    label = { Text("Skip") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(row.category, { v -> change { it.copy(category = v) } }, label = { Text("Category") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(row.subcategory, { v -> change { it.copy(subcategory = v) } }, label = { Text("Subcategory") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(row.servingsAmount, { v -> change { it.copy(servingsAmount = v) } }, label = { Text("Servings") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(row.servingsUnit, { v -> change { it.copy(servingsUnit = v) } }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = { showIngredients = !showIngredients }) {
                Text(if (showIngredients) "Hide ingredients" else "Show ingredients (${row.rows.count { it.kind == "ingredient" }})")
            }
            if (showIngredients) {
                IngredientEditor(
                    rows = row.rows,
                    onChange = { key, edit -> change { r -> r.copy(rows = r.rows.map { if (it.key == key) edit(it) else it }) } },
                    onRemove = { key -> change { r -> r.copy(rows = r.rows.filterNot { it.key == key }) } },
                    onMove = { key, by -> change { r -> r.copy(rows = moved(r.rows, r.rows.indexOfFirst { it.key == key }, by)) } },
                    onAddIngredient = { val key = "n${next++}"; change { r -> r.copy(rows = r.rows + com.naeblis11.mealplanner.recipes.EditorRowState(key, "ingredient", "")) } },
                    onAddSection = { val key = "n${next++}"; change { r -> r.copy(rows = r.rows + com.naeblis11.mealplanner.recipes.EditorRowState(key, "section", "")) } },
                )
            }
        }
    }
}

private fun <T> moved(items: List<T>, index: Int, by: Int): List<T> {
    val target = index + by
    if (index < 0 || target !in items.indices) return items
    return items.toMutableList().apply { add(target, removeAt(index)) }
}
