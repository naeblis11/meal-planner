package com.naeblis11.mealplanner.folder

import kotlinx.coroutines.flow.StateFlow

/**
 * A recipe file the desktop couldn't take as it is, for the Needs attention list. [missingFiles] is set on the FOLDER
 * problem for recipe files held back from a mass removal: how many are missing.
 */
data class RecipeFileProblem(
    val fileName: String,
    val kind: Kind,
    val message: String,
    val missingFiles: Int = 0,
    /**
     * Set on the FOLDER problem while the recipe folder is not the one the database was made from (P7-R10c): that
     * folder's path. Nothing is removed until the user chooses "Use the new folder" (RecipeFolderStatus.useNewFolder)
     * or points the app back.
     */
    val movedFrom: String? = null,
) {
    enum class Kind {
        /** Not valid YAML, a missing field, not UTF-8: not indexed (an older index row is kept). */
        UNREADABLE,

        /** Another file already has this recipe_uuid: not indexed until one of them gets a new ID. */
        DUPLICATE_ID,

        /**
         * The recipe folder itself: it is missing or can't be read, so nothing is indexed or removed until it is
         * back; or (with missingFiles) many of its files went at once, and they are kept in the app until they are
         * back or the user removes them. [fileName] is the folder's name. Said as such, never counted as a recipe file.
         */
        FOLDER,
    }

    /** "Assign new ID" fixes this one. */
    val canAssignNewId: Boolean get() = kind == Kind.DUPLICATE_ID

    /** Recipe files held back from a mass removal: "Remove them from the app" removes them (confirmMassRemoval). */
    val canRemoveMissing: Boolean get() = kind == Kind.FOLDER && missingFiles > 0 && movedFrom == null

    /** "Use the new folder" and "Point back" answer this one: the recipe folder changed (P7-R10c). */
    val canUseNewFolder: Boolean get() = kind == Kind.FOLDER && movedFrom != null
}

/** The Needs attention message while the recipe folder is not the one the database was made from (P7-R10c). */
fun folderMovedMessage(old: String, new: String): String =
    "The recipe folder changed from $old to $new. Nothing is removed until you confirm."

/** "Point back": how to give the app its old recipe folder again (P7-R10c). */
fun pointBackInstructions(old: String): String =
    "To point back: quit Meal Planner (tray icon > Quit), put the recipe folder back at $old (or set " +
        "MEAL_PLANNER_DATA_DIR to the folder that holds it), then start Meal Planner again. Nothing is removed meanwhile."

/** Recipes held back from a mass removal: their index row [ids], and how many [plannedMeals] use them. */
data class HeldRecipes(val ids: Set<Long>, val plannedMeals: Int)

/** The desktop's recipe folder as the screens see it; AppContainer.folder is null on Android. */
interface RecipeFolderStatus {
    /** Files that need attention, in file-name order; empty when all is well. */
    val problems: StateFlow<List<RecipeFileProblem>>

    /** Gives [fileName] a new recipe_uuid (one line changed), then syncs. Only for a listed duplicate. */
    suspend fun assignNewId(fileName: String)

    /**
     * Removes the recipes held back from a mass removal (the problem that canRemoveMissing) that are among [ids] (the
     * ones the confirmation counted, from [heldSummary]) and whose files are still gone when the folder is listed
     * again. Those that came back stay, and so does one held (or gone) only after the confirmation was opened.
     */
    suspend fun confirmMassRemoval(ids: Set<Long>)

    /**
     * For the confirmation before [confirmMassRemoval]: the recipes held back from a mass removal, and how many
     * planned meals use them (and would go with them), as the last sync left them.
     */
    suspend fun heldSummary(): HeldRecipes

    /**
     * "Use the new folder" (P7-R10c, P7-R10e) only accepts the current recipe folder as the library; it never removes
     * anything. Recipes held because the folder changed join the "Remove them from the app" hold, which still asks
     * with counts before anything goes; a genuine mass-removal hold is kept. Does nothing unless the folder changed.
     */
    suspend fun useNewFolder() {}

    /**
     * Opens the recipes folder in Explorer. Never throws, so a button can call it directly: false when the
     * folder is missing (the problems list already says so) or Explorer couldn't be asked to open it.
     */
    fun openFolder(): Boolean
}
