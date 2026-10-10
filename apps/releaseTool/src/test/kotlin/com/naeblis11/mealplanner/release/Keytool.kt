package com.naeblis11.mealplanner.release

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The JDK's keytool, as tools/release-key.ps1 uses it, but only ever on a throwaway key in a test's temp folder with a
 * test password. The owner's release key is never made, read or touched here.
 */
object Keytool {
    private val exe = File(System.getProperty("java.home"), if (File.separatorChar == '\\') "bin\\keytool.exe" else "bin/keytool")

    /** A PKCS12 file holding one EC key under ReleaseSigner.ALIAS, on [curve]. */
    fun makeStore(dir: File, password: String, curve: String = "secp256r1"): File {
        val store = File(dir, "test-key-$curve.p12")
        run(
            "-genkeypair", "-keystore", store.path, "-storetype", "PKCS12", "-storepass", password, "-keypass", password,
            "-alias", ReleaseSigner.ALIAS, "-keyalg", "EC", "-groupname", curve, "-validity", "2", "-dname", "CN=Meal Planner test",
        )
        return store
    }

    /** The store's certificate (its public half), in PEM, as release-key.ps1 exports it. */
    fun exportCertificate(store: File, password: String, out: File): File {
        run("-exportcert", "-rfc", "-keystore", store.path, "-storetype", "PKCS12", "-storepass", password, "-alias", ReleaseSigner.ALIAS, "-file", out.path)
        return out
    }

    private fun run(vararg args: String) {
        val process = ProcessBuilder(listOf(exe.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed: $output" }
    }
}
