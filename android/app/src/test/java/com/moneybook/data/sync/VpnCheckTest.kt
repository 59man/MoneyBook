package com.moneybook.data.sync

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VpnCheckTest {
    private val tailscale = NetView(vpn = true, addresses = listOf("100.101.102.103", "fd7a:115c:a1e0::1"), routesToTarget = true)

    @Test fun tailscaleLikeNetworkIsAllowed() {
        assertSame(tailscale, tailnetCarrier(listOf(NetView(false, listOf("192.168.0.9"), true), tailscale)))
    }

    @Test fun vpnWithoutA100AddressIsRefused() {
        assertNull(tailnetCarrier(listOf(NetView(true, listOf("10.2.0.2", "100.63.255.255", "100.128.0.1"), true))))
    }

    @Test fun vpnWithoutARouteToTheTargetIsRefused() {
        assertNull(tailnetCarrier(listOf(tailscale.copy(routesToTarget = false))))
    }

    @Test fun noVpnIsRefused() {
        assertNull(tailnetCarrier(emptyList()))
        assertNull(tailnetCarrier(listOf(tailscale.copy(vpn = false))))
    }

    @Test fun theAddressAndTheRouteMustBelongToTheSameNetwork() {
        val commercial = NetView(true, listOf("10.2.0.2"), true)
        val tsNoRoute = tailscale.copy(routesToTarget = false)
        assertNull(tailnetCarrier(listOf(commercial, tsNoRoute)))
    }
}
