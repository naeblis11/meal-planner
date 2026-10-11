package com.naeblis11.mealplanner.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naeblis11.mealplanner.ui.theme.MealColors

const val JOIN_HINT = "Have a Meal Planner PC? Join it in Settings."
const val JOIN_HINT_ACTION = "Join"
const val DISMISS_HINT = "Dismiss"

/** AppContainer.settings: the hint was put away (1), never shown again. */
const val JOIN_HINT_DISMISSED_KEY = "join_hint_dismissed"

/**
 * The phone's first-run hint (LAN sync stage 2c, D3), above the recipe list: friendly, in UpdateNotice's accent tint
 * (never the red "needs attention"). Join opens Settings; x puts it away for good.
 */
@Composable
fun JoinHint(onJoin: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MealColors.AccentTint, shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                JOIN_HINT,
                color = MealColors.AccentHover,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onJoin, modifier = Modifier.heightIn(min = 48.dp)) { Text(JOIN_HINT_ACTION) }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics { contentDescription = DISMISS_HINT },
            ) { Text("\u00D7", fontSize = 20.sp, color = MealColors.AccentHover) }
        }
    }
}
