package com.naeblis11.mealplanner.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.data.RecipeSummary
import com.naeblis11.mealplanner.domain.ServingsInput
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.PhotoImage
import com.naeblis11.mealplanner.ui.theme.MealColors
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignMealScreen(
    slot: String,
    dayLabel: String,
    query: String,
    servings: String,
    results: List<RecipeSummary>?,
    error: String?,
    onQueryChange: (String) -> Unit,
    onServingsChange: (String) -> Unit,
    onPick: (Long) -> Unit,
    onBack: () -> Unit,
    thumbnail: (RecipeSummary) -> File? = { null },
) {
    val listState = rememberLazyListState()
    // The error is in the first item: bring it into view when a pick further down fails.
    LaunchedEffect(error) { if (error != null) listState.animateScrollToItem(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plan ${slot.lowercase(Locale.ROOT)}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(dayLabel, style = MaterialTheme.typography.titleMedium, color = MealColors.Muted)
                    if (error != null) AttentionBanner(error)
                    OutlinedTextField(
                        value = servings,
                        onValueChange = onServingsChange,
                        label = { Text("Servings (optional)") },
                        supportingText = { Text("Leave blank to cook it as the recipe is written.") },
                        isError = error == ServingsInput.HINT,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        label = { Text("Search recipes") },
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
            }
            when {
                results == null -> item(key = "loading") { CircularProgressIndicator(Modifier.padding(24.dp)) }
                results.isEmpty() -> item(key = "empty") {
                    Text(
                        if (query.isBlank()) "No recipes yet. Add one from Recipes first." else "No recipes match \"$query\".",
                        color = MealColors.Muted,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                else -> items(results, key = { it.id }) { recipe -> PickRow(recipe, thumbnail(recipe), onPick) }
            }
        }
    }
}

@Composable
private fun PickRow(recipe: RecipeSummary, thumbnail: File?, onPick: (Long) -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onPick(recipe.id) }.padding(vertical = 8.dp),
        ) {
            PhotoImage(thumbnail, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
            Text(recipe.name, style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(color = MealColors.LineSoft)
    }
}
