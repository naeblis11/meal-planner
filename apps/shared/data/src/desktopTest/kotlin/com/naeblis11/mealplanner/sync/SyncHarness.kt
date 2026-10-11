package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.folder.AtomicFiles
import com.naeblis11.mealplanner.folder.FolderFileStore
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/** Two devices in one test: the master PC (with its hub) and a phone, talking through SyncWire as the network would. */
internal class SyncHarness(
    private val phoneClock: () -> Long = System::currentTimeMillis,
    hubClock: () -> Long = System::currentTimeMillis,
    /** Gives both devices a recipe folder, as a PC has (the phone's stands for a client PC's). */
    withFiles: Boolean = false,
) : Closeable {
    class Device(root: File, name: String, withFiles: Boolean = false) {
        val dir = File(root, name).apply { mkdirs() }
        val images = File(dir, "images").apply { mkdirs() }
        val db = AppDatabase.openAt(File(dir, "mp.db"))

        /** Recipe files written (with [withFiles]). */
        var fileWrites = 0
        private val folder = File(dir, "recipes").apply { mkdirs() }
        val recipes = RecipeRepository(
            db, images,
            files = if (!withFiles) null else FolderFileStore(folder, moveToTrash = null, writer = { f, b -> fileWrites++; AtomicFiles.write(f, b) }),
        )
    }

    private val root: File = Files.createTempDirectory("mp-sync-e2e").toFile()
    val pc = Device(root, "pc", withFiles)
    val phone = Device(root, "phone", withFiles)
    private val extra = mutableListOf<Device>()

    /** The hub the phone talks to; a test may swap in another (a rebuilt PC). */
    var hub = SyncHub(pc.db, pc.recipes, pc.images, hubClock)

    /** The last reply the phone got, as it arrived. */
    var lastReply: ExchangeReply? = null

    /** Photos the phone has sent to the hub. */
    var photosSent = 0

    /** Another device in the same temp folder, closed with the harness. */
    fun newDevice(name: String): Device = Device(root, name).also { extra += it }

    /** Fails the next exchange after the hub has applied it, as a dropped connection would. */
    var dropNextReply = false

    /** Fails the next photo fetch from the hub, or the next photo sent to it, as a dropped connection would. */
    var failNextPhoto = false
    var failNextSendPhoto = false

    /** Runs once while the next exchange is in flight (after the request was built), as an edit made meanwhile. */
    var duringNextExchange: (suspend () -> Unit)? = null

    /** [device]'s connection to the hub. */
    fun transportFor(device: Device): SyncTransport = object : SyncTransport {
        override suspend fun exchange(request: ExchangeRequest): ExchangeReply {
            duringNextExchange?.let { duringNextExchange = null; it() }
            val reply = hub.exchange(SyncSchema.deviceId(device.db), SyncWire.decodeRequest(SyncWire.encode(request)))
            if (dropNextReply) { dropNextReply = false; throw java.io.IOException("Connection reset") }
            return SyncWire.decodeReply(SyncWire.encode(reply)).also { lastReply = it }
        }
        override suspend fun photo(uuid: String): ByteArray? {
            if (failNextPhoto) { failNextPhoto = false; throw java.io.IOException("Connection reset") }
            return hub.photo(uuid)
        }
        override suspend fun sendPhoto(uuid: String, bytes: ByteArray) {
            if (failNextSendPhoto) { failNextSendPhoto = false; throw java.io.IOException("Connection reset") }
            photosSent++
            hub.receivePhoto(uuid, bytes)
        }
    }

    val transport = transportFor(phone)

    val client = SyncClient(phone.db, phone.recipes, phone.images, transport, phoneClock)

    /** A client for another device ([newDevice]), on the phone's clock. */
    fun clientFor(device: Device) = SyncClient(device.db, device.recipes, device.images, transportFor(device), phoneClock)

    @Suppress("UNCHECKED_CAST")
    fun doc(name: String, uuid: String, image: String? = null): YamlMap = RecipeYaml.load(
        "recipe_name: $name\nrecipe_uuid: $uuid\n" + (image?.let { "image: $it\n" } ?: "") +
            "ingredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n",
    ) as YamlMap

    override fun close() {
        pc.db.close()
        phone.db.close()
        extra.forEach { it.db.close() }
        root.deleteRecursively()
    }
}
