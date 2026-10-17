package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.google.SecretProtector
import com.naeblis11.mealplanner.folder.AtomicFiles
import com.naeblis11.mealplanner.sync.PairingStore
import com.naeblis11.mealplanner.sync.PhonePairing
import com.naeblis11.mealplanner.sync.PhonePairingCodec
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A client PC's pairing with its master (LAN sync stage 4a): the phone's [PhonePairing], sealed with DPAPI for the signed-in
 * Windows user ([protector], as the Google token and the sync certificate's password are) in one file, [file]
 * (`sync-client.bin` in the app data folder), written atomically. It holds the master's addresses, port, certificate
 * fingerprint and name, this PC's device key, and the hub and household ids.
 *
 * Whether this file holds a pairing is what makes the PC a client (stage 4a, R3). A file that is missing, damaged or
 * sealed by another Windows user loads as null: the PC is then simply not a client, keeps its data, and the file is left
 * where it is (a later Join replaces it). Nothing here logs the pairing, the key or the fingerprint.
 */
class DpapiPairingStore(
    private val file: File,
    private val protector: SecretProtector,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PairingStore {
    override suspend fun load(): PhonePairing? = withContext(io) {
        try {
            if (!file.isFile) return@withContext null
            PhonePairingCodec.decode(protector.unprotect(file.readBytes()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun save(pairing: PhonePairing) = withContext(io) {
        AtomicFiles.write(file, protector.protect(PhonePairingCodec.encode(pairing)))
    }

    override suspend fun clear() = withContext(io) {
        if (file.exists() && !file.delete() && file.exists()) throw java.io.IOException("The client pairing couldn't be removed.")
    }

    companion object {
        /** The file's name in the app data folder. */
        const val FILE_NAME = "sync-client.bin"
    }
}
