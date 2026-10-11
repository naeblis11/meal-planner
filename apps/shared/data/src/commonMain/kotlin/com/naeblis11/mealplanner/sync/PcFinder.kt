package com.naeblis11.mealplanner.sync

/** A Meal Planner PC found on this network: its name and where its sync port answers (an IPv4 literal and the port). */
data class FoundPc(val name: String, val host: String, val port: Int)

/** Looks for Meal Planner PCs on this network by mDNS (LAN sync stage 2c, D2): NsdPcFinder on the phone, FakePcFinder in tests. */
interface PcFinder {
    /** The PCs found within about [timeoutMillis]; empty when there are none, or no network. Never throws. */
    suspend fun find(timeoutMillis: Long): List<FoundPc>
}

/**
 * A PC's mDNS record (`_mealplanner._tcp`, the desktop's PeerTxt) as the phone reads it. The record is untrusted:
 * [foundPc] is null for anything missing or garbled, and never throws. Only a master offering its sync port (the `sync`
 * key) and an IPv4 host are kept: SyncHttp's address check refuses IPv6, and NSD may resolve a link-local one.
 */
object PcRecords {
    /** The PC's service type as NsdManager takes it. */
    const val SERVICE_TYPE = "_mealplanner._tcp"
    const val FALLBACK_NAME = "Meal Planner PC"
    private const val MAX_VALUE_CHARS = 64
    private val PORT = Regex("[0-9]{1,5}")

    /** The first of a resolved service's addresses that is an IPv4 literal: a PC may list a link-local IPv6 one first. */
    fun firstIpv4(addresses: List<String?>): String? = addresses.firstOrNull { it != null && PairingQr.isIpv4(it) }

    fun foundPc(serviceName: String?, host: String?, attributes: Map<String, ByteArray?>): FoundPc? {
        fun value(key: String): String? = attributes[key]?.toString(Charsets.UTF_8)?.takeIf { it.length <= MAX_VALUE_CHARS }
        if (value("v") != "1" || value("role") != "master") return null
        val port = value("sync")?.takeIf { PORT.matches(it) }?.toInt()?.takeIf { it in 1..65535 } ?: return null
        val address = host?.takeIf(PairingQr::isIpv4) ?: return null
        return FoundPc(DeviceNames.clean(serviceName, FALLBACK_NAME), address, port)
    }
}
