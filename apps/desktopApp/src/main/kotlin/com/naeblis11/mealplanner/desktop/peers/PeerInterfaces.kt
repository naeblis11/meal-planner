package com.naeblis11.mealplanner.desktop.peers

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

/**
 * One network interface as PeerInterfaces judges it: what java.net.NetworkInterface says, as plain values a test can
 * make. [displayName] is Windows' description of the adapter ("Hyper-V Virtual Ethernet Adapter"), null when unknown.
 */
data class NetInterface(
    val name: String,
    val up: Boolean,
    val loopback: Boolean,
    val virtual: Boolean,
    val pointToPoint: Boolean,
    val addresses: List<InetAddress>,
    val displayName: String? = null,
)

/** Where discovery runs (P6-R5). */
object PeerInterfaces {
    const val MAX_INTERFACES = 4

    /**
     * P6-T4b: adapters that are never the home network, matched case-insensitively against an interface's display name
     * or name. Java's flags miss them: Hyper-V's switches (vEthernet, made by Docker Desktop and WSL) are up at boot
     * with 172.16/12 addresses and isVirtual false. An announce on them alone, before Wi-Fi is up, would work, so
     * PeerWatch's retry would stop and the PC would stay unseen at home all session. VPNs and overlay networks are left
     * out on purpose too: the announcement belongs on the home adapter, not on a tunnel.
     */
    val NOT_HOME = listOf(
        "vEthernet",
        "Hyper-V",
        "WSL",
        "VirtualBox",
        "VMware",
        "Docker",
        "vboxnet",
        "Loopback Pseudo",
        "Npcap",
        "TAP-Windows",
        "Wintun",
        "WireGuard",
        "Tailscale",
        "ZeroTier",
    )

    /**
     * The first site-local IPv4 address (10/8, 172.16/12, 192.168/16) of each interface that is up and is not
     * loopback, virtual, point-to-point (a VPN) or named in NOT_HOME, at most MAX_INTERFACES of them, in the order given.
     */
    fun pick(all: List<NetInterface>): List<Inet4Address> =
        all.asSequence()
            .filter { it.up && !it.loopback && !it.virtual && !it.pointToPoint && !notHome(it) }
            .mapNotNull { nic -> nic.addresses.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress } }
            .distinct()
            .take(MAX_INTERFACES)
            .toList()

    private fun notHome(nic: NetInterface): Boolean =
        NOT_HOME.any { word -> nic.name.contains(word, ignoreCase = true) || nic.displayName?.contains(word, ignoreCase = true) == true }

    /** This PC's interfaces; an interface that can't be read is left out, and none at all is an empty list. Main only. */
    fun system(): List<NetInterface> =
        try {
            Collections.list(NetworkInterface.getNetworkInterfaces() ?: return emptyList()).mapNotNull { nic ->
                try {
                    NetInterface(nic.name, nic.isUp, nic.isLoopback, nic.isVirtual, nic.isPointToPoint, Collections.list(nic.inetAddresses), nic.displayName)
                } catch (e: SocketException) {
                    null
                }
            }
        } catch (e: SocketException) {
            emptyList()
        }
}
