package com.plantpilot.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

object WifiUtils {
    fun getCurrentSsid(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        
        // In newer Android versions, SSID is often "<unknown ssid>" without Location permissions
        // But for display purposes, we try our best
        val ssid = info.ssid.trim('"')
        
        if (ssid == "<unknown ssid>" || ssid == "0x") {
            // Try ConnectivityManager as a fallback
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return "Connected WiFi"
                }
            }
            return "Unknown WiFi"
        }
        
        return ssid
    }
}
