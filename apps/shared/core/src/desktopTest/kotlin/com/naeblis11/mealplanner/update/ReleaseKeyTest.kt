package com.naeblis11.mealplanner.update

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseKeyTest {
    @Test
    fun theBuiltInKeyIsEmptyOrAP256Key() {
        // Empty until the owner runs tools/release-key.ps1; from then on it must be a key the apps can verify with, or
        // every installed copy would refuse every release.
        if (ReleaseKeyData.PUBLIC_KEY.isBlank()) {
            assertNull(ReleaseKey.builtIn())
        } else {
            assertTrue(ReleaseSignature.isP256(ReleaseKey.builtIn()!!))
        }
    }
}
