package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.google.SecretProtector
import com.naeblis11.mealplanner.folder.AtomicFiles
import com.naeblis11.mealplanner.sync.HandoverBundle
import com.naeblis11.mealplanner.sync.HandoverWire
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A hand-over this PC has taken but not finished (stage 4b, R4): the [bundle] and the key it made for the old main PC. */
class PendingHandover(val bundle: HandoverBundle, val masterKey: String) {
    override fun toString(): String = "PendingHandover($bundle)"
}

/**
 * The pending hand-over (stage 4b, R4), sealed with DPAPI ([protector]) in one file, [file] (`sync-handover.bin` in the
 * app data folder), written atomically before this PC tells the old main PC it is done, and deleted once this PC is the
 * main PC (or the old one refused). A file found at start means a hand-over was cut short: DesktopClient finishes it.
 * A file that can't be opened loads as null. It holds the certificate's password and the Alexa token: never logged.
 */
class HandoverPending(private val file: File, private val protector: SecretProtector) {
    suspend fun load(): PendingHandover? = withContext(Dispatchers.IO) {
        try {
            if (!file.isFile) return@withContext null
            val o = Json.parseToJsonElement(String(protector.unprotect(file.readBytes()), Charsets.UTF_8)).jsonObject
            val key = (o["masterKey"] as JsonPrimitive).content.takeIf { KEY.matches(it) } ?: return@withContext null
            PendingHandover(HandoverWire.decodeBundle((o["bundle"] as JsonPrimitive).content), key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    suspend fun save(pending: PendingHandover) = withContext(Dispatchers.IO) {
        val text = buildJsonObject {
            put("bundle", HandoverWire.encode(pending.bundle))
            put("masterKey", pending.masterKey)
        }.toString()
        AtomicFiles.write(file, protector.protect(text.toByteArray(Charsets.UTF_8)))
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        if (file.exists() && !file.delete() && file.exists()) throw java.io.IOException("The pending hand-over couldn't be removed.")
    }

    companion object {
        const val FILE_NAME = "sync-handover.bin"
        private val KEY = Regex("[0-9a-f]{64}")
    }
}
