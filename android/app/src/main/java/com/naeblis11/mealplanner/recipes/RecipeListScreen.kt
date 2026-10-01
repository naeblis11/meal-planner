package com.naeblis11.mealplanner.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.data.RecipeSummary
import com.naeblis11.mealplanner.domain.CategoryGroup
import com.naeblis11.mealplanner.ui.PhotoImage
import com.naeblis11.mealplanner.ui.RatingStars
import com.naeblis11.mealplanner.ui.theme.MealColors
import java.io.File

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
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipes", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    TextButton(onClick = onImport) { Text("Import") }
                    TextButton(onClick = onSettings) { Text("Settings") }
                },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onNew, containerColor = MealColors.Accent, contentColor = MealColors.Paper) { Text("New recipe") } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        ) {
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
                    items(group.recipes, key = { "r:${it.id}" }) { RecipeRow(it, thumbnail(it), onOpen) }
                    for (sub in group.subcategories) {
                        item(key = "s:${group.category}/${sub.subcategory}") {
                            Text(sub.subcategory, style = MaterialTheme.typography.titleMedium, color = MealColors.Muted, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                        }
                        items(sub.recipes, key = { "r:${it.id}" }) { RecipeRow(it, thumbnail(it), onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeRow(recipe: RecipeSummary, thumbnail: File?, onOpen: (Long) -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().clickable { onOpen(recipe.id) }.padding(vertical = 10.dp),
        ) {
            PhotoImage(thumbnail, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
            Column {
                Text(recipe.name, style = MaterialTheme.typography.titleMedium)
                if (recipe.rating != null) RatingStars(recipe.rating, size = MaterialTheme.typography.bodyMedium.fontSize)
            }
        }
        HorizontalDivider(color = MealColors.LineSoft)
    }
}
