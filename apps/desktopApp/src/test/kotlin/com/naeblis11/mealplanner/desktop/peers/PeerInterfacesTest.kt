package com.naeblis11.mealplanner.desktop.peers

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

/** P6-R5: the interfaces announced and browsed on, chosen from a list; no network is read. */
class PeerInterfacesTest {
    private fun v4(a: Int, b: Int, c: Int, d: Int) =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())) as Inet4Address

    private val v6: InetAddress = InetAddress.getByAddress(ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 })

    private fun nic(
        name: String,
        vararg addresses: InetAddress,
        up: Boolean = true,
        loopback: Boolean = false,
        virtual: Boolean = false,
        pointToPoint: Boolean = false,
        displayName: String? = null,
    ) = NetInterface(name, up, loopback, virtual, pointToPoint, addresses.toList(), displayName)

    @Test
    fun onlyUpRealInterfacesWithAHomeIpv4AddressAreUsed() {
        val all = listOf(
            nic("lo", v4(127, 0, 0, 1), loopback = true),
            nic("down", v4(192, 168, 1, 9), up = false),
            nic("sub", v4(192, 168, 1, 8), virtual = true),
            nic("vpn", v4(10, 8, 0, 2), pointToPoint = true),
            nic("public", v4(8, 8, 8, 8)),
            nic("linklocal", v4(169, 254, 3, 4)),
            nic("v6only", v6),
            nic("wifi", v6, v4(192, 168, 1, 20)),
            nic("wired", v4(10, 0, 0, 5)),
            nic("lab", v4(172, 16, 4, 2)),
        )
        assertEquals(listOf(v4(192, 168, 1, 20), v4(10, 0, 0, 5), v4(172, 16, 4, 2)), PeerInterfaces.pick(all))
    }

    @Test
    fun atMostFourAreUsed() {
        val all = (1..6).map { nic("eth$it", v4(192, 168, it, 2)) }
        assertEquals((1..4).map { v4(192, 168, it, 2) }, PeerInterfaces.pick(all))
    }

    // P6-T4b: Hyper-V's switches (Docker Desktop, WSL) are up at boot with 172.16/12 addresses and isVirtual false;
    // announcing on them alone before Wi-Fi is up would leave the PC unseen at home all session.
    @Test
    fun hyperVSwitchesAloneAreNotUsed() {
        val all = listOf(
            nic("eth7", v4(172, 20, 160, 1), displayName = "vEthernet (Default Switch)"),
            nic("eth8", v4(172, 29, 0, 1), displayName = "vEthernet (WSL)"),
            nic("eth9", v4(172, 24, 0, 1), displayName = "Hyper-V Virtual Ethernet Adapter #2"),
            nic("VETHERNET-x", v4(172, 25, 0, 1)),
        )
        assertEquals(emptyList<Inet4Address>(), PeerInterfaces.pick(all))
    }

    @Test
    fun wifiAndEthernetAreKept() {
        val all = listOf(
            nic("wlan0", v4(192, 168, 1, 20), displayName = "Wi-Fi"),
            nic("eth0", v4(192, 168, 1, 21), displayName = "Ethernet"),
            nic("eth1", v4(10, 0, 0, 5), displayName = "Intel(R) Wi-Fi 6 AX201 160MHz"),
        )
        assertEquals(listOf(v4(192, 168, 1, 20), v4(192, 168, 1, 21), v4(10, 0, 0, 5)), PeerInterfaces.pick(all))
    }

    @Test
    fun vpnsAndOtherVirtualAdaptersAreNotUsedWhateverTheirFlagsSay() {
        val dropped = listOf(
            "Tailscale Tunnel",
            "WireGuard Tunnel: home",
            "VirtualBox Host-Only Ethernet Adapter",
            "VMware Virtual Ethernet Adapter for VMnet8",
            "Docker NAT",
            "vboxnet0",
            "Microsoft Loopback Pseudo-Interface 1",
            "Npcap Loopback Adapter",
            "TAP-Windows Adapter V9",
            "Wintun Userspace Tunnel",
            "ZeroTier One Virtual Port",
            "wsl bridge",
        )
        val all = dropped.mapIndexed { n, label -> nic("eth$n", v4(10, 9, n, 2), displayName = label) } +
            nic("wlan0", v4(192, 168, 1, 20), displayName = "Wi-Fi")
        assertEquals(listOf(v4(192, 168, 1, 20)), PeerInterfaces.pick(all))
        // By name too, where there is no display name.
        val byName = listOf(nic("tailscale0", v4(10, 64, 0, 1)), nic("wireguard1", v4(10, 7, 0, 2)))
        assertEquals(emptyList<Inet4Address>(), PeerInterfaces.pick(byName))
    }

    @Test
    fun noneMeansNone() {
        assertEquals(emptyList<Inet4Address>(), PeerInterfaces.pick(emptyList()))
    }
}
