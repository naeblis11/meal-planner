package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.update.UpdateControls
import com.naeblis11.mealplanner.update.UpdateStatus
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The update check without GitHub: the status it is told, and what it was asked. */
class FakeUpdateControls(initial: UpdateStatus = UpdateStatus(offered = true)) : UpdateControls {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<UpdateStatus> = flow
    val calls = CopyOnWriteArrayList<String>()

    override fun checkNow() {
        calls += "check"
    }

    override fun setAutomatic(on: Boolean) {
        calls += "automatic $on"
        flow.update { it.copy(automatic = on) }
    }

    override fun install() {
        calls += "install"
    }

    override fun openInstallPermission() {
        calls += "permission"
    }

    override fun dismissNotice() {
        calls += "dismiss"
        flow.update { it.copy(noticeDismissed = true) }
    }
}
