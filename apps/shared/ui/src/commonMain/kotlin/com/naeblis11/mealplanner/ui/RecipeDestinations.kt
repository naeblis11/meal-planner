package com.naeblis11.mealplanner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.CategoryOptions
import com.naeblis11.mealplanner.folder.RecipeFileProblem
import com.naeblis11.mealplanner.recipes.RecipeDetailScreen
import com.naeblis11.mealplanner.recipes.RecipeDetailViewModel
import com.naeblis11.mealplanner.recipes.RecipeEditScreen
import com.naeblis11.mealplanner.recipes.RecipeEditViewModel
import com.naeblis11.mealplanner.recipes.RecipeListScreen
import com.naeblis11.mealplanner.recipes.RecipeListViewModel
import com.naeblis11.mealplanner.recipes.RowActions
import com.naeblis11.mealplanner.recipes.StepActions
import com.naeblis11.mealplanner.ui.theme.MealColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The detail pane's resting page on a wide window, before a recipe is chosen. */
const val PANE_EMPTY = "pane-empty"

/** What the resting pane says. */
const val CHOOSE_A_RECIPE = "Choose a recipe to see it here."

/**
 * The list's width beside the detail pane. P3-R4 asks for about 360 dp; 380 keeps "Open recipe folder" and "Import"
 * beside the list's title without clipping. On a window at least [ROOMY_WINDOW] wide the list takes 480 dp (owner,
 * 2026-10-09), so most recipe names fit on one line; a smaller wide window keeps 380, leaving the recipe room.
 */
val LIST_PANE_WIDTH: Dp = 380.dp
val LIST_PANE_WIDTH_ROOMY: Dp = 480.dp
val ROOMY_WINDOW: Dp = 1000.dp

/** The list's width on a wide window [windowWidth] across. */
fun listPaneWidth(windowWidth: Dp): Dp = if (windowWidth >= ROOMY_WINDOW) LIST_PANE_WIDTH_ROOMY else LIST_PANE_WIDTH

/**
 * The recipe page, its Edit form and New recipe. They are pages of MealPlannerApp's NavHost (on a narrow window, and
 * for a recipe opened from the Calendar on any window) and of the Recipes detail pane on a wide one. [nav] is
 * whichever NavController holds them, so Back, Save and Delete move within it.
 */
fun NavGraphBuilder.recipeDestinations(nav: NavController, container: AppContainer) {
    composable(Routes.RECIPE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
        val id = entry.arguments!!.read { getLong("id") }
        val vm: RecipeDetailViewModel = viewModel { RecipeDetailViewModel(id, container.recipes, container.imagesDir, pantry = container.pantry, plans = container.plans, shopping = container.shopping) }
        val photoVersion by vm.photoVersion.collectAsStateWithLifecycle()
        val photoGuard = rememberLaunchGuard()
        val takePhoto = rememberTakePhoto(
            photoGuard,
            container.cameraDir,
            onTaken = { open -> vm.setPhoto(open) },
            onUnavailable = { vm.showMessage("No camera app is available.") },
        )
        val choosePhoto = rememberChoosePhoto(photoGuard) { open -> vm.setPhoto(open) }
        val view by vm.view.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val loadError by vm.loadError.collectAsStateWithLifecycle()
        val deleted by vm.deleted.collectAsStateWithLifecycle()
        val plannedMeals by vm.plannedMeals.collectAsStateWithLifecycle()
        val categoryOptions by vm.categoryOptions.collectAsStateWithLifecycle(CategoryOptions.DEFAULT)
        // Leaves once, with this composition's NavController, even if the page was recreated mid-delete.
        LaunchedEffect(deleted) { if (deleted) popIfCurrent(nav, entry) }
        RecipeDetailScreen(
            view = view,
            photo = view?.imageFilename?.takeIf { RecipeRepository.isSafeImageName(it) }?.let { container.recipes.imageFile(it) },
            message = message,
            loadError = loadError,
            onMessageShown = vm::messageShown,
            onBack = dropUnlessResumed { nav.popBackStack() },
            onEdit = dropUnlessResumed { nav.navigate(Routes.edit(id)) },
            onScale = vm::scaleTo,
            onRate = vm::setRating,
            onDelete = vm::delete,
            onTakePhoto = takePhoto,
            onChoosePhoto = choosePhoto,
            onRemovePhoto = vm::removePhoto,
            photoVersion = photoVersion,
            plannedMeals = plannedMeals,
            onAddToPantry = vm::addToPantry,
            onAssign = vm::assign,
            today = vm.today(),
            onSetCategory = vm::setCategory,
            categoryOptions = categoryOptions,
            onAddToShoppingList = if (vm.canShop) vm::addRecipeToShoppingList else null,
            onAddIngredientToList = if (vm.canShop) vm::addIngredientToShoppingList else null,
        )
    }
    composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
        val id = entry.arguments!!.read { getLong("id") }
        val vm: RecipeEditViewModel = viewModel { RecipeEditViewModel(id, container.recipes, createSavedStateHandle()) }
        EditDestination(vm, nav, onSaved = { popIfCurrent(nav, entry) })
    }
    composable(Routes.NEW) { entry ->
        val vm: RecipeEditViewModel = viewModel { RecipeEditViewModel(null, container.recipes, createSavedStateHandle()) }
        EditDestination(vm, nav, onSaved = { newId ->
            if (nav.currentBackStackEntry?.id == entry.id) {
                nav.navigate(Routes.recipe(newId)) { popUpTo(Routes.NEW) { inclusive = true } }
            }
        })
    }
}

