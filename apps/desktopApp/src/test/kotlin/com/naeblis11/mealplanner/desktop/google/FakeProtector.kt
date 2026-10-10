package com.naeblis11.mealplanner.desktop.google

/** DPAPI's stand-in for tests: a marker and the bytes scrambled, so a test never touches the user's real keys. */
class FakeProtector : SecretProtector {
    override fun protect(plain: ByteArray): ByteArray = MARK + plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()

    override fun unprotect(sealed: ByteArray): ByteArray {
        require(sealed.size >= MARK.size && sealed.copyOfRange(0, MARK.size).contentEquals(MARK)) { "not sealed here" }
        return sealed.copyOfRange(MARK.size, sealed.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    private companion object {
        val MARK = "fake-dpapi:".toByteArray(Charsets.US_ASCII)
    }
}
