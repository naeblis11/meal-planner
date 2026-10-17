package com.naeblis11.mealplanner.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naeblis11.mealplanner.ui.theme.MealColors
import kotlinx.coroutines.flow.StateFlow

const val JOIN_OFFER_JOIN = "Join"
const val KEEP_SEPARATE = "Keep this PC separate"

/** Settings' key: Keep this PC separate was pressed (1), and the offer never comes back. */
const val JOIN_OFFER_DISMISSED_KEY = "join_household_dismissed"

fun joinOffer(pcName: String): String = "Found $pcName's Meal Planner on this network."

/**
 * Stage 4a, R8: on a PC's first run, the household's main PC it found on the network, offered once. [offer] is the main
 * PC's name, or null when there is nothing to offer (not a first run, nothing found, joined, or put away).
 */
interface JoinOfferControls {
    val offer: StateFlow<String?>

    /** Keep this PC separate: the offer goes for good. */
    fun keepSeparate()
}

/**
 * The offer above every screen, friendly (UpdateNotice's accent tint, never the red "needs attention"): Join opens
 * Settings, where Join a household is; Keep this PC separate puts it away for good. Nothing joins by itself.
 */
@Composable
fun JoinOfferBanner(controls: JoinOfferControls, onJoin: () -> Unit, modifier: Modifier = Modifier) {
    val pcName by controls.offer.collectAsStateWithLifecycle()
    val name = pcName ?: return
    Surface(color = MealColors.AccentTint, shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Text(
                joinOffer(name),
                color = MealColors.AccentHover,
                modifier = Modifier.padding(top = 8.dp, end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onJoin, modifier = Modifier.heightIn(min = 48.dp)) { Text(JOIN_OFFER_JOIN) }
                TextButton(onClick = controls::keepSeparate, modifier = Modifier.heightIn(min = 48.dp)) { Text(KEEP_SEPARATE) }
            }
        }
    }
}
