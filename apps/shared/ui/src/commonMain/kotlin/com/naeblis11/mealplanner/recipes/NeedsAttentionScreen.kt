package com.naeblis11.mealplanner.recipes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.folder.RecipeFileProblem
import com.naeblis11.mealplanner.ui.theme.MealColors
import kotlinx.coroutines.launch

/** Said when "Open recipe folder" couldn't open it (the folder is missing, or Explorer couldn't be asked). */
const val OPEN_FOLDER_FAILED = "Couldn't open the recipe folder."

/** The button that removes recipes held back from a mass removal (one, or several). */
const val REMOVE_IT = "Remove it from the app"
const val REMOVE_THEM = "Remove them from the app"

/**
 * The two answers to a recipe folder that changed (P7-R10c). "Use the new folder" only accepts the folder; it removes
 * nothing, and recipes missing from it still wait for "Remove them from the app" (P7-R10e).
 */
const val USE_NEW_FOLDER = "Use the new folder (removes nothing)"
const val POINT_BACK = "Point back"

/**
 * The desktop's recipe files that couldn't be taken as they are (the server's corrections and
 * /sync/conflicts pages): each file and what is wrong with it. A duplicate recipe_uuid can be fixed
 * here with a new ID. Everything else is fixed in the file, and the app notices the save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsAttentionScreen(
    problems: List<RecipeFileProblem>,
    message: String?,
    onMessageShown: () -> Unit,
    onBack: () -> Unit,
    onAssignNewId: (fileName: String) -> Unit,
    /** Opens the recipes folder in Explorer; false when it couldn't, which the screen says. */
    onOpenFolder: () -> Boolean,
    /** Files getting a new ID right now; their "Assign new ID" is disabled. */
    busy: Set<String> = emptySet(),
    /** "Remove them from the app", for recipe files held back from a mass removal: opens the confirmation. */
    onRemoveMissing: () -> Unit = {},
    /** True while that removal is working; its button is disabled. */
    removing: Boolean = false,
    /** The confirmation's numbers while it is open; null otherwise. */
    removeAsk: RemoveMissingAsk? = null,
    /** "Remove" in the confirmation. */
    onConfirmRemoveMissing: () -> Unit = {},
    /** "Keep them", or the confirmation dismissed: nothing is removed. */
    onKeepMissing: () -> Unit = {},
    /** "Use the new folder", for a recipe folder that changed (P7-R10c). */
    onUseNewFolder: () -> Unit = {},
    /** True while that is working; its button is disabled. */
    usingNewFolder: Boolean = false,
    /** "Point back": how to give the app its old folder again. */
    onPointBack: (RecipeFileProblem) -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Needs attention", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { if (!onOpenFolder()) scope.launch { snackbar.showSnackbar(OPEN_FOLDER_FAILED) } }) {
                        Text("Open recipe folder")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (problems.isEmpty()) {
                item { Text("Nothing needs attention.", color = MealColors.Muted, modifier = Modifier.padding(vertical = 24.dp)) }
            } else {
                // A missing folder says what to do in its own message; there are no files to fix then.
                if (problems.any { it.kind != RecipeFileProblem.Kind.FOLDER }) {
                    item {
                        Text(
                            "Fix these in the recipe files and save; the app picks the change up by itself.",
                            color = MealColors.Muted,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                items(problems) { problem ->
                    ProblemRow(problem, problem.fileName in busy, onAssignNewId, removing, onRemoveMissing, usingNewFolder, onUseNewFolder, onPointBack)
                }
            }
        }
    }
    if (removeAsk != null) {
        // Removing takes the planned meals with the recipes, which no file can bring back: said before it is done.
        AlertDialog(
            onDismissRequest = onKeepMissing,
            text = { Text(removeMissingQuestion(removeAsk.recipes, removeAsk.plannedMeals)) },
            confirmButton = { TextButton(onClick = onConfirmRemoveMissing) { Text("Remove", color = MealColors.Danger) } },
            dismissButton = { TextButton(onClick = onKeepMissing) { Text("Keep them") } },
        )
    }
}

/** The confirmation's question: [recipes] to remove, and the [plannedMeals] that use them. */
fun removeMissingQuestion(recipes: Int, plannedMeals: Int): String {
    val what = if (recipes == 1) "1 recipe" else "$recipes recipes"
    val meals = if (plannedMeals == 1) "1 planned meal uses" else "$plannedMeals planned meals use"
    val them = if (recipes == 1) "it" else "them"
    val files = if (recipes == 1) "Its file is" else "Their files are"
    return "Remove $what from the app? $meals $them and will be removed too. $files already gone from the folder."
}

@Composable
private fun ProblemRow(
    problem: RecipeFileProblem,
    busy: Boolean,
    onAssignNewId: (String) -> Unit,
    removing: Boolean,
    onRemoveMissing: () -> Unit,
    usingNewFolder: Boolean,
    onUseNewFolder: () -> Unit,
    onPointBack: (RecipeFileProblem) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(if (problem.kind == RecipeFileProblem.Kind.FOLDER) "Recipe folder" else problem.fileName, style = MaterialTheme.typography.titleMedium)
        Text(problem.message, color = MealColors.DangerHover)
        if (problem.canAssignNewId) {
            TextButton(onClick = { onAssignNewId(problem.fileName) }, enabled = !busy) { Text("Assign new ID") }
        }
        if (problem.canRemoveMissing) {
            // Takes their planned meals with them, so it is said plainly and only ever chosen here.
            TextButton(onClick = onRemoveMissing, enabled = !removing) {
                Text(if (problem.missingFiles == 1) REMOVE_IT else REMOVE_THEM, color = MealColors.Danger)
            }
        }
        if (problem.canUseNewFolder) {
            // Nothing is removed while the folder differs from the database's; these are the only ways on.
            Row {
                TextButton(onClick = onUseNewFolder, enabled = !usingNewFolder) { Text(USE_NEW_FOLDER) }
                TextButton(onClick = { onPointBack(problem) }) { Text(POINT_BACK) }
            }
        }
    }
    HorizontalDivider(color = MealColors.LineSoft)
}
