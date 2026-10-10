package com.naeblis11.mealplanner.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.naeblis11.mealplanner.domain.RecipeDefaults
import com.naeblis11.mealplanner.domain.ServingsInput
import com.naeblis11.mealplanner.domain.Week
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.BookChip
import com.naeblis11.mealplanner.ui.KeepScreenOn
import com.naeblis11.mealplanner.ui.PhotoImage
import com.naeblis11.mealplanner.ui.Pill
import com.naeblis11.mealplanner.ui.RatingStars
import com.naeblis11.mealplanner.ui.SectionLabel
import com.naeblis11.mealplanner.ui.theme.MealColors
import java.io.File
import java.time.LocalDate

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
    onTakePhoto: (() -> Unit)?,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    photoVersion: Long = 0,
    plannedMeals: Int = 0,
    /** P5-R7: adds an ingredient to the pantry by name; null hides the button (the marks show either way). */
    onAddToPantry: ((String) -> Unit)? = null,
    /** P5-R7: plans this recipe on a day and slot at the servings given (blank: as written); null hides "Plan this recipe". */
    onAssign: ((LocalDate, String, String) -> Unit)? = null,
    /** The assign dialog's first day. */
    today: LocalDate = LocalDate.now(),
    /** P5-R7: the server's quick category form; null leaves Category out of More. */
    onSetCategory: ((String, String) -> Unit)? = null,
    /** Owner, 2026-10-09: puts every ingredient on the shopping list at the page's servings; null hides the button. */
    onAddToShoppingList: (() -> Unit)? = null,
    /** Owner, 2026-10-09: puts one ingredient on the shopping list (its "+ List"); null hides the buttons. */
    onAddIngredientToList: ((IngredientView) -> Unit)? = null,
) {
    // Read while cooking: keep the screen awake while this page is open.
    KeepScreenOn()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var assigning by rememberSaveable { mutableStateOf(false) }
    var editingCategory by rememberSaveable { mutableStateOf(false) }

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
                                // P5-R7: the server's quick category form, in the same menu.
                                if (onSetCategory != null) {
                                    DropdownMenuItem(text = { Text("Category") }, onClick = { menuOpen = false; editingCategory = true })
                                }
                                if (onTakePhoto != null) {
                                    DropdownMenuItem(text = { Text("Take photo") }, onClick = { menuOpen = false; onTakePhoto() })
                                }
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
                    view.book?.let { BookChip(it) }
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
            if (onAssign != null || onAddToShoppingList != null) {
                item(key = "plan") { PlanThisRecipe(view, onAssign?.let { { assigning = true } }, onAddToShoppingList) }
            }
            item(key = "ingredients-label") { SectionLabel("Ingredients") }
            var lastSection: String? = null
            for ((index, ingredient) in view.ingredients.withIndex()) {
                if (ingredient.section != null && ingredient.section != lastSection) {
                    val heading = ingredient.section
                    item(key = "section-$index") { Text(heading, style = MaterialTheme.typography.titleMedium, color = MealColors.Muted, modifier = Modifier.padding(top = 12.dp)) }
                }
                lastSection = ingredient.section
                item(key = "ingredient-$index") { IngredientLine(ingredient, onAddToPantry, onAddIngredientToList) }
            }
            item(key = "instructions-label") { SectionLabel("Instructions") }
            for (step in view.steps) item(key = "step-${step.number}") { StepLine(step) }
            if (view.notes.isNotEmpty()) {
                item(key = "notes-label") { SectionLabel("Notes") }
                for ((index, note) in view.notes.withIndex()) item(key = "note-$index") { Text(note, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            val source = listOfNotNull(view.author, view.book, view.sourceUrl)
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
    if (assigning && view != null && onAssign != null) {
        AssignDialog(today, if (view.scaled) view.servings.orEmpty() else "", onDismiss = { assigning = false }) { day, slot, servings ->
            assigning = false
            onAssign(day, slot, servings)
        }
    }
    if (editingCategory && view != null && onSetCategory != null) {
        CategoryDialog(view.category.orEmpty(), view.subcategory.orEmpty(), onDismiss = { editingCategory = false }) { category, subcategory ->
            editingCategory = false
            onSetCategory(category, subcategory)
        }
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
private fun IngredientLine(ingredient: IngredientView, onAddToPantry: ((String) -> Unit)?, onAddToList: ((IngredientView) -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(listOf(ingredient.amount, ingredient.unit, ingredient.name).filter { it.isNotBlank() }.joinToString(" "), style = MaterialTheme.typography.bodyLarge)
            for (note in ingredient.notes) Text(note, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
            for (sub in ingredient.substitutions) {
                Text("or " + listOf(sub.amount, sub.unit, sub.name).filter { it.isNotBlank() }.joinToString(" "), color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // The server's "In pantry" mark, or its one-tap "+ Pantry".
        if (ingredient.inPantry) {
            Text(
                IN_PANTRY,
                color = MealColors.AccentHover,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "${ingredient.name} is in your pantry" },
            )
        } else if (onAddToPantry != null && ingredient.name.isNotBlank()) {
            TextButton(
                onClick = { onAddToPantry(ingredient.name) },
                // 48 dp tall on the desktop too, where Material doesn't pad a button out to its touch size.
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Add ${ingredient.name} to your pantry" },
            ) { Text("+ Pantry") }
        }
        if (onAddToList != null && ingredient.name.isNotBlank()) {
            TextButton(
                onClick = { onAddToList(ingredient) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Add ${ingredient.name} to your shopping list" },
            ) { Text(ADD_TO_LIST) }
        }
    }
}

/** An ingredient's one-tap add to the shopping list. */
const val ADD_TO_LIST = "+ List"

/** The recipe page's mark on an ingredient the pantry covers. */
const val IN_PANTRY = "\u2713 In pantry"

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
fun deleteWarning(plannedMeals: Int): String = when (plannedMeals) {
    0 -> "It is removed from this phone. This can't be undone."
    1 -> "It is on your meal plan once; deleting it removes that meal from the plan too. This can't be undone."
    else -> "It is on your meal plan $plannedMeals times; deleting it removes those meals from the plan too. This can't be undone."
}

/** The server's "Plan this recipe": a note when the page is scaled, and the button that opens the assign dialog. */
@Composable
private fun PlanThisRecipe(view: RecipeView, onAssign: (() -> Unit)?, onAddToShoppingList: (() -> Unit)?) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (view.scaled && view.servings != null) {
            Text(planningNote(view.servings, view.servingsUnit), color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onAssign != null) {
                OutlinedButton(onClick = onAssign, shape = RoundedCornerShape(10.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text(ASSIGN_TO_CALENDAR) }
            }
            if (onAddToShoppingList != null) {
                OutlinedButton(onClick = onAddToShoppingList, shape = RoundedCornerShape(10.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text(ADD_TO_SHOPPING_LIST) }
            }
        }
    }
}

/**
 * Assign to calendar: a day (from [today], a day at a time), a slot, and the servings, prefilled with the scaled
 * servings when the page is scaled. Servings that don't read stay in the dialog with the hint.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssignDialog(today: LocalDate, servings: String, onDismiss: () -> Unit, onAssign: (LocalDate, String, String) -> Unit) {
    // Saveable, so what was chosen survives a rotation.
    var day by rememberSaveable(stateSaver = DAY_SAVER) { mutableStateOf(today) }
    var slot by rememberSaveable { mutableStateOf("Dinner") }
    var text by rememberSaveable { mutableStateOf(servings) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        title = { Text(ASSIGN_TO_CALENDAR) },
        text = {
            Column(Modifier.widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 48 dp steppers and a smaller label keep the longest day ("Wednesday, Sep 30") on one line at 320 dp.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { day = day.minusDays(1) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Previous day" }) { Text("<") }
                    Text(Week.dayLabel(day), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { day = day.plusDays(1) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Next day" }) { Text(">") }
                }
                // Wraps on a narrow phone rather than pushing Dinner out of the dialog.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (each in Week.SLOTS) FilterChip(selected = slot == each, onClick = { slot = each }, label = { Text(each) })
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; invalid = false },
                    label = { Text(ASSIGN_SERVINGS_LABEL) },
                    isError = invalid,
                    supportingText = if (invalid) ({ Text(ServingsInput.HINT) }) else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (ServingsInput.read(text) == ServingsInput.Result.Invalid) invalid = true else onAssign(day, slot, text) }) {
                Text("Assign")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The server's quick category form: Category and Subcategory, with its suggestions. */
@Composable
private fun CategoryDialog(category: String, subcategory: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var main by rememberSaveable { mutableStateOf(category) }
    var sub by rememberSaveable { mutableStateOf(subcategory) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        title = { Text("Category") },
        text = {
            Column(Modifier.widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = main, onValueChange = { main = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SuggestionRow(RecipeDefaults.CATEGORIES) { main = it }
                OutlinedTextField(value = sub, onValueChange = { sub = it }, label = { Text("Subcategory") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SuggestionRow(RecipeDefaults.SUBCATEGORIES) { sub = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(main, sub) }) { Text("Save category") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SuggestionRow(options: List<String>, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        for (option in options) AssistChip(onClick = { onPick(option) }, label = { Text(option) })
    }
}

// A day as its epoch day, for rememberSaveable.
private val DAY_SAVER = Saver<LocalDate, Long>(save = { it.toEpochDay() }, restore = { LocalDate.ofEpochDay(it) })

const val ASSIGN_TO_CALENDAR = "Assign to calendar"

/** The recipe page's button that puts every ingredient on the shopping list. */
const val ADD_TO_SHOPPING_LIST = "Add to shopping list"
const val ASSIGN_SERVINGS_LABEL = "Servings (blank: as written)"

/** The server's note above Assign when the page is scaled. */
fun planningNote(servings: String, unit: String?): String =
    "Planning at $servings ${unit ?: "servings"} \u2014 the shopping list will scale to match."
