package com.moneybook.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether the active network is a VPN (Tailscale on Android is one). Plain http is sent only while it is. */
fun interface VpnCheck {
    fun active(): Boolean
}

/** 100.64.0.0/10 is also carrier-grade NAT, so with Tailscale off the traffic would leave over the carrier in cleartext. */
class SystemVpnCheck(context: Context) : VpnCheck {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun active(): Boolean =
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
}
