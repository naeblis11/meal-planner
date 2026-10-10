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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
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
    /** Owner, 2026-10-10: the collapsed headings (RecipeListViewModel.categoryKey / subcategoryKey); ignored while searching. */
    collapsed: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
    onCollapseAll: (List<String>) -> Unit = {},
    onExpandAll: () -> Unit = {},
) {
    // The list's stars only show a rating (owner, 2026-10-09); it is changed on the recipe page. The snackbar is for
    // Open recipe folder.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
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
        // The banner, the search and the cookbook filter stay put; only the recipes below them scroll.
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (folderMissing || folderMoved || filesMissing || attentionFiles > 0) {
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
                        // Said on the banner itself: it opens the list of the files, each with what's wrong and its fix.
                        action = if (openLocked) null else SHOW_THEM,
                    )
                }
                if (openLocked) {
                    Text(EDITING_ELSEWHERE, color = MealColors.Muted, modifier = Modifier.padding(top = 8.dp))
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Search recipes") },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                // Owner, 2026-10-10: the cookbook filter and Collapse all / Expand all share a line.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { if (books.isNotEmpty()) BookFilter(books, book, onBookChange) }
                    val categories = groups.orEmpty().map { it.category }
                    if (categories.isNotEmpty() && query.isBlank()) {
                        val allCollapsed = categories.all { RecipeListViewModel.categoryKey(it) in collapsed }
                        TextButton(
                            onClick = { if (allCollapsed) onExpandAll() else onCollapseAll(categories) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(if (allCollapsed) EXPAND_ALL else COLLAPSE_ALL) }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag(RECIPE_LIST_TAG),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            ) {
                when {
                    groups == null -> item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                    groups.isEmpty() -> item {
                        Text(
                            if (query.isBlank()) "No recipes yet. Add one, or import a file." else "No recipes match \"$query\".",
                            color = MealColors.Muted,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                    else -> {
                        // A search shows every match: nothing it finds hides under a collapsed heading.
                        val folded = if (query.isBlank()) collapsed else emptySet()
                        for (group in groups) {
                            val categoryKey = RecipeListViewModel.categoryKey(group.category)
                            val count = group.recipes.size + group.subcategories.sumOf { it.recipes.size }
                            item(key = "c:${group.category}") {
                                Heading(group.category, count, categoryKey !in folded, MaterialTheme.typography.titleLarge, MealColors.Ink, top = 14.dp) {
                                    onToggle(categoryKey)
                                }
                            }
                            if (categoryKey in folded) continue
                            items(group.recipes, key = { "r:${it.id}" }) { RecipeRow(it, thumbnail(it), onOpen, it.id == selectedId, !openLocked) }
                            for (sub in group.subcategories) {
                                val subKey = RecipeListViewModel.subcategoryKey(group.category, sub.subcategory)
                                item(key = "s:${group.category}/${sub.subcategory}") {
                                    Heading(sub.subcategory, sub.recipes.size, subKey !in folded, MaterialTheme.typography.titleMedium, MealColors.Muted, top = 8.dp) {
                                        onToggle(subKey)
                                    }
                                }
                                if (subKey in folded) continue
                                items(sub.recipes, key = { "r:${it.id}" }) { RecipeRow(it, thumbnail(it), onOpen, it.id == selectedId, !openLocked) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A category or subcategory heading that folds its recipes away (owner, 2026-10-10): an arrow, the name and how many
 * recipes it holds. The whole line is the button, at least 48 dp tall.
 */
@Composable
private fun Heading(name: String, count: Int, open: Boolean, style: TextStyle, color: Color, top: Dp, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = top)
            .clickable(onClickLabel = if (open) "Collapse $name" else "Expand $name", role = Role.Button, onClick = onToggle)
            .heightIn(min = 48.dp)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
    ) {
        Text(if (open) OPEN_ARROW else CLOSED_ARROW, style = style, color = MealColors.Muted)
        Text(name, style = style, color = color, modifier = Modifier.weight(1f, fill = false))
        Text("($count)", style = MaterialTheme.typography.bodyMedium, color = MealColors.Muted)
    }
}

/** The heading arrows: pointing down while open, right while collapsed. */
private const val OPEN_ARROW = "\u25BE"
private const val CLOSED_ARROW = "\u25B8"

/** The list's button that folds every category, and the one that opens them all again. */
const val COLLAPSE_ALL = "Collapse all"
const val EXPAND_ALL = "Expand all"

/**
 * One recipe: a small photo, the name (two lines at most), its cookbook mark, and its rating as small stars that only
 * show it (owner, 2026-10-09). Rating is done on the recipe page, so nothing in a row but the row itself is tapped,
 * and the rows stay short enough to show many recipes at once on the phone and the PC alike.
 */
@Composable
private fun RecipeRow(recipe: RecipeSummary, thumbnail: File?, onOpen: (Long) -> Unit, selected: Boolean, enabled: Boolean) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                // Only the wide layout selects a row.
                .then(if (selected) Modifier.background(MealColors.AccentTint, RoundedCornerShape(10.dp)).semantics { this.selected = true } else Modifier)
                .clickable(enabled = enabled) { onOpen(recipe.id) }
                .heightIn(min = ROW_MIN_HEIGHT)
                .padding(vertical = 4.dp),
        ) {
            PhotoImage(thumbnail, contentDescription = null, modifier = Modifier.size(THUMBNAIL).clip(RoundedCornerShape(6.dp)))
            Text(
                recipe.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            recipe.book?.let { Icon(BookIcon, contentDescription = "From $it", tint = MealColors.Accent, modifier = Modifier.size(16.dp)) }
            // A fixed slot, empty when unrated, so the cookbook marks line up down the list.
            Box(Modifier.width(STAR_SLOT), contentAlignment = Alignment.CenterEnd) {
                recipe.rating?.takeIf { it > 0 }?.let { RatingStars(it, size = LIST_STAR_SIZE) }
            }
        }
        HorizontalDivider(color = MealColors.LineSoft)
    }
}

private val THUMBNAIL = 36.dp

// The row is a tap target as a whole: at least 48 dp tall, as every control is.
private val ROW_MIN_HEIGHT = 48.dp

// Small: the stars only report the rating; it is changed on the recipe page.
private val LIST_STAR_SIZE = 14.sp

// Five 14 sp stars fit in it, with a little room to spare.
private val STAR_SLOT = 72.dp

/** The recipe list's scrolling part (below the search and filters, which stay put), for tests. */
const val RECIPE_LIST_TAG = "recipe-list"

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

/** The Recipes banner's visible action: it opens Needs attention, which names each file and how to fix it. */
const val SHOW_THEM = "Show them"

private fun attentionText(files: Int): String =
    if (files == 1) "1 recipe file needs attention" else "$files recipe files need attention"
