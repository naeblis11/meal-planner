package com.naeblis11.mealplanner.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naeblis11.mealplanner.domain.GroceryCategories
import com.naeblis11.mealplanner.ui.theme.MealColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo from app storage, decoded off the main thread; a quiet placeholder while loading or when missing. */
@Composable
fun PhotoImage(file: File?, contentDescription: String?, modifier: Modifier = Modifier, version: Any? = null) {
    val bitmap by produceState<ImageBitmap?>(null, file?.path, file?.lastModified(), version) {
        value = withContext(Dispatchers.IO) {
            file?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        }
    }
    val image = bitmap
    if (image != null) {
        Image(image, contentDescription, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MealColors.PaperAlt))
    }
}

/** Five stars; tapping star n sets n when [onRate] is given. Display-only stars read as one "Rated N of 5" node. */
@Composable
fun RatingStars(rating: Int?, onRate: ((Int) -> Unit)? = null, size: TextUnit = 28.sp) {
    val rowModifier = if (onRate == null) {
        Modifier.clearAndSetSemantics { contentDescription = if (rating == null) "Not rated" else "Rated $rating of 5" }
    } else {
        Modifier
    }
    Row(rowModifier) {
        for (n in 1..5) {
            val filled = rating != null && n <= rating
            val glyph: @Composable () -> Unit = {
                Text(
                    text = if (filled) "\u2605" else "\u2606",
                    color = if (filled) MealColors.Accent else MealColors.Muted2,
                    fontSize = size,
                )
            }
            if (onRate != null) {
                Box(
                    Modifier
                        .defaultMinSize(48.dp, 48.dp)
                        .clickable { onRate(n) }
                        .semantics {
                            contentDescription = "Rate $n star${if (n == 1) "" else "s"}"
                            role = Role.Button
                            selected = filled
                        },
                    contentAlignment = Alignment.Center,
                ) { glyph() }
            } else {
                glyph()
            }
        }
    }
}

/** An in-page subheading ("Ingredients", "Instructions"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.15f).background(MealColors.Accent).padding(top = 2.dp))
    }
}

/** A small accent-tint pill (a category, a badge). */
@Composable
fun Pill(text: String) {
    Surface(color = MealColors.AccentTint, shape = RoundedCornerShape(50)) {
        Text(
            text,
            color = MealColors.AccentHover,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** "Needs your attention" message in the danger tint; a screen reader announces it when it appears or changes. */
@Composable
fun AttentionBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MealColors.DangerTint,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, color = MealColors.DangerHover, modifier = Modifier.padding(12.dp))
    }
}

/**
 * An aisle text field with one-tap chips for the store aisles whose names contain
 * what was typed (the Pi's datalist). An empty field, or an exact aisle, shows none.
 */
@Composable
fun AisleField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String = "Aisle (optional)") {
    val typed = value.trim()
    val matches = if (typed.isEmpty()) {
        emptyList()
    } else {
        GroceryCategories.AISLE_ORDER.filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
    }
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (matches.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
            ) {
                for (aisle in matches) AssistChip(onClick = { onChange(aisle) }, label = { Text(aisle) })
            }
        }
    }
}

/** An aisle's heading in a grouped list, with how many items it holds. */
@Composable
fun AisleHeader(aisle: String, count: Int) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    ) {
        Text(aisle, style = MaterialTheme.typography.titleMedium)
        Text(if (count == 1) "1 item" else "$count items", color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}
