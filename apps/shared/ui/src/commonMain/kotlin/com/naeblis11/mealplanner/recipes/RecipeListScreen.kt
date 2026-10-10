package com.naeblis11.mealplanner.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.data.RecipeSummary
import com.naeblis11.mealplanner.domain.CategoryGroup
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.BookIcon
import com.naeblis11.mealplanner.ui.PhotoImage
import com.naeblis11.mealplanner.ui.RatingStars
import com.naeblis11.mealplanner.ui.theme.MealColors
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeListScreen(
    groups: List<CategoryGroup<RecipeSummary>>?,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    thumbnail: (RecipeSummary) -> File?,
    books: List<String> = emptyList(),
    book: String? = null,
    onBookChange: (String?) -> Unit = {},
    /** Desktop: how many recipe files are on the Needs attention list; the banner shows when it isn't 0. */
    attentionFiles: Int = 0,
    /** Desktop: the recipe folder itself is missing or unreadable; the banner says so instead of counting files. */
    folderMissing: Boolean = false,
    /** Desktop: the folder is there, but recipe files are missing from it and held in the app; the banner says so. */
    filesMissing: Boolean = false,
    /** Desktop: the recipe folder isn't the one the database was made from (P7-R10c); nothing is removed meanwhile. */
    folderMoved: Boolean = false,
    onAttention: () -> Unit = {},
    /** Desktop: opens the recipes folder in Explorer, false when it couldn't (the screen says so); null hides the button (Android). */
    onOpenFolder: (() -> Boolean)? = null,
    /** Wide layout: the recipe open in the detail pane, highlighted here. */
    selectedId: Long? = null,
    /** Wide layout: the pane holds an edit form, so no other recipe, nor Import or Needs attention, opens until it is saved or cancelled. */
    openLocked: Boolean = false,
    /** False on the wide layout, where Settings is on the rail and the list is narrow. */
    showSettings: Boolean = true,
    /** P5-R7: the stars rate (the current one clears), as on the server's list; null shows them for rated recipes only. */
    onRate: ((Long, Int) -> Unit)? = null,
    /** A message for the snackbar (a rating that couldn't be saved), marked shown as it appears. */
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) {
        if (message != null) {
            onMessageShown()
            scope.launch { snackbar.showSnackbar(message) }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Recipes", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    if (onOpenFolder != null) {
                        TextButton(onClick = { if (!onOpenFolder()) scope.launch { snackbar.showSnackbar(OPEN_FOLDER_FAILED) } }) {
                            Text("Open recipe folder")
                        }
                    }
                    // Import opens its own screen over the list: not while a form holds the pane (see openLocked).
                    TextButton(onClick = onImport, enabled = !openLocked) { Text("Import") }
                    if (showSettings) TextButton(onClick = onSettings) { Text("Settings") }
                },
            )
        },
        floatingActionButton = {
            if (!openLocked) {
                ExtendedFloatingActionButton(onClick = onNew, containerColor = MealColors.Accent, contentColor = MealColors.Paper) { Text("New recipe") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        ) {
            if (folderMissing || folderMoved || filesMissing || attentionFiles > 0) {
                item(key = "attention") {
                    AttentionBanner(
                        when {
                            folderMissing -> FOLDER_MISSING
                            folderMoved -> FOLDER_MOVED
                            filesMissing -> FILES_MISSING
                            else -> attentionText(attentionFiles)
                        },
                        // Not while a form holds the pane: opening the list from here would leave the form behind.
                        Modifier.padding(top = 8.dp)
                            .clickable(enabled = !openLocked, onClickLabel = "Show the files", role = Role.Button, onClick = onAttention),
                    )
                }
            }
            if (openLocked) {
                item(key = "locked") { Text(EDITING_ELSEWHERE, color = MealColors.Muted, modifier = Modifier.padding(top = 8.dp)) }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Search recipes") },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            if (books.isNotEmpty()) item(key = "books") { BookFilter(books, book, onBookChange) }
            when {
                groups == null -> item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                groups.isEmpty() -> item {
                    Text(
                        if (query.isBlank()) "No recipes yet. Add one, or import a file." else "No recipes match \"$query\".",
                        color = MealColors.Muted,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                else -> for (group in groups) {
                    item(key = "c:${group.category}") {
                        Text(group.category, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                    }
                    items(group.recipes, key = { "r:${it.id}" }) { RecipeRow(it, thumbnail(it), onOpen, it.id == selectedId, !openLocked, onRate) }
                    for (sub in group.subcategories) {
                        item(key = "s:${group.category}/${sub.subcategory}") {
                            Text(sub.subcategory, style = MaterialTheme.typography.titleMedium, color = MealColors.Muted, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                        }
                        items(sub.recipes, key = { "r:${it.id}" }) { RecipeRow(it, thumbnail(it), onOpen, it.id == selectedId, !openLocked, onRate) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeRow(recipe: RecipeSummary, thumbnail: File?, onOpen: (Long) -> Unit, selected: Boolean, enabled: Boolean, onRate: ((Long, Int) -> Unit)?) {
    // A list whose stars rate has a star line under every row. Not while a form holds the pane, though: a rating
    // saved under an open edit of the same recipe would stop its Save, so the line then only shows the rating.
    val starLine = onRate != null
    val rate = onRate?.takeIf { enabled }
    Column {
        Column(
            Modifier
                .fillMaxWidth()
                // Only the wide layout selects a row; the phone's rows look and read as before.
                .then(if (selected) Modifier.background(MealColors.AccentTint, RoundedCornerShape(10.dp)).semantics { this.selected = true } else Modifier)
                .clickable(enabled = enabled) { onOpen(recipe.id) }
                // The star line's 48 dp boxes end the row; without one, the row keeps its padding below.
                .padding(top = 10.dp, bottom = if (starLine) 0.dp else 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhotoImage(thumbnail, contentDescription = null, modifier = Modifier.size(THUMBNAIL).clip(RoundedCornerShape(10.dp)))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(recipe.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                        recipe.book?.let { Icon(BookIcon, contentDescription = "From $it", tint = MealColors.Accent, modifier = Modifier.size(16.dp)) }
                    }
                    if (!starLine && recipe.rating != null) RatingStars(recipe.rating, size = MaterialTheme.typography.bodyMedium.fontSize)
                }
            }
            // The stars get their own line under the name, 4 dp clear of it, so the middle of a row (the photo and
            // the name) and a tap just under the name still open the recipe. Each star is a 48 dp touch box, as every
            // control is; the line starts STAR_INDENT in, so five fit on a 320 dp phone.
            if (starLine) {
                Box(Modifier.padding(start = STAR_INDENT, top = 4.dp).height(STAR_TOUCH), contentAlignment = Alignment.CenterStart) {
                    if (rate != null) {
                        RatingStars(
                            recipe.rating,
                            onRate = { stars -> rate(recipe.id, stars) },
                            size = STAR_SIZE,
                            subject = recipe.name,
                            clearsOnCurrent = true,
                            touch = STAR_TOUCH,
                        )
                    } else {
                        // Same height and the same places, so the list doesn't move as Edit opens or closes.
                        RatingStars(recipe.rating, size = STAR_SIZE, touch = STAR_TOUCH, boxed = true)
                    }
                }
            }
        }
        HorizontalDivider(color = MealColors.LineSoft)
    }
}

private val THUMBNAIL = 56.dp
private val STAR_TOUCH = 48.dp

// The recipe page's size, so a rating reads the same in the list as on the recipe.
private val STAR_SIZE = 28.sp

// Less than the photo's width: the first star's glyph, centered in its box, then sits just past the photo, and the
// line (40 + 5 x 48 = 280 dp) fits the 288 dp a 320 dp phone leaves inside the list's 16 dp sides.
private val STAR_INDENT = 40.dp

/** "All recipes" plus one chip per cookbook in the library; the selected one narrows the list. */
@Composable
private fun BookFilter(books: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All recipes") })
        for (book in books) {
            FilterChip(
                selected = selected == book,
                onClick = { onSelect(if (selected == book) null else book) },
                label = { Text(book) },
                leadingIcon = { Icon(BookIcon, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
}

/** The Recipes banner when the recipe folder itself is gone: not a file count. */
const val FOLDER_MISSING = "The recipe folder is missing or can't be read"

/** The banner while recipe files are missing from a folder that is there (held from a mass removal). */
const val FILES_MISSING = "Some recipe files are missing from the folder"

/** The banner while the recipe folder isn't the one the database was made from (P7-R10c). */
const val FOLDER_MOVED = "The recipe folder has changed; nothing is removed until you confirm"

/** Above the wide list while the detail pane holds an edit form. */
const val EDITING_ELSEWHERE = "Save or cancel the recipe you're editing to open another."

private fun attentionText(files: Int): String =
    if (files == 1) "1 recipe file needs attention" else "$files recipe files need attention"
