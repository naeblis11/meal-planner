package com.naeblis11.mealplanner.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.dragToReorder
import com.naeblis11.mealplanner.ui.theme.MealColors

/**
 * Edit Recipe's Instructions (static/step-editor.js): one field per step, numbered, each with Split here, Up, Down and
 * Remove, then Add step. Split here (or Ctrl+Enter in the field) cuts the step at the cursor; on the desktop each step
 * also drags by its handle (P5-R9).
 */
@Composable
fun StepEditor(steps: List<StepState>, actions: StepActions) {
    val reorder = remember { ReorderState() }
    val drop = actions.moveTo?.takeIf { dragToReorder }
    ReorderColumn(reorder, steps.map { it.key }, drop, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((index, step) in steps.withIndex()) key(step.key) {
            // While it is dragged the row gets the ingredient cards' paper behind it, so it covers the rows it passes.
            val row = when {
                drop == null -> Modifier
                reorder.dragging == step.key -> Modifier.reorderable(reorder, step.key).background(MealColors.Paper)
                else -> Modifier.reorderable(reorder, step.key)
            }
            StepRow(index + 1, step, actions, row) { label ->
                if (drop != null) DragHandle(reorder, step.key, label)
            }
        }
        OutlinedButton(onClick = actions.add) { Text("Add step") }
    }
}

@Composable
private fun StepRow(number: Int, step: StepState, actions: StepActions, modifier: Modifier, handle: @Composable (String) -> Unit) {
    // The field keeps its own cursor, so Split here knows where it is.
    var value by remember { mutableStateOf(TextFieldValue(step.text, TextRange(step.text.length))) }
    // The texts this row sent the list that it has not seen come back yet, oldest first. The list is collected into
    // state outside the frame, so the echo of one keystroke can arrive after the next was typed; an echo only drops
    // what it and the keystrokes before it sent (a conflated flow can skip some), and never resets the field.
    val unechoed = remember { ArrayDeque<String>() }
    // A change made elsewhere (a split took the end of this step) is a text this row never sent: it replaces the field.
    LaunchedEffect(step.text) {
        val i = unechoed.indexOf(step.text)
        if (i >= 0) {
            repeat(i + 1) { unechoed.removeFirst() }
        } else if (step.text != value.text) {
            value = TextFieldValue(step.text, TextRange(step.text.length))
            unechoed.clear()
        }
    }
    val split = {
        // The split's new first half is always taken, whatever this row was still waiting for.
        unechoed.clear()
        actions.split(step.key, value.selection.start)
    }
    val label = "step $number"
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                val changed = next.text != value.text
                value = next
                if (changed) {
                    unechoed.addLast(next.text)
                    actions.change(step.key, next.text)
                }
            },
            label = { Text("Step $number") },
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                // Ctrl+Enter (Cmd+Enter on a Mac) splits at the cursor, as on the server.
                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                if (event.type == KeyEventType.KeyDown && enter && (event.isCtrlPressed || event.isMetaPressed)) {
                    split()
                    true
                } else {
                    false
                }
            },
        )
        EditorRowButtons(
            label,
            onUp = { actions.move(step.key, -1) },
            onDown = { actions.move(step.key, 1) },
            onRemove = { actions.remove(step.key) },
            leading = {
                handle(label)
                TextButton(onClick = split, modifier = Modifier.semantics { contentDescription = "Split step $number at the cursor" }) {
                    Text("Split here")
                }
            },
        )
    }
}
