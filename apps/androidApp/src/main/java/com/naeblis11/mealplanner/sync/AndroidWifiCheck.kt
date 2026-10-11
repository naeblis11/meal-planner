package com.naeblis11.mealplanner.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Whether the phone is on a home network now (Join a PC, D2), from the networks' capabilities (ACCESS_NETWORK_STATE):
 * the active network is Wi-Fi or Ethernet, or, when the active network is a VPN, the phone is also on a Wi-Fi or
 * Ethernet network underneath it ([onHomeLan]). Mobile data alone, or no network, is not. False when unsure.
 */
class AndroidWifiCheck(context: Context) {
    private val app = context.applicationContext

    fun onWifi(): Boolean {
        return try {
            val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return false
            val active = connectivity.activeNetwork ?: return false
            onHomeLan(transportsOf(connectivity, active)) {
                @Suppress("DEPRECATION") // allNetworks: the only way to see the networks under a VPN; read only behind one.
                connectivity.allNetworks.filter { it != active }.mapNotNull { transportsOf(connectivity, it) }
            }
        } catch (e: SecurityException) {
            false
        }
    }

    private fun transportsOf(connectivity: ConnectivityManager, network: Network): Transports? =
        connectivity.getNetworkCapabilities(network)?.let { caps ->
            Transports(
                lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            )
        }

    /** One network's transports, as far as Join a PC cares: Wi-Fi or Ethernet ([lan]), and whether it is a VPN. */
    internal data class Transports(val lan: Boolean, val vpn: Boolean)

    internal companion object {
        /**
         * The rule over capabilities: the active network is a LAN one (a VPN that reports its Wi-Fi counts), or it is a VPN
         * and one of the [others] (read only then) is a LAN network that isn't itself a VPN.
         */
        fun onHomeLan(active: Transports?, others: () -> List<Transports>): Boolean = when {
            active == null -> false
            active.lan -> true
            active.vpn -> others().any { it.lan && !it.vpn }
            else -> false
        }
    }
}
