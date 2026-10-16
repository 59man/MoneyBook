package com.moneybook.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnCheckTest {
    private val tailscale = NetView(vpn = true, addresses = listOf("100.101.102.103", "fd7a:115c:a1e0::1"), routesToTarget = true)

    @Test fun tailscaleLikeNetworkIsAllowed() {
        assertTrue(tailnetCarries(listOf(NetView(false, listOf("192.168.0.9"), true), tailscale)))
    }

    @Test fun vpnWithoutA100AddressIsRefused() {
        assertFalse(tailnetCarries(listOf(NetView(true, listOf("10.2.0.2", "100.63.255.255", "100.128.0.1"), true))))
    }

    @Test fun vpnWithoutARouteToTheTargetIsRefused() {
        assertFalse(tailnetCarries(listOf(tailscale.copy(routesToTarget = false))))
    }

    @Test fun noVpnIsRefused() {
        assertFalse(tailnetCarries(emptyList()))
        assertFalse(tailnetCarries(listOf(tailscale.copy(vpn = false))))
    }

    @Test fun theAddressAndTheRouteMustBelongToTheSameNetwork() {
        val commercial = NetView(true, listOf("10.2.0.2"), true)
        val tsNoRoute = tailscale.copy(routesToTarget = false)
        assertFalse(tailnetCarries(listOf(commercial, tsNoRoute)))
    }
}
