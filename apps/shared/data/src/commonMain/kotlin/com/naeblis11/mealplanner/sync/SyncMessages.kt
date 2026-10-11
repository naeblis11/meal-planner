package com.naeblis11.mealplanner.sync

/**
 * What the phone says about pairing and syncing (LAN sync stage 2c): one plain sentence each, chosen by the exception's
 * type (and, for SyncHttp's unreachable PC, its known constant), never by its text, so nothing a PC or the platform put in
 * a message (a key, a code, a reply, a path) is ever shown.
 */
object SyncMessages {
    const val WRONG_CODE = "That code didn't match. Check it on your PC."
    const val PAIRING_CLOSED = "Pairing has closed on your PC. Press Pair a device again."
    const val NOT_MAIN_PC = "This PC isn't the household's main PC."
    const val WRONG_PC_SCANNED = "This isn't the PC you scanned."
    const val WRONG_PC_TYPED = "That PC couldn't prove the code, so this phone didn't pair with it. Scan the code instead."
    const val WRONG_PC_SYNCING = "This isn't the PC you paired with. Sync stopped."
    const val NOT_A_PAIRING_CODE = "That isn't a Meal Planner pairing code. Scan the code in your PC's Settings > Phones and PCs."
    const val NOT_PAIRED = "This phone isn't paired with a PC. Join one in Settings."
    const val NO_PC_FOUND = "Couldn't find your PC on this Wi-Fi."
    const val PAIRING_FAILED = "Couldn't pair. Try again in a moment."
    const val LEAVE_FAILED = "Couldn't forget the PC. Try again in a moment."
    const val SYNC_FAILED = "Sync didn't finish. Try again in a moment."
    const val BACKUP_FAILED = "The backup couldn't be saved, so nothing was replaced."
    const val REMOVE_COPY_FAILED = "Couldn't remove this phone's copy. Try again in a moment."
    const val REPLACE_REFUSED = "Another sync or pairing is running. Nothing was replaced."
    private const val SHOWN_SKIPPED = 3

    /** The spec's sentence for a phone the PC removed, with the PC's name. */
    fun removedFrom(pcName: String?): String = "This phone was removed from ${pcName ?: "your PC"}. Your data is still here."

    /** "Sent 3 changes, got 12", then the records that couldn't be applied, by name (up to three). */
    fun synced(result: SyncResult): String {
        val moved = "Sent ${changes(result.sent)}, got ${result.received}"
        if (result.skipped.isEmpty()) return moved
        val more = result.skipped.size - SHOWN_SKIPPED
        val names = result.skipped.take(SHOWN_SKIPPED).joinToString(", ") + if (more > 0) " and $more more" else ""
        return "$moved. ${result.skipped.size} couldn't be applied: $names"
    }

    /** A failed pairing; [scanned] is true for the QR code, false for the typed code. */
    fun pairingFailed(e: Exception, scanned: Boolean): String = when {
        e is WrongCodeException -> WRONG_CODE
        e is NotPairingException -> PAIRING_CLOSED
        e is NotMasterException -> NOT_MAIN_PC
        e is WrongPcException -> if (scanned) WRONG_PC_SCANNED else WRONG_PC_TYPED
        unreachable(e) -> SyncHttp.UNREACHABLE
        e is IllegalArgumentException && scanned -> NOT_A_PAIRING_CODE
        else -> PAIRING_FAILED
    }

    /** A failed sync; [pcName] is the PC the phone was paired with when it started. */
    fun syncFailed(e: Exception, pcName: String?): String = when {
        e is UnpairedException -> removedFrom(pcName)
        e is WrongPcException -> WRONG_PC_SYNCING
        e is NotMasterException -> NOT_MAIN_PC
        e is NotPairedException -> NOT_PAIRED
        unreachable(e) -> SyncHttp.UNREACHABLE
        else -> SYNC_FAILED
    }

    /** Replace's backup failed: nothing was replaced. The reason isn't shown (it may name a path). */
    @Suppress("UNUSED_PARAMETER")
    fun backupFailed(e: Exception): String = BACKUP_FAILED

    // SyncHttp's own unreachable sentence: matched against the constant, so only that constant is ever said.
    private fun unreachable(e: Exception): Boolean = e is SyncHttpException && e.message == SyncHttp.UNREACHABLE

    private fun changes(n: Int): String = if (n == 1) "1 change" else "$n changes"
}
