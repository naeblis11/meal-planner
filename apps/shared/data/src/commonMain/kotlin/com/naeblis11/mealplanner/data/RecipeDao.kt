package com.naeblis11.mealplanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
    @Query("SELECT id FROM recipe WHERE recipe_uuid = :uuid")
    suspend fun idForUuid(uuid: String): Long?

    @Insert
    suspend fun insertRecipe(recipe: RecipeEntity): Long

    @Update
    suspend fun updateRecipe(recipe: RecipeEntity)

    @Query("DELETE FROM recipe WHERE id = :id")
    suspend fun deleteRecipe(id: Long)

    @Insert
    suspend fun insertIngredients(rows: List<RecipeIngredientEntity>)

    @Insert
    suspend fun insertSteps(rows: List<RecipeStepEntity>)

    @Query("DELETE FROM meal_plan WHERE recipe_id = :recipeId")
    suspend fun deletePlanEntries(recipeId: Long)

    @Query("SELECT COUNT(*) FROM meal_plan WHERE recipe_id = :recipeId")
    fun observePlannedCount(recipeId: Long): Flow<Int>

    @Query("DELETE FROM recipe_ingredient WHERE recipe_id = :recipeId")
    suspend fun deleteIngredients(recipeId: Long)

    @Query("DELETE FROM recipe_step WHERE recipe_id = :recipeId")
    suspend fun deleteSteps(recipeId: Long)

    @Query("SELECT * FROM recipe WHERE id = :id")
    suspend fun recipe(id: Long): RecipeEntity?

    @Query("SELECT * FROM recipe_ingredient WHERE recipe_id = :recipeId ORDER BY order_num")
    suspend fun ingredients(recipeId: Long): List<RecipeIngredientEntity>

    @Query("SELECT * FROM recipe_step WHERE recipe_id = :recipeId ORDER BY order_num")
    suspend fun steps(recipeId: Long): List<RecipeStepEntity>

    @Query("SELECT name FROM recipe")
    suspend fun names(): List<String>

    @Query("SELECT * FROM recipe ORDER BY name")
    suspend fun allRecipes(): List<RecipeEntity>

    @Query("SELECT COUNT(*) FROM recipe WHERE image_filename = :imageFilename")
    suspend fun countImageUsers(imageFilename: String): Int

    @Query("SELECT id, name, category, subcategory, image_filename, rating, source_book_json FROM recipe ORDER BY name")
    fun observeSummaries(): Flow<List<RecipeSummary>>

    /** The Pi's `_search_recipes`: name, category, subcategory, cookbook, ingredient names and section headings. */
    @Query(
        """SELECT DISTINCT r.id, r.name, r.category, r.subcategory, r.image_filename, r.rating, r.source_book_json
           FROM recipe r
           LEFT JOIN recipe_ingredient i ON i.recipe_id = r.id
           WHERE r.name LIKE :like
              OR r.category LIKE :like
              OR r.subcategory LIKE :like
              OR r.source_book_json LIKE :like
              OR i.name LIKE :like
              OR i.section LIKE :like
           ORDER BY r.name""",
    )
    fun search(like: String): Flow<List<RecipeSummary>>

    @Query("SELECT * FROM recipe WHERE id = :id")
    fun observeRecipe(id: Long): Flow<RecipeEntity?>

    @Query("SELECT name FROM recipe WHERE id != :id")
    suspend fun namesExcept(id: Long): List<String>

    /**
     * recipe_sync.py's lookup by file_path: the row a recipe file already has, when its uuid is new.
     * Letter case is ignored, as Windows file names ignore it ("Soup.yaml" is the file "soup.yaml" was).
     */
    @Query("SELECT id FROM recipe WHERE file_name = :fileName COLLATE NOCASE")
    suspend fun idForFileName(fileName: String): Long?

    /** The file a recipe is kept in on the desktop; an edit writes back to it. */
    @Query("SELECT file_name FROM recipe WHERE recipe_uuid = :uuid")
    suspend fun fileNameForUuid(uuid: String): String?

    /** Every file name the index knows, so a new recipe's file never takes one. */
    @Query("SELECT file_name FROM recipe WHERE file_name IS NOT NULL")
    suspend fun fileNames(): List<String>

    @Query("SELECT id, recipe_uuid, file_name, file_hash FROM recipe ORDER BY id")
    suspend fun fileIndex(): List<RecipeFileRow>
}
