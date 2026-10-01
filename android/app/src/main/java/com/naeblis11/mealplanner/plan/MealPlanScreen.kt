package com.naeblis11.mealplanner.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.PhotoImage
import com.naeblis11.mealplanner.ui.Pill
import com.naeblis11.mealplanner.ui.UiMessage
import com.naeblis11.mealplanner.ui.theme.MealColors
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealPlanScreen(
    week: WeekView?,
    message: UiMessage?,
    error: String?,
    adding: Boolean,
    sending: Boolean,
    sendProblem: SendProblem?,
    onMessageShown: (UiMessage) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onThisWeek: () -> Unit,
    onAddToShoppingList: () -> Unit,
    onSend: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onAssign: (LocalDate, String) -> Unit,
    onRemove: (LocalDate, String) -> Unit,
    thumbnail: (String?) -> File? = { null },
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            // Consumed even if the user leaves while it shows (so it is not shown again on return),
            // and only this message: a newer one that arrived meanwhile still gets its turn.
            try {
                snackbar.showSnackbar(message.text)
            } finally {
                onMessageShown(message)
            }
        }
    }
    val listState = rememberLazyListState()
    // Problems sit in the first item: bring them into view when an action further down fails.
    LaunchedEffect(error, sendProblem) { if (error != null || sendProblem != null) listState.animateScrollToItem(0) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Meal plan", style = MaterialTheme.typography.headlineMedium) }) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Plan your meals for the week.", color = MealColors.Muted)
                    if (error != null) AttentionBanner(error)
                    if (sendProblem != null) SendProblemBanner(sendProblem, onOpenSettings, onOpenAppSettings)
                    Text(week?.label ?: "", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPrevious, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Previous") }
                        OutlinedButton(onClick = onThisWeek, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("This week") }
                        OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Next") }
                    }
                    Button(
                        onClick = onAddToShoppingList,
                        enabled = week != null && !adding,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Add this week's meals to the shopping list") }
                    OutlinedButton(
                        onClick = onSend,
                        enabled = week != null && !sending,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (sending) "Sending to your calendar..." else "Send this week to Google Calendar") }
                }
            }
            if (week == null) {
                item(key = "loading") { CircularProgressIndicator(Modifier.padding(24.dp)) }
                return@LazyColumn
            }
            for (day in week.days) {
                item(key = "day-${day.date}") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                    ) {
                        Text(day.label, style = MaterialTheme.typography.titleLarge)
                        if (day.isToday) Pill("Today")
                    }
                }
                for (slot in day.slots) {
                    item(key = "slot-${day.date}-${slot.slot}") { SlotCard(day, slot, onOpenRecipe, onAssign, onRemove, thumbnail) }
                }
            }
        }
    }
}

/** A send problem, and the way out of it: Settings to choose a calendar, or this app's system settings for the permission. */
@Composable
private fun SendProblemBanner(problem: SendProblem, onOpenSettings: () -> Unit, onOpenAppSettings: () -> Unit) {
    AttentionBanner(problem.text)
    when (problem) {
        SendProblem.NotSetUp, SendProblem.CalendarGone ->
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Settings") }
        SendProblem.PermissionDenied ->
            OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open app settings") }
        is SendProblem.Failed -> {}
    }
}

@Composable
private fun SlotCard(
    day: DayView,
    slot: SlotView,
    onOpenRecipe: (Long) -> Unit,
    onAssign: (LocalDate, String) -> Unit,
    onRemove: (LocalDate, String) -> Unit,
    thumbnail: (String?) -> File?,
) {
    val (tint, ink) = slotColors(slot.slot)
    // Every slot has the same three buttons; the screen reader hears which slot each is for.
    val where = "${slot.slot} on ${day.label}"
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MealColors.Line),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            Text(
                slot.slot,
                color = ink,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth().background(tint).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            val meal = slot.meal
            if (meal == null) {
                TextButton(
                    onClick = { onAssign(day.date, slot.slot) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Add recipe for $where" },
                ) { Text("Add recipe") }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenRecipe(meal.recipeId) }.padding(12.dp),
                ) {
                    PhotoImage(thumbnail(meal.imageFilename), contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(meal.recipeName, style = MaterialTheme.typography.titleMedium)
                        meal.servings?.let { Text("for $it", color = MealColors.Muted) }
                    }
                }
                Row(Modifier.padding(horizontal = 4.dp)) {
                    TextButton(
                        onClick = { onAssign(day.date, slot.slot) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Change $where" },
                    ) { Text("Change") }
                    TextButton(
                        onClick = { onRemove(day.date, slot.slot) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Remove $where" },
                    ) { Text("Remove") }
                }
            }
        }
    }
}

/** DESIGN.md's meal-slot tints: the header's background and its ink. */
internal fun slotColors(slot: String): Pair<Color, Color> = when (slot) {
    "Breakfast" -> MealColors.BreakfastTint to MealColors.BreakfastInk
    "Lunch" -> MealColors.LunchTint to MealColors.LunchInk
    else -> MealColors.DinnerTint to MealColors.DinnerInk
}
