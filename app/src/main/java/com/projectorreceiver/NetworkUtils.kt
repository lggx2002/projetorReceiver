package com.projectorreceiver

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun localIpv4Address(): String? {
        val addresses = mutableListOf<String>()
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val interfaceAddresses = networkInterface.inetAddresses
                while (interfaceAddresses.hasMoreElements()) {
                    val address = interfaceAddresses.nextElement()
                    val hostAddress = address.hostAddress ?: continue
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        if (!address.isLinkLocalAddress) return hostAddress
                        addresses += hostAddress
                    }
                }
            }
            addresses.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
