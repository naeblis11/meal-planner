package com.naeblis11.mealplanner.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.domain.Week
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.PhotoImage
import com.naeblis11.mealplanner.ui.Pill
import com.naeblis11.mealplanner.ui.UiMessage
import com.naeblis11.mealplanner.ui.theme.MealColors
import com.naeblis11.mealplanner.ui.theme.MealSpacing
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

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
    /** The wide layout (P3-R4): the whole week as a grid under the controls. */
    wide: Boolean = false,
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
        if (wide) {
            WeekGrid(
                week, error, sendProblem, adding, sending, onPrevious, onNext, onThisWeek, onAddToShoppingList, onSend,
                onOpenSettings, onOpenAppSettings, onOpenRecipe, onAssign, onRemove, thumbnail, Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanIntro(error, sendProblem, onOpenSettings, onOpenAppSettings)
                    Text(week?.label ?: "", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPrevious, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Previous") }
                        OutlinedButton(onClick = onThisWeek, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("This week") }
                        OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Next") }
                    }
                    PlanActions(week != null, adding, sending, onAddToShoppingList, onSend, Modifier.fillMaxWidth().heightIn(min = 48.dp))
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

/** The blurb and any problems above the week's controls; emits into the caller's column, phone and grid alike. */
@Composable
private fun PlanIntro(error: String?, sendProblem: SendProblem?, onOpenSettings: () -> Unit, onOpenAppSettings: () -> Unit) {
    Text("Plan your meals for the week.", color = MealColors.Muted)
    if (error != null) AttentionBanner(error)
    if (sendProblem != null) SendProblemBanner(sendProblem, onOpenSettings, onOpenAppSettings)
}

/** The two week-wide actions, emitted into the caller's column or row; [buttonModifier] sizes them for the layout. */
@Composable
private fun PlanActions(
    hasWeek: Boolean,
    adding: Boolean,
    sending: Boolean,
    onAddToShoppingList: () -> Unit,
    onSend: () -> Unit,
    buttonModifier: Modifier,
) {
    Button(onClick = onAddToShoppingList, enabled = hasWeek && !adding, shape = RoundedCornerShape(10.dp), modifier = buttonModifier) {
        Text("Add this week's meals to the shopping list")
    }
    OutlinedButton(onClick = onSend, enabled = hasWeek && !sending, shape = RoundedCornerShape(10.dp), modifier = buttonModifier) {
        Text(if (sending) "Sending to your calendar..." else "Send this week to Google Calendar")
    }
}

/** A send problem, and the way out of it: Settings to choose a calendar, or this app's system settings for the permission. */
@Composable
private fun SendProblemBanner(problem: SendProblem, onOpenSettings: () -> Unit, onOpenAppSettings: () -> Unit) {
    AttentionBanner(problem.text)
    when (problem) {
        SendProblem.NotSetUp, SendProblem.CalendarGone, is SendProblem.NeedsSettings ->
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
    compact: Boolean = false,
) {
    val (tint, ink) = slotColors(slot.slot)
    // Every slot has the same three buttons; the screen reader hears which slot each is for.
    val where = "${slot.slot} on ${day.label}"
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MealColors.Line),
        modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 0.dp else 4.dp),
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
            } else if (compact) {
                // A grid column is narrow, and a day of three meals should fit without scrolling (owner, 2026-10-09):
                // the name across the card, then a small photo beside the servings, then the actions side by side.
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenRecipe(meal.recipeId) }.padding(start = 8.dp, top = 8.dp, end = 8.dp),
                ) {
                    Text(
                        meal.recipeName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (meal.imageFilename != null || meal.servings != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (meal.imageFilename != null) {
                                PhotoImage(
                                    thumbnail(meal.imageFilename),
                                    contentDescription = null,
                                    modifier = Modifier.size(GRID_PHOTO).clip(RoundedCornerShape(6.dp)),
                                )
                            }
                            meal.servings?.let { Text("for $it", color = MealColors.Muted, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                SlotActions(where, { onAssign(day.date, slot.slot) }, { onRemove(day.date, slot.slot) }, stacked = true)
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
                SlotActions(where, { onAssign(day.date, slot.slot) }, { onRemove(day.date, slot.slot) }, stacked = false)
            }
        }
    }
}

/**
 * A planned meal's Change and Remove: side by side on the phone; in a grid column ([stacked]) a little shorter, side
 * by side where the column is wide enough and one under the other where it isn't.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotActions(where: String, onChange: () -> Unit, onRemove: () -> Unit, stacked: Boolean) {
    val minHeight = if (stacked) 36.dp else 48.dp
    // Tighter and a little smaller in a grid column, so both fit side by side even in a 1024 dp window's columns.
    val padding = if (stacked) PaddingValues(horizontal = 6.dp) else ButtonDefaults.TextButtonContentPadding
    val label: @Composable (String) -> Unit = { text ->
        if (stacked) Text(text, style = MaterialTheme.typography.labelMedium) else Text(text)
    }
    val buttons: @Composable () -> Unit = {
        TextButton(
            onClick = onChange,
            contentPadding = padding,
            modifier = Modifier.heightIn(min = minHeight).semantics { contentDescription = "Change $where" },
        ) { label("Change") }
        TextButton(
            onClick = onRemove,
            contentPadding = padding,
            modifier = Modifier.heightIn(min = minHeight).semantics { contentDescription = "Remove $where" },
        ) { label("Remove") }
    }
    if (stacked) FlowRow(Modifier.padding(horizontal = 2.dp)) { buttons() } else Row(Modifier.padding(horizontal = 4.dp)) { buttons() }
}

// A planned meal's photo in a grid column: small, beside the servings under the name.
private val GRID_PHOTO = 28.dp

/**
 * The wide Calendar (the server's `.week-grid`): the week's controls in two rows, then seven equal columns, one per
 * day, each with its heading and its breakfast, lunch and dinner cards.
 */
@Composable
private fun WeekGrid(
    week: WeekView?,
    error: String?,
    sendProblem: SendProblem?,
    adding: Boolean,
    sending: Boolean,
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
    thumbnail: (String?) -> File?,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    // Problems sit at the top: bring them into view when an action further down fails.
    LaunchedEffect(error, sendProblem) { if (error != null || sendProblem != null) scroll.animateScrollTo(0) }
    Column(
        modifier.fillMaxSize().verticalScroll(scroll).padding(start = MealSpacing.Band, end = MealSpacing.Band, bottom = MealSpacing.Band),
        verticalArrangement = Arrangement.spacedBy(MealSpacing.Row),
    ) {
        PlanIntro(error, sendProblem, onOpenSettings, onOpenAppSettings)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(week?.label ?: "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onPrevious, modifier = Modifier.heightIn(min = 40.dp)) { Text("Previous") }
            OutlinedButton(onClick = onThisWeek, modifier = Modifier.heightIn(min = 40.dp)) { Text("This week") }
            OutlinedButton(onClick = onNext, modifier = Modifier.heightIn(min = 40.dp)) { Text("Next") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanActions(week != null, adding, sending, onAddToShoppingList, onSend, Modifier)
        }
        if (week == null) {
            CircularProgressIndicator(Modifier.padding(24.dp))
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MealSpacing.Row), modifier = Modifier.padding(top = MealSpacing.Row)) {
            for (day in week.days) {
                // Today is a tinted column, not an extra line, so every day's heading and meals stay in line (owner,
                // 2026-10-09). Every column has the same inner padding, so the tint changes nothing's size.
                Column(
                    Modifier.weight(1f)
                        .background(if (day.isToday) MealColors.AccentTint else Color.Transparent, RoundedCornerShape(10.dp))
                        .padding(TODAY_INSET),
                    verticalArrangement = Arrangement.spacedBy(MealSpacing.Row),
                ) {
                    DayHeading(day)
                    for (slot in day.slots) SlotCard(day, slot, onOpenRecipe, onAssign, onRemove, thumbnail, compact = true)
                }
            }
        }
    }
}

/**
 * A grid column's heading: the weekday (accent on today) above its date, as on the server's grid. Today says so on
 * the date line ("Oct 9, Today"), so its heading is the same two lines as every other day's.
 */
@Composable
private fun DayHeading(day: DayView) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            day.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = if (day.isToday) MealColors.Accent else MealColors.Ink,
        )
        val date = "${Week.month(day.date)} ${day.date.dayOfMonth}"
        Text(
            if (day.isToday) "$date \u00b7 Today" else date,
            style = MaterialTheme.typography.bodySmall.let { if (day.isToday) it.copy(fontWeight = FontWeight.Bold) else it },
            color = if (day.isToday) MealColors.Accent else MealColors.Muted,
            maxLines = 1,
        )
    }
}

// Each grid column's inner padding; today's tint fills it.
private val TODAY_INSET = 4.dp

/** DESIGN.md's meal-slot tints: the header's background and its ink. */
fun slotColors(slot: String): Pair<Color, Color> = when (slot) {
    "Breakfast" -> MealColors.BreakfastTint to MealColors.BreakfastInk
    "Lunch" -> MealColors.LunchTint to MealColors.LunchInk
    else -> MealColors.DinnerTint to MealColors.DinnerInk
}
