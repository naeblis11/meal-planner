package com.naeblis11.mealplanner.desktop.peers

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.AppMetaIds
import com.naeblis11.mealplanner.data.Household
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom

/**
 * This PC as other Meal Planner PCs see it (P6-R3, P6-R4). [instanceId] is 128 random bits kept in app_meta under
 * [INSTANCE_KEY], so a PC can tell its own record when it browses. [household] dates a household made before plan 6 from
 * [databaseFile]'s creation time, the best estimate of when this PC started.
 */
class PeerIdentity(
    private val db: AppDatabase,
    databaseFile: File,
    clock: () -> Long = System::currentTimeMillis,
    private val random: () -> ByteArray = { ByteArray(16).also(SecureRandom()::nextBytes) },
) {
    val household = Household(db, clock = clock, createdFallback = { fileCreated(databaseFile) })

    /** This install's id, made and kept on the first call; one transaction, so two first calls at once agree. */
    suspend fun instanceId(): String = AppMetaIds.keep(db, INSTANCE_KEY, "An instance id", random)

    /** This PC's record (P6-R3) under [name] (PeerTxt.pcName) and app version [app]; the household id itself never leaves. */
    suspend fun record(name: String, app: String): PeerRecord = PeerRecord(
        name = PeerTxt.cleanName(name) ?: PeerTxt.FALLBACK_NAME,
        householdHash = PeerTxt.householdHash(household.id()),
        since = household.created(),
        instanceId = instanceId(),
        app = PeerTxt.cleanApp(app),
    )

    companion object {
        const val INSTANCE_KEY = "peer_instance_id"

        /** When [file] was created, in epoch milliseconds; null when that can't be read (it doesn't exist, say). */
        fun fileCreated(file: File): Long? =
            try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).creationTime().toMillis()
            } catch (e: Exception) {
                null
            }
    }
}
