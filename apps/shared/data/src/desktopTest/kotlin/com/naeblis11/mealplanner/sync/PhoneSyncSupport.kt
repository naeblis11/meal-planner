package com.naeblis11.mealplanner.sync

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's way to a PC, with no network: a SyncHarness hub answering at the hosts in [hosts], each showing the
 * certificate fingerprint given there. It refuses as SyncHttp would: an unknown host is unreachable; a pin that isn't the
 * host's certificate is WrongPcException; a wrong code is WrongCodeException; once [removed], the key is
 * UnpairedException. It records what it was asked.
 */
internal class HubConnector(private val harness: SyncHarness) : SyncConnector {
    /** "host:port" to the fingerprint of the certificate there; any other host is unreachable. */
    val hosts = ConcurrentHashMap<String, String>()

    @Volatile
    var removed = false

    @Volatile
    var leaveFailure: Exception? = null

    /** Runs once while the next pairing is in flight (before the PC answers), as a slow PC. */
    @Volatile
    var duringNextPair: (suspend () -> Unit)? = null

    /** "host:port pin name" for each pairing; the pin is "unpinned" on the typed path. */
    val pairs = CopyOnWriteArrayList<String>()

    /** The `kind` each pairing asked to be listed as (stage 4a). */
    val kinds = CopyOnWriteArrayList<String>()

    /** "host:port" for each exchange that reached the hub. */
    val exchanges = CopyOnWriteArrayList<String>()

    /** The pin of every transport made. */
    val transportPins = CopyOnWriteArrayList<String>()

    /** "host:port" for each leave. */
    val left = CopyOnWriteArrayList<String>()

    fun answerAt(host: String, port: Int = PORT, fingerprint: String = FP) {
        hosts["$host:$port"] = fingerprint
    }

    private fun certificateAt(host: String, port: Int): String = hosts["$host:$port"] ?: throw SyncHttpException(SyncHttp.UNREACHABLE)

    override suspend fun pair(host: String, port: Int, pin: String?, code: String, deviceId: String, name: String, kind: String): Paired {
        kinds += kind
        duringNextPair?.let { duringNextPair = null; it() }
        val seen = certificateAt(host, port)
        if (pin != null && pin != seen) throw WrongPcException()
        if (code != CODE) throw WrongCodeException()
        removed = false
        pairs += "$host:$port ${pin ?: "unpinned"} $name"
        return Paired(seen, PairReply("proof", KEY, harness.hub.hubId(), HOUSEHOLD, PC_NAME))
    }

    override fun transport(host: String, port: Int, pin: String, deviceKey: String): SyncTransport {
        transportPins += pin
        val hub = harness.transport
        return object : SyncTransport {
            private fun reach() {
                if (certificateAt(host, port) != pin) throw WrongPcException()
                if (removed || deviceKey != KEY) throw UnpairedException()
            }

            override suspend fun exchange(request: ExchangeRequest): ExchangeReply {
                reach()
                exchanges += "$host:$port"
                return hub.exchange(request)
            }

            override suspend fun photo(uuid: String): ByteArray? {
                reach()
                return hub.photo(uuid)
            }

            override suspend fun sendPhoto(uuid: String, bytes: ByteArray) {
                reach()
                hub.sendPhoto(uuid, bytes)
            }
        }
    }

    override suspend fun leave(host: String, port: Int, pin: String, deviceKey: String) {
        leaveFailure?.let { throw it }
        left += "$host:$port"
        removed = true
    }

    companion object {
        const val PORT = 5001
        const val CODE = "123456"
        const val HOUSEHOLD = "0123456789abcdef0123456789abcdef"
        const val PC_NAME = "KITCHEN"
        val FP = "ab".repeat(32)
        val OTHER_FP = "cd".repeat(32)
        val KEY = "12".repeat(32)

        /** The QR code's text for the PC at [addresses]. */
        fun qr(vararg addresses: String): String = PairingQr.encode(addresses.toList(), PORT, CODE, FP)
    }
}

/** mDNS with no multicast: the PCs it "finds" are [found]; it records each time asked. */
internal class FakePcFinder(@Volatile var found: List<FoundPc> = emptyList()) : PcFinder {
    val asked = CopyOnWriteArrayList<Long>()

    override suspend fun find(timeoutMillis: Long): List<FoundPc> {
        asked += timeoutMillis
        return found
    }
}
