package com.naeblis11.mealplanner.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/** LAN sync stage 2c (D2, D3): Join a PC asks for the home Wi-Fi first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidWifiCheckTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private fun capabilities(vararg transports: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { caps -> transports.forEach { shadowOf(caps).addTransportType(it) } }

    private fun activeNetworkWith(vararg transports: Int) {
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities(*transports))
    }

    // Another network the phone is on besides the active one (a VPN's underlying Wi-Fi). Robolectric's own default
    // networks (one of them Wi-Fi) are made mobile data first, so the test's network is the only one that counts.
    private fun alsoOn(netId: Int, vararg transports: Int) {
        @Suppress("DEPRECATION")
        connectivity.allNetworks.filter { it != connectivity.activeNetwork }.forEach {
            shadowOf(connectivity).setNetworkCapabilities(it, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        }
        val network = ShadowNetwork.newInstance(netId)
        val info = ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, android.net.NetworkInfo.State.CONNECTED,
        )
        shadowOf(connectivity).addNetwork(network, info)
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities(*transports))
    }

    @Test
    fun onWifiItSaysSo() {
        activeNetworkWith(NetworkCapabilities.TRANSPORT_WIFI)
        assertTrue(AndroidWifiCheck(context).onWifi())
    }

    @Test
    fun onMobileDataItIsNotWifi() {
        activeNetworkWith(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertFalse(AndroidWifiCheck(context).onWifi())
    }

    @Test
    fun onEthernetItCountsAsTheHomeNetwork() {
        activeNetworkWith(NetworkCapabilities.TRANSPORT_ETHERNET)
        assertTrue(AndroidWifiCheck(context).onWifi())
    }

    @Test
    fun behindAVpnTheWifiUnderneathCounts() {
        activeNetworkWith(NetworkCapabilities.TRANSPORT_VPN)
        alsoOn(4242, NetworkCapabilities.TRANSPORT_WIFI)
        assertTrue(AndroidWifiCheck(context).onWifi())
    }

    @Test
    fun behindAVpnOnMobileDataItIsNotWifi() {
        activeNetworkWith(NetworkCapabilities.TRANSPORT_VPN)
        alsoOn(4243, NetworkCapabilities.TRANSPORT_CELLULAR)
        assertFalse(AndroidWifiCheck(context).onWifi())
    }

    @Test
    fun theRuleOverCapabilities() {
        val wifi = AndroidWifiCheck.Transports(lan = true, vpn = false)
        val cellular = AndroidWifiCheck.Transports(lan = false, vpn = false)
        val vpn = AndroidWifiCheck.Transports(lan = false, vpn = true)
        val vpnOverWifi = AndroidWifiCheck.Transports(lan = true, vpn = true)
        assertTrue(AndroidWifiCheck.onHomeLan(wifi) { error("not looked at") })
        assertTrue(AndroidWifiCheck.onHomeLan(vpnOverWifi) { error("not looked at") })
        assertFalse(AndroidWifiCheck.onHomeLan(cellular) { error("not looked at") })
        assertFalse(AndroidWifiCheck.onHomeLan(null) { listOf(wifi) })
        assertTrue(AndroidWifiCheck.onHomeLan(vpn) { listOf(vpn, cellular, wifi) })
        assertFalse(AndroidWifiCheck.onHomeLan(vpn) { listOf(vpn, vpnOverWifi, cellular) })
    }

    @Test
    fun withNoNetworkItIsNotWifi() {
        shadowOf(connectivity).setActiveNetworkInfo(null)
        assertFalse(AndroidWifiCheck(context).onWifi())
    }
}
