package com.naeblis11.mealplanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** shopping_list.py's statements, and what "Add this week" reads from the rest of the database. */
@Dao
interface ShoppingDao {
    /** shopping_list.list_items: not-in-pantry first, then by name. */
    @Query("SELECT * FROM shopping_list_item ORDER BY in_pantry, name COLLATE NOCASE")
    fun observeAll(): Flow<List<ShoppingItemEntity>>

    /** Row-id order: a merge goes into the first matching row, as in Python. */
    @Query("SELECT * FROM shopping_list_item ORDER BY id")
    suspend fun allInIdOrder(): List<ShoppingItemEntity>

    @Query("SELECT * FROM shopping_list_item WHERE name = :name COLLATE NOCASE ORDER BY id")
    suspend fun byName(name: String): List<ShoppingItemEntity>

    @Query("SELECT * FROM shopping_list_item WHERE id = :id")
    suspend fun item(id: Long): ShoppingItemEntity?

    @Insert
    suspend fun insert(item: ShoppingItemEntity): Long

    @Update
    suspend fun update(item: ShoppingItemEntity)

    @Query("UPDATE shopping_list_item SET checked = :checked WHERE id = :id")
    suspend fun setChecked(id: Long, checked: Boolean)

    @Query("UPDATE shopping_list_item SET aisle = :aisle WHERE id = :id")
    suspend fun setAisle(id: Long, aisle: String?)

    @Query("DELETE FROM shopping_list_item WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM shopping_list_item")
    suspend fun clear()

    /** One row per planned meal (not per recipe), in plan-row order like the Python query. */
    @Query(
        """SELECT mp.recipe_id, mp.servings, r.yields_json
           FROM meal_plan mp JOIN recipe r ON r.id = mp.recipe_id
           WHERE mp.date BETWEEN :first AND :last
           ORDER BY mp.id""",
    )
    suspend fun plannedForShopping(first: String, last: String): List<PlannedForShopping>

    @Query("SELECT name, amount, unit FROM recipe_ingredient WHERE recipe_id = :recipeId ORDER BY order_num")
    suspend fun ingredientLines(recipeId: Long): List<IngredientLineRow>

    /** Only what is on hand: a crossed-out item has run out and needs buying. */
    @Query("SELECT name, exact_match FROM pantry_item WHERE active = 1")
    suspend fun onHandPantry(): List<PantryRuleRow>

    @Query("SELECT * FROM ingredient_aisle")
    suspend fun knownAisles(): List<IngredientAisleEntity>

    @Query("SELECT aisle FROM ingredient_aisle WHERE name = :name")
    suspend fun rememberedAisle(name: String): String?

    /** REPLACE on the unique NOCASE name: one remembered aisle per ingredient. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun rememberAisle(entry: IngredientAisleEntity)

    @Query("DELETE FROM ingredient_aisle WHERE name = :name")
    suspend fun forgetAisle(name: String)

    /** -1 when the NOCASE name is already remembered (INSERT OR IGNORE): the first-run import keeps what is there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAisleOrIgnore(entry: IngredientAisleEntity): Long
}
