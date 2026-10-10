package com.naeblis11.mealplanner.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.naeblis11.mealplanner.domain.Books

/**
 * A recipe, mirroring the Pi's `recipe` table. [rawYaml] is the whole ORF
 * file; every other column is an index rebuilt from it whenever the recipe is
 * saved. On the desktop the file in the recipes folder is the source of truth
 * and [fileName]/[fileHash] say which file and which version of it the row holds.
 */
@Entity(tableName = "recipe", indices = [Index(value = ["recipe_uuid"], unique = true), Index(value = ["name"])])
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recipe_uuid") val recipeUuid: String,
    val name: String,
    val author: String?,
    @ColumnInfo(name = "source_authors_json") val sourceAuthorsJson: String?,
    @ColumnInfo(name = "source_url") val sourceUrl: String?,
    @ColumnInfo(name = "source_book_json") val sourceBookJson: String?,
    @ColumnInfo(name = "oven_temp_json") val ovenTempJson: String?,
    @ColumnInfo(name = "oven_fan") val ovenFan: String?,
    @ColumnInfo(name = "oven_time") val ovenTime: String?,
    @ColumnInfo(name = "yields_json") val yieldsJson: String?,
    @ColumnInfo(name = "notes_json") val notesJson: String?,
    val category: String?,
    val subcategory: String?,
    @ColumnInfo(name = "image_filename") val imageFilename: String?,
    val rating: Int?,
    @ColumnInfo(name = "raw_yaml") val rawYaml: String,
    /** Desktop: the recipe's file in the recipes folder ("soup.yaml"); always null on Android. */
    @ColumnInfo(name = "file_name") val fileName: String? = null,
    /** Desktop: SHA-256 (hex) of that file's bytes when it was last indexed or written; always null on Android. */
    @ColumnInfo(name = "file_hash") val fileHash: String? = null,
)

@Entity(
    tableName = "recipe_ingredient",
    foreignKeys = [ForeignKey(RecipeEntity::class, ["id"], ["recipe_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["recipe_id"])],
)
data class RecipeIngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    @ColumnInfo(name = "order_num") val orderNum: Int,
    val name: String,
    @ColumnInfo(name = "usda_num") val usdaNum: String?,
    val amount: String?,
    val unit: String?,
    val section: String?,
    @ColumnInfo(name = "amounts_json") val amountsJson: String,
    @ColumnInfo(name = "processing_json") val processingJson: String?,
    @ColumnInfo(name = "ingredient_notes_json") val notesJson: String?,
    @ColumnInfo(name = "substitutions_json") val substitutionsJson: String?,
)

@Entity(
    tableName = "recipe_step",
    foreignKeys = [ForeignKey(RecipeEntity::class, ["id"], ["recipe_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["recipe_id"])],
)
data class RecipeStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    @ColumnInfo(name = "order_num") val orderNum: Int,
    @ColumnInfo(name = "step_text") val stepText: String,
    @ColumnInfo(name = "step_notes_json") val stepNotesJson: String?,
    @ColumnInfo(name = "haccp_json") val haccpJson: String?,
)

/** A recipe's line in the library list. */
/** One category and subcategory pairing in use (see [RecipeDao.observeCategoryPairs]). */
data class CategoryPair(val category: String?, val subcategory: String?)

data class RecipeSummary(
    val id: Long,
    val name: String,
    val category: String?,
    val subcategory: String?,
    @ColumnInfo(name = "image_filename") val imageFilename: String?,
    val rating: Int?,
    @ColumnInfo(name = "source_book_json") val sourceBookJson: String? = null,
) {
    /** The cookbook this recipe came from, or null. */
    val book: String? get() = Books.name(sourceBookJson)
}

/** What the desktop's folder sync needs from each row. */
data class RecipeFileRow(
    val id: Long,
    @ColumnInfo(name = "recipe_uuid") val recipeUuid: String,
    @ColumnInfo(name = "file_name") val fileName: String?,
    @ColumnInfo(name = "file_hash") val fileHash: String?,
)
