package com.naeblis11.mealplanner.ui

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.importing.ImportScreen
import com.naeblis11.mealplanner.importing.ImportViewModel
import com.naeblis11.mealplanner.recipes.RecipeDetailScreen
import com.naeblis11.mealplanner.recipes.RecipeDetailViewModel
import com.naeblis11.mealplanner.recipes.RecipeEditScreen
import com.naeblis11.mealplanner.recipes.RecipeEditViewModel
import com.naeblis11.mealplanner.recipes.RecipeListScreen
import com.naeblis11.mealplanner.recipes.RecipeListViewModel
import com.naeblis11.mealplanner.recipes.RowActions
import com.naeblis11.mealplanner.recipes.StepActions
import com.naeblis11.mealplanner.settings.BackupViewModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealPlannerApp(container: AppContainer) {
    val nav = rememberNavController()
    val context = LocalContext.current
    // The application's resolver: a lambda a ViewModel keeps must never hold the Activity.
    val resolver = remember(context) { context.applicationContext.contentResolver }
    val importVm: ImportViewModel = viewModel { ImportViewModel(container.recipes, container.imagesDir, container::newImportStagingDir) }
    val importGuard = rememberLaunchGuard()
    val pickImportFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importGuard.done()
        if (uri != null) {
            importVm.start(name = { displayName(resolver, uri) }) {
                resolver.openInputStream(uri) ?: throw IOException("Could not open the file.")
            }
            nav.navigate(Routes.IMPORT)
        }
    }
    val backupVm: BackupViewModel = viewModel { BackupViewModel(container.recipes, container.imagesDir) }
    val startImport: () -> Unit = { importGuard.launch { pickImportFile.launch(arrayOf("*/*")) } }
    val current by nav.currentBackStackEntryAsState()
    val tab = MainTab.forRoute(current?.destination?.route)
    Scaffold(
        bottomBar = { if (tab != null) MainTabs(selected = tab, onSelect = { nav.openTab(it.route) }) },
        // Each screen's own Scaffold handles the system bars; this one only adds the tab bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.RECIPES,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.RECIPES) {
                val vm: RecipeListViewModel = viewModel { RecipeListViewModel(container.recipes) }
                val groups by vm.groups.collectAsStateWithLifecycle()
                val query by vm.query.collectAsStateWithLifecycle()
                val books by vm.books.collectAsStateWithLifecycle()
                val book by vm.book.collectAsStateWithLifecycle()
                RecipeListScreen(
                    groups = groups,
                    query = query,
                    onQueryChange = vm::setQuery,
                    books = books,
                    book = book,
                    onBookChange = vm::setBook,
                    onOpen = dropUnlessResumedWith { id: Long -> nav.navigate(Routes.recipe(id)) },
                    onNew = dropUnlessResumed { nav.navigate(Routes.NEW) },
                    onImport = startImport,
                    onSettings = dropUnlessResumed { nav.navigate(Routes.SETTINGS) },
                    thumbnail = { summary ->
                        summary.imageFilename?.takeIf { RecipeRepository.isSafeImageName(it) }
                            ?.let { container.recipes.imageFile(RecipeRepository.thumbName(it)) }
                    },
                )
            }
            composable(Routes.RECIPE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments!!.getLong("id")
                val vm: RecipeDetailViewModel = viewModel { RecipeDetailViewModel(id, container.recipes, container.imagesDir) }
                val photoVersion by vm.photoVersion.collectAsStateWithLifecycle()
                val photoGuard = rememberLaunchGuard()
                val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
                    photoGuard.done()
                    val file = File(container.cameraDir, "capture.jpg")
                    if (saved) {
                        // Read once, then delete, so a later capture can never reuse this file.
                        vm.setPhoto { ByteArrayInputStream(file.readBytes().also { file.delete() }) }
                    } else {
                        file.delete()
                    }
                }
                val choosePhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    photoGuard.done()
                    if (uri != null) vm.setPhoto { resolver.openInputStream(uri) ?: throw IOException("Could not open that photo.") }
                }
                val view by vm.view.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()
                val loadError by vm.loadError.collectAsStateWithLifecycle()
                val deleted by vm.deleted.collectAsStateWithLifecycle()
                val plannedMeals by vm.plannedMeals.collectAsStateWithLifecycle()
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
                    onTakePhoto = {
                        val file = File(container.cameraDir, "capture.jpg")
                        file.delete()
                        try {
                            photoGuard.launch { takePhoto.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }
                        } catch (e: ActivityNotFoundException) {
                            vm.showMessage("No camera app is available.")
                        }
                    },
                    onChoosePhoto = { photoGuard.launch { choosePhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
                    onRemovePhoto = vm::removePhoto,
                    photoVersion = photoVersion,
                    plannedMeals = plannedMeals,
                )
            }
            composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments!!.getLong("id")
                val vm: RecipeEditViewModel = viewModel { RecipeEditViewModel(id, container.recipes, createSavedStateHandle()) }
                EditDestination(vm, onDone = dropUnlessResumed { nav.popBackStack() }, onSaved = { popIfCurrent(nav, entry) })
            }
            composable(Routes.IMPORT) {
                val state by importVm.state.collectAsStateWithLifecycle()
                ImportScreen(
                    state = state,
                    onUpdate = { id, change -> importVm.update(id, change) },
                    onConfirm = { importVm.confirm() },
                    onCancel = { importVm.cancel(); leaveImport(nav) },
                    onDone = { importVm.done(); leaveImport(nav) },
                    onBackToRecipes = {
                        importVm.done()
                        // Wherever the import was started (Settings too), its result leads to the recipe list.
                        if (nav.currentDestination?.route == Routes.IMPORT) nav.popBackStack(Routes.RECIPES, inclusive = false)
                    },
                )
            }
            composable(Routes.NEW) { entry ->
                val vm: RecipeEditViewModel = viewModel { RecipeEditViewModel(null, container.recipes, createSavedStateHandle()) }
                EditDestination(vm, onDone = dropUnlessResumed { nav.popBackStack() }, onSaved = { newId ->
                    if (nav.currentBackStackEntry?.id == entry.id) {
                        nav.navigate(Routes.recipe(newId)) { popUpTo(Routes.NEW) { inclusive = true } }
                    }
                })
            }
            settingsDestination(nav, container, backupVm, startImport)
            plannerDestinations(nav, container)
        }
    }
}

