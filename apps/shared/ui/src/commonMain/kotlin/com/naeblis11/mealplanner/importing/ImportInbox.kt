package com.naeblis11.mealplanner.importing

import com.naeblis11.mealplanner.backup.ImportBundle
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Above every screen while a recipe from the extension waits behind an edit or another review. */
const val CHROME_RECIPE_WAITING = "A recipe from Chrome is waiting."

/** An import read outside the screens (the Chrome extension): [bundle]'s photos are files in [dir], which the import owns. */
data class PreparedImport(val bundle: ImportBundle, val dir: File)

/**
 * Imports waiting for the review screen (P4-R4). The desktop server offers them; the app takes the next whenever no
 * other import is being read, reviewed or summed up, so one sent during a review waits its turn rather than replacing
 * it. At most [capacity] wait; a staging folder left here when the app quits is cleared at the next start
 * (AppContainer.startupCleanup).
 */
class ImportInbox(private val capacity: Int = CAPACITY) {
    private val lock = Any()
    private val queue = ArrayDeque<PreparedImport>()
    private val _waiting = MutableStateFlow(0)

    /** How many imports wait. */
    val waiting: StateFlow<Int> = _waiting.asStateFlow()

    /** False, and nothing kept, when [capacity] imports already wait. */
    fun offer(prepared: PreparedImport): Boolean = synchronized(lock) {
        if (queue.size >= capacity) return false
        queue.addLast(prepared)
        _waiting.value = queue.size
        true
    }

    /** The oldest waiting import, now the caller's; null when none waits. */
    fun take(): PreparedImport? = synchronized(lock) {
        val next = queue.removeFirstOrNull()
        _waiting.value = queue.size
        next
    }

    companion object {
        const val CAPACITY = 20
    }
}
