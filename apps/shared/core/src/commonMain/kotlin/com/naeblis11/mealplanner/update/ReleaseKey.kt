package com.naeblis11.mealplanner.update

import java.security.PublicKey

/** The key built into this copy of the app (ReleaseKeyData), which every latest.json must be signed with. */
object ReleaseKey {
    /**
     * Null in a build made before the owner made the release key: that copy never checks. Throws
     * IllegalArgumentException for a key that isn't EC P-256, which ReleaseKeyTest keeps out of every build.
     */
    fun builtIn(): PublicKey? = ReleaseKeyData.PUBLIC_KEY.takeIf { it.isNotBlank() }?.let(ReleaseSignature::publicKey)
}
