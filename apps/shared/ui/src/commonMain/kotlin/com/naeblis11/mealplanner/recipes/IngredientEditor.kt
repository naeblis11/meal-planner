package com.naeblis11.mealplanner.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.domain.SubmittedRow
import com.naeblis11.mealplanner.ui.dragToReorder
import com.naeblis11.mealplanner.ui.theme.MealColors

/**
 * The ingredient editor shared by Edit Recipe and Import Review: one ordered
 * list where a heading row starts a sub-recipe ("section") that owns the
 * ingredients below it; a heading with no name returns to no section.
 * On the desktop each row also drags by its handle ([onMoveTo], P5-R9); a heading moves alone, as on the server.
 */
@Composable
fun IngredientEditor(
    rows: List<EditorRowState>,
    onChange: (String, (EditorRowState) -> EditorRowState) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onAddIngredient: () -> Unit,
    onAddSection: () -> Unit,
    /** A drag's drop: the row's key and the index it lands at. Null (and the phone) keeps Up and Down alone. */
    onMoveTo: ((String, Int) -> Unit)? = null,
) {
    val reorder = remember { ReorderState() }
    val drop = onMoveTo?.takeIf { dragToReorder }
    ReorderColumn(reorder, rows.map { it.key }, drop, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((index, row) in rows.withIndex()) key(row.key) {
            val attention = row.needsInput
            val label = rowLabel(row, index)
            Card(
                colors = CardDefaults.cardColors(containerColor = if (attention) MealColors.DangerTint else MealColors.Paper),
                border = CardDefaults.outlinedCardBorder(),
                modifier = Modifier.fillMaxWidth().then(if (drop != null) Modifier.reorderable(reorder, row.key) else Modifier),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (row.kind == SubmittedRow.SECTION) {
                        OutlinedTextField(
                            value = row.name,
                            onValueChange = { text -> onChange(row.key) { it.copy(name = text) } },
                            label = { Text("Sub-recipe heading (blank: no sub-recipe)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            value = row.name,
                            onValueChange = { text -> onChange(row.key) { it.copy(name = text) } },
                            label = { Text("Ingredient") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (row.locked) {
                            Text("This ingredient has several amounts; they are kept as they are.", color = MealColors.Muted)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = row.amount,
                                    onValueChange = { text -> onChange(row.key) { it.copy(amount = text, needsInput = false) } },
                                    label = { Text("Amount") },
                                    singleLine = true,
                                    isError = attention,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = row.unit,
                                    onValueChange = { text -> onChange(row.key) { it.copy(unit = text) } },
                                    label = { Text("Unit") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (attention) Text("Check this amount", color = MealColors.Danger)
                        }
                        OutlinedTextField(
                            value = row.notes,
                            onValueChange = { text -> onChange(row.key) { it.copy(notes = text) } },
                            label = { Text("Notes (separate several with ;)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    EditorRowButtons(
                        label,
                        onUp = { onMove(row.key, -1) },
                        onDown = { onMove(row.key, 1) },
                        onRemove = { onRemove(row.key) },
                        leading = if (drop != null) {
                            { DragHandle(reorder, row.key, label) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddIngredient) { Text("Add ingredient") }
            OutlinedButton(onClick = onAddSection) { Text("Add sub-recipe heading") }
        }
    }
}

// What a screen reader calls a row's buttons: "Flour", "heading Sauce", "ingredient 3".
private fun rowLabel(row: EditorRowState, index: Int): String {
    val name = row.name.trim()
    return if (row.kind == SubmittedRow.SECTION) {
        if (name.isEmpty()) "heading ${index + 1}" else "heading $name"
    } else {
        name.ifEmpty { "ingredient ${index + 1}" }
    }
}

/**
 * Up, Down and Remove for one editor row (an ingredient, a heading, a step),
 * each described with the row's [label] ("Move Flour up", "Remove step 3").
 * Remove is reversible until Save, so it is a muted label, not danger red, as on the Pi.
 * The buttons wrap onto a second line on a narrow screen or with large text, rather than run off the edge; [leading]
 * goes before them (the desktop's drag handle, a step's Split here).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorRowButtons(label: String, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        TextButton(onClick = onUp, modifier = Modifier.semantics { contentDescription = "Move $label up" }) { Text("Up") }
        TextButton(onClick = onDown, modifier = Modifier.semantics { contentDescription = "Move $label down" }) { Text("Down") }
        TextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = "Remove $label" }) {
            Text("Remove", color = MealColors.Muted)
        }
    }
}
