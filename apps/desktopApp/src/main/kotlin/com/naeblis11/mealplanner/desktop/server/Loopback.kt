package com.naeblis11.mealplanner.desktop.server

import java.net.InetAddress

/**
 * True when [address] (a connection's remote address, as text) is this PC: 127.x.x.x or ::1 (P4-R2). Only an IP
 * literal can pass, so this never asks DNS; a host name, a scoped address or nothing at all is refused.
 */
internal fun isLoopback(address: String?): Boolean {
    val text = address?.trim()?.removePrefix("/") ?: return false
    val octets = text.split('.')
    if (octets.size == 4 && octets.all { part -> part.length in 1..3 && part.all { it in '0'..'9' } && part.toInt() <= 255 }) {
        return octets[0].toInt() == 127
    }
    if (':' !in text || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return false
    return try {
        // In brackets it is an IPv6 literal or an error, never a name to look up.
        InetAddress.getByName("[$text]").isLoopbackAddress
    } catch (e: Exception) {
        false
    }
}