/**
 * Recipes. Narrow: the list alone, and a recipe opens as its own page. Wide (P3-R4): the list beside a detail pane
 * with its own NavController, where the recipe, its Edit form and New recipe open. The list highlights the open
 * recipe and waits while a form is open, so moving to another recipe never drops unsaved edits.
 *
 * S1: the pane's NavController and NavHost exist in both layouts, at the same place in the composition, so what the
 * pane holds (an Edit form with unsaved changes above all) survives the window narrowing and widening again. Narrow,
 * the pane fills the screen while it still holds a page; otherwise it takes no room and the list is the phone's.
 */
@Composable
internal fun RecipesHome(nav: NavController, container: AppContainer, startImport: () -> Unit, wide: Boolean) {
    val pane = rememberNavController()
    val paneEntry by pane.currentBackStackEntryAsState()
    val paneRoute = paneEntry?.destination?.route
    val paneHasPage = paneRoute != null && paneRoute != PANE_EMPTY
    val editing = paneRoute == Routes.EDIT || paneRoute == Routes.NEW
    val selectedId = if (paneRoute == Routes.RECIPE || paneRoute == Routes.EDIT) paneEntry?.arguments?.read { getLong("id") } else null
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val listWidth = listPaneWidth(maxWidth)
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                Box(Modifier.width(listWidth).fillMaxHeight()) {
                    RecipeListPane(
                        nav,
                        container,
                        startImport,
                        onOpen = { id -> if (!editing && id != selectedId) pane.showOnly(Routes.recipe(id)) },
                        onNew = { if (!editing) pane.showOnly(Routes.NEW) },
                        selectedId = selectedId,
                        openLocked = editing,
                        showSettings = false,
                    )
                }
                VerticalDivider(color = MealColors.Line)
            } else if (!paneHasPage) {
                // The phone's list: a recipe opens as a page of the main NavHost.
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    RecipeListPane(
                        nav,
                        container,
                        startImport,
                        onOpen = dropUnlessResumedWith { id: Long -> nav.navigate(Routes.recipe(id)) },
                        onNew = dropUnlessResumed { nav.navigate(Routes.NEW) },
                    )
                }
            }
            // Always composed, so its pages keep their state across a resize; zero wide when narrow and empty.
            val paneModifier = if (wide || paneHasPage) Modifier.weight(1f).fillMaxHeight() else Modifier.width(0.dp).fillMaxHeight()
            NavHost(navController = pane, startDestination = PANE_EMPTY, modifier = paneModifier) {
                composable(PANE_EMPTY) {
                    // Only the wide pane says anything: the narrow one is out of sight (and out of a screen reader's way).
                    if (LocalWideLayout.current) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(CHOOSE_A_RECIPE, color = MealColors.Muted) }
                    }
                }
                recipeDestinations(pane, container)
            }
        }
    }
}