@Composable
private fun EditDestination(vm: RecipeEditViewModel, onDone: () -> Unit, onSaved: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val savedId by vm.savedId.collectAsStateWithLifecycle()
    // The save's outcome is state the destination consumes once, so it is never lost to a rotation.
    LaunchedEffect(savedId) {
        savedId?.let { id ->
            onSaved(id)
            vm.savedHandled()
        }
    }
    RecipeEditScreen(
        state = state,
        onCancel = onDone,
        onSave = vm::save,
        onEdit = { change -> vm.edit(change) },
        rowActions = RowActions(
            { key, change -> vm.updateRow(key, change) },
            { key -> vm.removeRow(key) },
            { key, by -> vm.moveRow(key, by) },
            { vm.addIngredient() },
            { vm.addSection() },
        ),
        stepActions = StepActions(
            { key, text -> vm.updateStep(key, text) },
            { key -> vm.removeStep(key) },
            { key, by -> vm.moveStep(key, by) },
            { vm.addStep() },
        ),
    )
}

/** The picked file's name ("box.mmf"), for the reader's type check and the messages. */
private fun displayName(resolver: ContentResolver, uri: Uri): String =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "import"

/** Pops the import screen once, even if Back and the screen's own exit both ask. */
private fun leaveImport(nav: androidx.navigation.NavController) {
    if (nav.currentDestination?.route == Routes.IMPORT) nav.popBackStack()
}

/**
 * Switches top-level screen: Recipes stays at the bottom of the back stack (so Back
 * from any tab returns there), and each tab's state is saved and restored. Navigating
 * to the tab already shown does nothing, so a double tap is harmless.
 */
private fun NavController.openTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
