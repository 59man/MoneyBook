package com.moneybook.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress

/** Whether a request to [host] (a Tailscale address) would travel through the tailnet. Plain http is sent only if so. */
fun interface VpnCheck {
    fun routesThroughTailnet(host: String): Boolean
}

/** What the decision needs to know about one network: [addresses] are its own IPs, [routesToTarget] whether a route covers the target. */
data class NetView(val vpn: Boolean, val addresses: List<String>, val routesToTarget: Boolean)

/**
 * A Tailscale-like VPN has a 100.64.0.0/10 address of its own (carrier-grade NAT, so the carrier could carry the
 * traffic in cleartext otherwise; commercial VPNs use other ranges) and a route that covers the target.
 */
fun tailnetCarries(nets: List<NetView>): Boolean =
    nets.any { n -> n.vpn && n.routesToTarget && n.addresses.any(ServerUrl::isTailscale) }

class SystemVpnCheck(context: Context) : VpnCheck {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION") // allNetworks: a VPN need not be the default network
    override fun routesThroughTailnet(host: String): Boolean = try {
        if (cm == null || !ServerUrl.isTailscale(host)) false // a literal only: no DNS lookup here
        else {
            val target = InetAddress.getByName(host)
            tailnetCarries(cm.allNetworks.map { net ->
                val vpn = cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                val lp = if (vpn) cm.getLinkProperties(net) else null
                NetView(
                    vpn,
                    lp?.linkAddresses?.mapNotNull { it.address.hostAddress } ?: emptyList(),
                    lp?.routes?.any { it.matches(target) } == true,
                )
            })
        }
    } catch (_: Exception) {
        false // SecurityException and anything else: fail closed
    }
}