// The recipe list with the folder banner; it opens recipes through [onOpen] and [onNew].
@Composable
private fun RecipeListPane(
    nav: NavController,
    container: AppContainer,
    startImport: () -> Unit,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    selectedId: Long? = null,
    openLocked: Boolean = false,
    showSettings: Boolean = true,
) {
    val vm: RecipeListViewModel = viewModel { RecipeListViewModel(container.recipes, container.settings) }
    val collapsed by vm.collapsed.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val book by vm.book.collectAsStateWithLifecycle()
    val minRating by vm.minRating.collectAsStateWithLifecycle()
    // The desktop's recipe folder; null on Android, where none of it shows.
    val folder = container.folder
    val problems by (folder?.problems ?: NO_PROBLEMS).collectAsStateWithLifecycle()
    RecipeListScreen(
        groups = groups,
        query = query,
        onQueryChange = vm::setQuery,
        books = books,
        book = book,
        onBookChange = vm::setBook,
        minRating = minRating,
        onMinRatingChange = vm::setMinRating,
        onOpen = onOpen,
        onNew = onNew,
        onImport = startImport,
        onSettings = dropUnlessResumed { nav.navigate(Routes.SETTINGS) },
        thumbnail = { summary ->
            summary.imageFilename?.takeIf { RecipeRepository.isSafeImageName(it) }
                ?.let { container.recipes.imageFile(RecipeRepository.thumbName(it)) }
        },
        attentionFiles = problems.filter { it.kind != RecipeFileProblem.Kind.FOLDER }.map { it.fileName }.distinct().size,
        folderMissing = problems.any { it.kind == RecipeFileProblem.Kind.FOLDER && !it.canRemoveMissing && !it.canUseNewFolder },
        folderMoved = problems.any { it.canUseNewFolder },
        filesMissing = problems.any { it.canRemoveMissing },
        onAttention = dropUnlessResumed { nav.navigate(Routes.NEEDS_ATTENTION) },
        onOpenFolder = folder?.let { f -> { f.openFolder() } },
        selectedId = selectedId,
        openLocked = openLocked,
        showSettings = showSettings,
        collapsed = collapsed,
        onToggle = vm::toggle,
        onCollapseAll = vm::collapseAll,
        onExpandAll = vm::expandAll,
    )
}

// One recipe at a time in the pane: whatever it showed goes, so Back from the new page leads to the empty pane.
private fun NavController.showOnly(route: String) {
    navigate(route) { popUpTo(PANE_EMPTY) }
}

@Composable
private fun EditDestination(vm: RecipeEditViewModel, nav: NavController, onSaved: (Long) -> Unit) {
    // Both close the form only while it is resumed; Discard's switch of section follows only a close that happened.
    val onCancel = dropUnlessResumed { nav.popBackStack() }
    val onLeave = dropUnlessResumedWith { switchSection: () -> Unit -> if (nav.popBackStack()) switchSection() }
    val state by vm.state.collectAsStateWithLifecycle()
    val savedId by vm.savedId.collectAsStateWithLifecycle()
    val categoryOptions by vm.categoryOptions.collectAsStateWithLifecycle(CategoryOptions.DEFAULT)
    // The save's outcome is state the destination consumes once, so it is never lost to a rotation.
    LaunchedEffect(savedId) {
        savedId?.let { id ->
            onSaved(id)
            vm.savedHandled()
        }
    }
    RecipeEditScreen(
        state = state,
        onCancel = onCancel,
        onSave = vm::save,
        onEdit = { change -> vm.edit(change) },
        rowActions = RowActions(
            { key, change -> vm.updateRow(key, change) },
            { key -> vm.removeRow(key) },
            { key, by -> vm.moveRow(key, by) },
            { vm.addIngredient() },
            { vm.addSection() },
            moveTo = { key, to -> vm.moveRowTo(key, to) },
        ),
        stepActions = StepActions(
            { key, text -> vm.updateStep(key, text) },
            { key -> vm.removeStep(key) },
            { key, by -> vm.moveStep(key, by) },
            { vm.addStep() },
            split = { key, cursor -> vm.splitStep(key, cursor) },
            moveTo = { key, to -> vm.moveStepTo(key, to) },
        ),
        onLeave = onLeave,
        categoryOptions = categoryOptions,
    )
}

// Stand-ins on Android, which has no recipe folder.
private val NO_PROBLEMS: StateFlow<List<RecipeFileProblem>> = MutableStateFlow(emptyList())
