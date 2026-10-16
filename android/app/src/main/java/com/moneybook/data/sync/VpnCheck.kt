package com.moneybook.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress

/**
 * The tailnet network that carries a request to [host] (a Tailscale address), or null. Plain http is sent only over the
 * returned network (`Network.openConnection`), so if the VPN does not cover this app, or drops before the connect, the
 * connection fails instead of leaving through the carrier.
 */
fun interface VpnCheck {
    fun tailnetFor(host: String): Network?
}

/** What the decision needs to know about one network: [addresses] are its own IPs, [routesToTarget] whether a route covers the target. [id] is the platform handle. */
data class NetView(val vpn: Boolean, val addresses: List<String>, val routesToTarget: Boolean, val id: Network? = null)

/**
 * A Tailscale-like VPN has a 100.64.0.0/10 address of its own (carrier-grade NAT, so the carrier could carry the
 * traffic in cleartext otherwise; commercial VPNs use other ranges) and a route that covers the target.
 */
fun tailnetCarrier(nets: List<NetView>): NetView? =
    nets.firstOrNull { n -> n.vpn && n.routesToTarget && n.addresses.any(ServerUrl::isTailscale) }

class SystemVpnCheck(context: Context) : VpnCheck {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION") // allNetworks: a VPN need not be the default network
    override fun tailnetFor(host: String): Network? = try {
        if (cm == null || !ServerUrl.isTailscale(host)) null // a literal only: no DNS lookup here
        else {
            val target = InetAddress.getByName(host)
            tailnetCarrier(cm.allNetworks.map { net ->
                val vpn = cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                val lp = if (vpn) cm.getLinkProperties(net) else null
                NetView(
                    vpn,
                    lp?.linkAddresses?.mapNotNull { it.address.hostAddress } ?: emptyList(),
                    lp?.routes?.any { it.matches(target) } == true,
                    net,
                )
            })?.id
        }
    } catch (_: Exception) {
        null // SecurityException and anything else: fail closed
    }
}
