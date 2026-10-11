package com.naeblis11.mealplanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naeblis11.mealplanner.settings.NEVER_SYNCED
import com.naeblis11.mealplanner.ui.theme.MealColors

/**
 * The phone's Sync button's view model (LAN sync stage 2c), for the four main screens' top bars, the first-run hint and
 * the sync dialogs; null on the desktop and in screen tests. MealPlannerApp provides it.
 */
val LocalSyncControls = staticCompositionLocalOf<SyncViewModel?> { null }

const val SYNC_ACTION_TAG = "sync-action"
const val SYNC = "Sync"
const val SYNCING = "Syncing"

/** Two arrows chasing round a circle: the Sync button's mark (drawn here; the app bundles no icon set). */
val SyncIcon: ImageVector by lazy {
    ImageVector.Builder("Sync", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M4.5 12a7.5 7.5 0 0 1 12.8-5.3L19.5 9M19.5 4.5V9H15M19.5 12a7.5 7.5 0 0 1-12.8 5.3L4.5 15M4.5 19.5V15H9"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()
}

/** "Never", "Just now", "5 min ago", "3 h ago", "Yesterday", "4 days ago": the line under the Sync button. */
fun syncedAgo(at: Long?, now: Long): String {
    if (at == null) return "Never"
    val minutes = (now - at).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 48 * 60 -> "Yesterday"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/** The full sentence, the button's content description: "Last synced 5 min ago", or "Never synced". */
fun lastSyncedLabel(at: Long?, now: Long): String =
    if (at == null) NEVER_SYNCED else "Last synced " + syncedAgo(at, now).replaceFirstChar { it.lowercase() }

/**
 * The Sync button for a main screen's top bar (D4). Nothing shows unless this phone is paired (LocalSyncControls). The
 * time is read again on every resume and every sync, with no ticking clock.
 */
@Composable
fun SyncAction() {
    val vm = LocalSyncControls.current ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.status.known || !state.status.paired) return
    var resumedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumedAt = System.currentTimeMillis() }
    val changedAt = remember(state.status.lastSyncAt, state.syncing) { System.currentTimeMillis() }
    SyncButton(state.status.lastSyncAt, state.syncing, maxOf(resumedAt, changedAt), vm::tap)
}

/**
 * One 48 dp touch target: the sync mark (a spinner while syncing, when it can't be tapped) over a short "5 min ago".
 * Its content description is the full "Last synced 5 min ago", and its click label is "Sync".
 */
@Composable
fun SyncButton(lastSyncAt: Long?, syncing: Boolean, now: Long, onSync: () -> Unit) {
    val label = lastSyncedLabel(lastSyncAt, now)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .testTag(SYNC_ACTION_TAG)
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !syncing, onClickLabel = SYNC, role = Role.Button, onClick = onSync)
            .semantics {
                contentDescription = label
                if (syncing) stateDescription = SYNCING
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (syncing) {
                CircularProgressIndicator(Modifier.size(20.dp), color = MealColors.Accent, strokeWidth = 2.dp)
            } else {
                Icon(SyncIcon, contentDescription = null, tint = MealColors.Accent)
            }
        }
        Text(syncedAgo(lastSyncAt, now), style = MaterialTheme.typography.labelSmall, color = MealColors.Muted, maxLines = 1)
    }
}
