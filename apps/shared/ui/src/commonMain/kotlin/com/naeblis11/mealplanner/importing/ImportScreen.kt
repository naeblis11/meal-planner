package com.naeblis11.mealplanner.importing

import com.naeblis11.mealplanner.ui.LocalLeaveGuard
import com.naeblis11.mealplanner.ui.PlatformBackHandler
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
import androidx.compose.material3.AlertDialog
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
import com.naeblis11.mealplanner.backup.ImportAction
import com.naeblis11.mealplanner.backup.StagedKind
import com.naeblis11.mealplanner.domain.CategoryOptions
import com.naeblis11.mealplanner.domain.EditorMoves
import com.naeblis11.mealplanner.recipes.IngredientEditor
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.CategoryFields
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
    /**
     * Whether this screen is the one shown, and so may hold the LeaveGuard; false for a screen still composed as it
     * leaves, which must not take the guard from the review that replaces it.
     */
    holdsLeave: Boolean = true,
    /**
     * Leave was chosen while a switch of section (the rail, the tabs, the desktop's Quit) waited: the review is
     * cancelled first, so the section it leaves for never keeps it behind, then [switchSection] goes ahead.
     */
    onLeave: (switchSection: () -> Unit) -> Unit = { switchSection -> onCancel(); switchSection() },
    /** Each card's Category and Subcategory suggestions (owner, 2026-10-10); the built-in lists until the library's arrive. */
    categoryOptions: CategoryOptions = CategoryOptions.DEFAULT,
) {
    val busy = state is ImportState.Reviewing && state.busy
    // A switch of section while a review is open asks first, as an unsaved edit does: left behind, the review would
    // hold the import (and the desktop's recipes waiting for review) off screen. Ignored while Confirm is saving.
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val leaveGuard = LocalLeaveGuard.current
    val saving by rememberUpdatedState(busy)
    if (leaveGuard != null && holdsLeave && state is ImportState.Reviewing) {
        DisposableEffect(leaveGuard) {
            val release = leaveGuard.hold { proceed -> if (!saving) pendingLeave = proceed }
            onDispose { release() }
        }
    }
    pendingLeave?.takeIf { state is ImportState.Reviewing }?.let { proceed ->
        AlertDialog(
            onDismissRequest = { pendingLeave = null },
            title = { Text("Leave this import?") },
            text = { Text("The recipes you haven't added will be dropped.") },
            confirmButton = {
                TextButton(onClick = { pendingLeave = null; onLeave(proceed) }) { Text("Leave", color = MealColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingLeave = null }) { Text("Keep reviewing") } },
        )
    }
    val listState = rememberLazyListState()
    val reviewError = (state as? ImportState.Reviewing)?.error
    // The error sits at the top of the review: bring it into view when Confirm fails from further down.
    LaunchedEffect(reviewError) { if (reviewError != null) listState.animateScrollToItem(0) }
    PlatformBackHandler { if (busy) return@PlatformBackHandler; if (state is ImportState.Reviewing || state is ImportState.Reading) onCancel() else onDone() }
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
                items(state.rows, key = { it.tempId }) { row -> ReviewCard(row, categoryOptions) { change -> onUpdate(row.tempId, change) } }
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
private fun ReviewCard(row: ReviewRow, categoryOptions: CategoryOptions, change: ((ReviewRow) -> ReviewRow) -> Unit) {
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
            CategoryFields(
                category = row.category,
                subcategory = row.subcategory,
                options = categoryOptions,
                onCategory = { v -> change { it.copy(category = v) } },
                onSubcategory = { v -> change { it.copy(subcategory = v) } },
            )
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
                    onMove = { key, by -> change { r -> r.copy(rows = EditorMoves.moved(r.rows, r.rows.indexOfFirst { it.key == key }, by)) } },
                    onAddIngredient = { val key = "n${next++}"; change { r -> r.copy(rows = r.rows + com.naeblis11.mealplanner.recipes.EditorRowState(key, "ingredient", "")) } },
                    onAddSection = { val key = "n${next++}"; change { r -> r.copy(rows = r.rows + com.naeblis11.mealplanner.recipes.EditorRowState(key, "section", "")) } },
                    onMoveTo = { key, to -> change { r -> r.copy(rows = EditorMoves.movedTo(r.rows, r.rows.indexOfFirst { it.key == key }, to)) } },
                )
            }
        }
    }
}
