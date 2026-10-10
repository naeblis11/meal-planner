package com.naeblis11.mealplanner.release

import com.naeblis11.mealplanner.update.ReleaseSignature
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.util.Base64

/**
 * The apps' built-in public key, ReleaseKeyData.kt (shared/core), written from the release key's certificate. The owner
 * runs this once, through tools/release-key.ps1, and commits the file. It never replaces a key the apps already trust
 * unless told to ([write]'s replace): installed copies would ignore every release signed with the new one.
 */
object PublicKeyFile {
    /** From apps/. */
    const val PATH = "shared/core/src/commonMain/kotlin/com/naeblis11/mealplanner/update/ReleaseKeyData.kt"

    private val VALUE = Regex("""const val PUBLIC_KEY: String = "([A-Za-z0-9+/=]*)"""")

    /** The whole file for [base64] (empty before the key is made); PublicKeyFileTest pins the committed one to it. */
    fun render(base64: String): String = listOf(
        "package com.naeblis11.mealplanner.update",
        "",
        "// Written by tools/release-key.ps1 (releaseTool public-key); don't edit it by hand. The public half of the owner's",
        "// release key: an update is shown only when latest.json's signature verifies against it (docs/RELEASING.md).",
        "// While it is empty, no copy of the app checks for updates.",
        "object ReleaseKeyData {",
        "    const val PUBLIC_KEY: String = \"$base64\"",
        "}",
    ).joinToString("\n", postfix = "\n")

    /** The key [target] holds now ("" before it is made), or null when it has no PUBLIC_KEY line. */
    fun current(target: File): String? = target.takeIf { it.isFile }?.readText()?.let { VALUE.find(it)?.groupValues?.get(1) }

    fun write(certificate: File, target: File, replace: Boolean) {
        if (!certificate.isFile) throw ReleaseToolException("There is no certificate at $certificate.")
        val key = try {
            certificate.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }.publicKey
        } catch (e: CertificateException) {
            throw ReleaseToolException("$certificate isn't an X.509 certificate.")
        }
        if (!ReleaseSignature.isP256(key)) throw ReleaseToolException("The release key must be EC P-256, which the apps verify.")
        val base64 = Base64.getEncoder().encodeToString(key.encoded)
        val trusted = current(target)
        if (!trusted.isNullOrEmpty() && trusted != base64 && !replace) {
            throw ReleaseToolException(
                "The apps already trust another release key. Installed copies would ignore every release signed with a new one, " +
                    "so restore the backed-up key instead (docs/RELEASING.md). Pass --replace only if that key is lost for good.",
            )
        }
        target.writeText(render(base64), Charsets.UTF_8)
    }
}
