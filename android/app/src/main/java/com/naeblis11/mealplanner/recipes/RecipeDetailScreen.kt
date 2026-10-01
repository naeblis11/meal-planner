package com.naeblis11.mealplanner.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.PhotoImage
import com.naeblis11.mealplanner.ui.Pill
import com.naeblis11.mealplanner.ui.RatingStars
import com.naeblis11.mealplanner.ui.SectionLabel
import com.naeblis11.mealplanner.ui.theme.MealColors
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    view: RecipeView?,
    photo: File?,
    message: String?,
    loadError: String?,
    onMessageShown: () -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onScale: (String) -> Unit,
    onRate: (Int) -> Unit,
    onDelete: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    photoVersion: Long = 0,
    plannedMeals: Int = 0,
) {
    // Read while cooking: keep the screen awake while this page is open.
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = onEdit, enabled = view != null) { Text("Edit") }
                    Box {
                        TextButton(onClick = { menuOpen = true }, enabled = view != null || loadError != null) { Text("More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (view != null) {
                                DropdownMenuItem(text = { Text("Take photo") }, onClick = { menuOpen = false; onTakePhoto() })
                                DropdownMenuItem(text = { Text("Choose photo") }, onClick = { menuOpen = false; onChoosePhoto() })
                                if (view.imageFilename != null) {
                                    DropdownMenuItem(text = { Text("Remove photo") }, onClick = { menuOpen = false; onRemovePhoto() })
                                }
                            }
                            DropdownMenuItem(
                                text = { Text("Delete recipe", color = MealColors.Danger) },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (view == null) {
            if (loadError != null) {
                Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AttentionBanner(loadError)
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete recipe", color = MealColors.Danger) }
                }
            } else {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            if (photo != null && view.imageFilename != null) {
                item(key = "photo") {
                    PhotoImage(photo, contentDescription = "Photo of ${view.name}", modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)), version = photoVersion)
                }
            }
            item(key = "header") {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(view.name, style = MaterialTheme.typography.headlineMedium)
                    listOfNotNull(view.category, view.subcategory).takeIf { it.isNotEmpty() }?.let { Pill(it.joinToString(" \u00b7 ")) }
                    RatingStars(view.rating, onRate = onRate)
                    if (view.rating != null) TextButton(onClick = { onRate(0) }) { Text("Clear rating") }
                    val meta = listOfNotNull(
                        view.servings?.let { "Serves $it ${view.servingsUnit ?: ""}".trim() },
                        view.oven?.let { "Oven $it" },
                    )
                    if (meta.isNotEmpty()) Text(meta.joinToString("   "), color = MealColors.Muted)
                }
            }
            if (view.canScale) item(key = "scaler") { ServingsScaler(view.servings ?: "", onScale) }
            item(key = "ingredients-label") { SectionLabel("Ingredients") }
            var lastSection: String? = null
            for ((index, ingredient) in view.ingredients.withIndex()) {
                if (ingredient.section != null && ingredient.section != lastSection) {
                    val heading = ingredient.section
                    item(key = "section-$index") { Text(heading, style = MaterialTheme.typography.titleMedium, color = MealColors.Muted, modifier = Modifier.padding(top = 12.dp)) }
                }
                lastSection = ingredient.section
                item(key = "ingredient-$index") { IngredientLine(ingredient) }
            }
            item(key = "instructions-label") { SectionLabel("Instructions") }
            for (step in view.steps) item(key = "step-${step.number}") { StepLine(step) }
            if (view.notes.isNotEmpty()) {
                item(key = "notes-label") { SectionLabel("Notes") }
                for ((index, note) in view.notes.withIndex()) item(key = "note-$index") { Text(note, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            val source = listOfNotNull(view.author, view.sourceUrl)
            if (source.isNotEmpty()) item(key = "source") { Text("Source: ${source.joinToString(" \u00b7 ")}", color = MealColors.Muted, modifier = Modifier.padding(top = 16.dp)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this recipe?") },
            text = { Text(deleteWarning(plannedMeals)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MealColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun ServingsScaler(current: String, onScale: (String) -> Unit) {
    var text by rememberSaveable(current) { mutableStateOf(current) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Servings") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { onScale(text) }, shape = RoundedCornerShape(10.dp)) { Text("Scale") }
    }
}

@Composable
private fun IngredientLine(ingredient: IngredientView) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(listOf(ingredient.amount, ingredient.unit, ingredient.name).filter { it.isNotBlank() }.joinToString(" "), style = MaterialTheme.typography.bodyLarge)
        for (note in ingredient.notes) Text(note, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
        for (sub in ingredient.substitutions) {
            Text("or " + listOf(sub.amount, sub.unit, sub.name).filter { it.isNotBlank() }.joinToString(" "), color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StepLine(step: StepView) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(MealColors.Accent), contentAlignment = Alignment.Center) {
            Text("${step.number}", color = MealColors.Paper, style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.weight(1f)) {
            Text(step.text, style = MaterialTheme.typography.bodyLarge)
            for (note in step.notes) Text(note, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The delete dialog's body: a planned recipe says its meals go too. */
internal fun deleteWarning(plannedMeals: Int): String = when (plannedMeals) {
    0 -> "It is removed from this phone. This can't be undone."
    1 -> "It is on your meal plan once; deleting it removes that meal from the plan too. This can't be undone."
    else -> "It is on your meal plan $plannedMeals times; deleting it removes those meals from the plan too. This can't be undone."
}
