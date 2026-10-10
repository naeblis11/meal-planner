package com.naeblis11.mealplanner.data

/**
 * A random id made once and kept in app_meta: the household's (Household) and this PC's (the desktop's PeerIdentity).
 * 128 random bits as 32 lower-case hex digits.
 */
object AppMetaIds {
    /**
     * The id kept under [key], made from [random]'s 16 bytes on the first call; one transaction, so two first calls at
     * once agree. [onMade] runs inside that transaction, only on the call that made the id. [what] names the id in the
     * error for bytes of the wrong size.
     */
    suspend fun keep(
        db: AppDatabase,
        key: String,
        what: String,
        random: () -> ByteArray,
        onMade: suspend (AppMetaDao) -> Unit = {},
    ): String {
        val meta = db.appMetaDao()
        meta.get(key)?.let { return it }
        return db.inTransaction {
            meta.get(key) ?: hex(random(), what).also {
                meta.put(AppMetaEntity(key, it))
                onMade(meta)
            }
        }
    }

    private fun hex(bytes: ByteArray, what: String): String {
        require(bytes.size == 16) { "$what is 16 random bytes." }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
