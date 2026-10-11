package com.naeblis11.mealplanner.sync

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/** LAN sync stage 2c (D3, D4): one plain sentence for each outcome, chosen by the exception's type, never its text. */
class SyncMessagesTest {
    private fun result(sent: Int, received: Int, skipped: List<String> = emptyList()) = SyncResult(sent, received, skipped, 0, emptyList())

    @Test
    fun aSyncSaysWhatMoved() {
        assertEquals("Sent 3 changes, got 12", SyncMessages.synced(result(3, 12)))
        assertEquals("Sent 1 change, got 1", SyncMessages.synced(result(1, 1)))
        assertEquals("Sent 0 changes, got 0", SyncMessages.synced(result(0, 0)))
    }

    @Test
    fun recordsThatCouldntBeAppliedAreNamed() {
        assertEquals("Sent 2 changes, got 5. 1 couldn't be applied: Soup", SyncMessages.synced(result(2, 5, listOf("Soup"))))
        assertEquals(
            "Sent 2 changes, got 5. 5 couldn't be applied: A, B, C and 2 more",
            SyncMessages.synced(result(2, 5, listOf("A", "B", "C", "D", "E"))),
        )
    }

    @Test
    fun theSentencesAreTheSpecs() {
        assertEquals("That code didn't match. Check it on your PC.", SyncMessages.WRONG_CODE)
        assertEquals("Pairing has closed on your PC. Press Pair a device again.", SyncMessages.PAIRING_CLOSED)
        assertEquals("This PC isn't the household's main PC.", SyncMessages.NOT_MAIN_PC)
        assertEquals("This isn't the PC you scanned.", SyncMessages.WRONG_PC_SCANNED)
        assertEquals("This isn't the PC you paired with. Sync stopped.", SyncMessages.WRONG_PC_SYNCING)
        assertEquals("Couldn't reach your PC \u2014 is it on and are you on the home Wi-Fi?", SyncHttp.UNREACHABLE)
    }

    @Test
    fun pairingFailuresAreSaidPlainly() {
        assertEquals(SyncMessages.WRONG_CODE, SyncMessages.pairingFailed(WrongCodeException(), scanned = false))
        assertEquals(SyncMessages.WRONG_CODE, SyncMessages.pairingFailed(WrongCodeException(), scanned = true))
        assertEquals(SyncMessages.PAIRING_CLOSED, SyncMessages.pairingFailed(NotPairingException(), scanned = true))
        assertEquals(SyncMessages.PAIRING_CLOSED, SyncMessages.pairingFailed(NotPairingException(), scanned = false))
        assertEquals(SyncMessages.NOT_MAIN_PC, SyncMessages.pairingFailed(NotMasterException(), scanned = true))
        assertEquals(SyncMessages.WRONG_PC_SCANNED, SyncMessages.pairingFailed(WrongPcException(), scanned = true))
        assertEquals(SyncMessages.WRONG_PC_TYPED, SyncMessages.pairingFailed(WrongPcException(), scanned = false))
        assertEquals(SyncHttp.UNREACHABLE, SyncMessages.pairingFailed(SyncHttpException(SyncHttp.UNREACHABLE), scanned = true))
        assertEquals(SyncHttp.UNREACHABLE, SyncMessages.pairingFailed(SyncHttpException(SyncHttp.UNREACHABLE), scanned = false))
        assertEquals(SyncMessages.NOT_A_PAIRING_CODE, SyncMessages.pairingFailed(IllegalArgumentException("Bad pairing code: x."), scanned = true))
        assertEquals(SyncMessages.PAIRING_FAILED, SyncMessages.pairingFailed(IllegalStateException("x"), scanned = false))
    }

    @Test
    fun anExceptionsOwnTextIsNeverSaid() {
        // Whatever the PC or the platform put in a message, the phone says its own sentence.
        val odd = SyncHttpException("Your PC answered 500.")
        assertEquals(SyncMessages.PAIRING_FAILED, SyncMessages.pairingFailed(odd, scanned = true))
        assertEquals(SyncMessages.SYNC_FAILED, SyncMessages.syncFailed(odd, "KITCHEN"))
        assertEquals(SyncMessages.SYNC_FAILED, SyncMessages.syncFailed(IOException("Connection reset"), "KITCHEN"))
        assertEquals(SyncMessages.BACKUP_FAILED, SyncMessages.backupFailed(IOException("ENOSPC /storage/emulated/0/x")))
    }

    @Test
    fun syncFailuresAreSaidPlainly() {
        assertEquals("This phone was removed from KITCHEN. Your data is still here.", SyncMessages.syncFailed(UnpairedException(), "KITCHEN"))
        assertEquals("This phone was removed from your PC. Your data is still here.", SyncMessages.syncFailed(UnpairedException(), null))
        assertEquals(SyncHttp.UNREACHABLE, SyncMessages.syncFailed(SyncHttpException(SyncHttp.UNREACHABLE), "KITCHEN"))
        assertEquals("This isn't the PC you paired with. Sync stopped.", SyncMessages.syncFailed(WrongPcException(), "KITCHEN"))
        assertEquals(SyncMessages.NOT_MAIN_PC, SyncMessages.syncFailed(NotMasterException(), "KITCHEN"))
        assertEquals(SyncMessages.NOT_PAIRED, SyncMessages.syncFailed(NotPairedException(), null))
        assertEquals(SyncMessages.SYNC_FAILED, SyncMessages.syncFailed(IllegalStateException("x"), "KITCHEN"))
    }
}
