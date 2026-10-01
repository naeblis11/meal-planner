package com.naeblis11.mealplanner.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.naeblis11.mealplanner.data.PantryItemEntity
import com.naeblis11.mealplanner.domain.PantryDates
import com.naeblis11.mealplanner.ui.AisleField
import com.naeblis11.mealplanner.ui.AisleHeader
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.theme.MealColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryScreen(
    state: PantryState?,
    message: String?,
    error: String?,
    onMessageShown: (String) -> Unit,
    onAdd: (name: String, aisle: String) -> Unit,
    onSetOnHand: (PantryItemEntity, Boolean) -> Unit,
    onSave: (item: PantryItemEntity, aisle: String, addedOn: String) -> Unit,
    onToggleMatch: (PantryItemEntity) -> Unit,
    onAddToShoppingList: (PantryItemEntity) -> Unit,
    onDelete: (PantryItemEntity) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            // Consumed even if the user leaves while it shows (so it is not shown again on return),
            // and only this message: a newer one that arrived meanwhile still gets its turn.
            try {
                snackbar.showSnackbar(message)
            } finally {
                onMessageShown(message)
            }
        }
    }
    val listState = rememberLazyListState()
    // The error sits in the first item: bring it into view when an action further down fails.
    LaunchedEffect(error) { if (error != null) listState.animateScrollToItem(0) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val everything = state?.let { s -> s.onHand.flatMap { it.items } + s.removed }.orEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Pantry", style = MaterialTheme.typography.headlineMedium) }) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "add") { AddForm(error, onAdd) }
            if (state == null) {
                item(key = "loading") { CircularProgressIndicator(Modifier.padding(24.dp)) }
                return@LazyColumn
            }
            if (state.onHand.isEmpty()) {
                item(key = "empty") {
                    Text("Your pantry is empty \u2014 add an ingredient above.", color = MealColors.Muted, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            for (group in state.onHand) {
                item(key = "aisle-${group.aisle}") { AisleHeader(group.aisle, group.items.size) }
                items(group.items, key = { "item-${it.id}" }) { item -> PantryRow(item, onSetOnHand) { editingId = item.id } }
            }
            if (state.removed.isNotEmpty()) {
                item(key = "removed") { AisleHeader("Removed", state.removed.size) }
                items(state.removed, key = { "item-${it.id}" }) { item -> PantryRow(item, onSetOnHand) { editingId = item.id } }
            }
        }
    }

    everything.firstOrNull { it.id == editingId }?.let { item ->
        PantryItemDialog(
            item = item,
            onDismiss = { editingId = null },
            onSave = { aisle, addedOn -> onSave(item, aisle, addedOn); editingId = null },
            onToggleMatch = { onToggleMatch(item) },
            onAddToShoppingList = { onAddToShoppingList(item); editingId = null },
            onDelete = { deletingId = item.id; editingId = null },
        )
    }
    everything.firstOrNull { it.id == deletingId }?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("Delete ${item.name}?") },
            text = {
                Text(
                    if (item.active) {
                        "Unticking it instead keeps it here, marked as not on hand."
                    } else {
                        "Ticking it instead puts it back in your pantry."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(item); deletingId = null }) { Text("Yes, delete it", color = MealColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun AddForm(error: String?, onAdd: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var aisle by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What you've already got on hand.", color = MealColors.Muted)
        if (error != null) AttentionBanner(error)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Add an ingredient") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        AisleField(aisle, { aisle = it })
        Button(
            onClick = {
                onAdd(name, aisle)
                if (name.isNotBlank()) {
                    name = ""
                    aisle = ""
                }
            },
            shape = RoundedCornerShape(10.dp),
        ) { Text("Add") }
    }
}

@Composable
private fun PantryRow(item: PantryItemEntity, onSetOnHand: (PantryItemEntity, Boolean) -> Unit, onEdit: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // The tick is "on hand", as on the Pi: untick when it runs out.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .toggleable(value = item.active, role = Role.Checkbox, onValueChange = { onSetOnHand(item, it) }),
            ) {
                Checkbox(checked = item.active, onCheckedChange = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (item.active) MealColors.Ink else MealColors.Muted,
                        textDecoration = if (item.active) null else TextDecoration.LineThrough,
                    )
                    Text(
                        listOf(
                            if (item.exactMatch) "exact match" else "partial match",
                            item.addedOn?.let { "Added ${PantryDates.friendly(it)}" } ?: "Date added not set",
                        ).joinToString(" \u00b7 "),
                        color = MealColors.Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Edit ${item.name}" }) { Text("Edit") }
        }
        HorizontalDivider(color = MealColors.LineSoft)
    }
}

@Composable
private fun PantryItemDialog(
    item: PantryItemEntity,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onToggleMatch: () -> Unit,
    onAddToShoppingList: () -> Unit,
    onDelete: () -> Unit,
) {
    var aisle by rememberSaveable(item.id) { mutableStateOf(item.aisle ?: "") }
    var addedOn by rememberSaveable(item.id) { mutableStateOf(item.addedOn ?: "") }
    var dateError by rememberSaveable(item.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        title = { Text(item.name) },
        text = {
            Column(Modifier.widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AisleField(aisle, { aisle = it }, label = "Aisle")
                OutlinedTextField(
                    value = addedOn,
                    onValueChange = { addedOn = it; dateError = false },
                    label = { Text("Date added (YYYY-MM-DD)") },
                    isError = dateError,
                    supportingText = if (dateError) ({ Text(PantryDates.HINT) }) else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onAddToShoppingList) { Text("Add to shopping list") }
                TextButton(onClick = onToggleMatch) { Text(if (item.exactMatch) "Allow partial match" else "Require exact match") }
                TextButton(onClick = onDelete) { Text("Delete", color = MealColors.Danger) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // A bad date keeps the dialog open with what was typed, the hint beside the field.
                    try {
                        PantryDates.parse(addedOn)
                        onSave(aisle, addedOn)
                    } catch (e: IllegalArgumentException) {
                        dateError = true
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
