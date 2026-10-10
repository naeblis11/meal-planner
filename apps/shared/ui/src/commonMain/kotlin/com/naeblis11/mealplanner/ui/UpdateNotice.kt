package com.naeblis11.mealplanner.ui

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
import com.naeblis11.mealplanner.settings.INSTALL_UPDATE
import com.naeblis11.mealplanner.settings.SEE_UPDATE
import com.naeblis11.mealplanner.settings.UPDATE_NOT_NOW
import com.naeblis11.mealplanner.settings.readyToInstall
import com.naeblis11.mealplanner.settings.updateAvailable
import com.naeblis11.mealplanner.ui.theme.MealColors
import com.naeblis11.mealplanner.update.UpdateControls

/**
 * The small launch notice (spec "Updates"): an update the launch check found, above every screen, until See update
 * (Settings, where Install is; it puts the notice away) or Not now. A download that finished while the app was away
 * shows again with Install. It never installs anything. Nothing shows for one found from Settings.
 */
@Composable
fun UpdateNotice(updates: UpdateControls, onOpen: () -> Unit) {
    val status by updates.state.collectAsStateWithLifecycle()
    val offer = status.offer
    if (!status.showsNotice || offer == null) return
    Surface(
        color = MealColors.AccentTint,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                if (status.waitingToInstall) readyToInstall(offer.version, status.installClosesApp) else updateAvailable(offer.version),
                color = MealColors.AccentHover,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = updates::dismissNotice, modifier = Modifier.heightIn(min = 48.dp)) { Text(UPDATE_NOT_NOW) }
                // P8-PF11: a download that finished while the app was away offers Install, which uses the verified
                // file at once; where Install closes the app (the PC) it opens Settings instead, which asks first.
                val installsHere = status.waitingToInstall && !status.installClosesApp
                // P8-PF4: opening Settings puts the notice away.
                val openSettings = {
                    updates.dismissNotice()
                    onOpen()
                }
                TextButton(onClick = if (installsHere) updates::install else openSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (status.waitingToInstall) INSTALL_UPDATE else SEE_UPDATE)
                }
            }
        }
    }
}
