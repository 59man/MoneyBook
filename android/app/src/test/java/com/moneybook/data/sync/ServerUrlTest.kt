package com.moneybook.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    private fun check(expected: Boolean, debug: Boolean, vararg urls: String) =
        urls.forEach { assertEquals("$it debug=$debug", expected, ServerUrl.isAllowed(it, debug)) }

    @Test fun releaseAllowsHttpsAndTailscaleHttp() = check(
        true, false,
        "https://host.ts.net", "https://example.com:8443", "https://100.101.102.103",
        "http://100.101.102.103:8095", "http://100.64.0.1", "http://100.127.255.254:8095",
    )

    @Test fun releaseRefusesOtherHttpAndGarbage() = check(
        false, false,
        "http://100.63.255.255", "http://100.128.0.1", "http://10.1.2.3:8095", "http://10.0.2.2:8095",
        "http://localhost:8095", "http://127.0.0.1:8095", "http://host.ts.net", "http://100.101.102.103.evil.com",
        "http://100.64.0.1@evil.com", "http://999.1.1.1", "ftp://100.64.0.1", "100.64.0.1:8095", "", "https://",
    )

    @Test fun leadingZeroOctetsAreRefused() { // bionic's inet_aton reads 100.064.0.1 as octal 100.52.0.1, a public address
        check(false, false, "http://100.064.0.1", "http://100.0100.0.1", "http://100.64.00.1", "http://100.64.0.01")
        assertFalse(ServerUrl.isTailscale("100.064.0.1"))
        assertTrue(ServerUrl.isTailscale("100.64.0.0"))
    }

    @Test fun onlyPlainHttpToNonDebugHostsNeedsAVpn() {
        listOf("http://100.101.102.103:8095", "http://100.64.0.1").forEach { assertTrue(it, ServerUrl.needsVpn(it)) }
        listOf("https://100.101.102.103", "https://host.ts.net", "http://10.0.2.2:8095", "http://localhost:1", "http://127.0.0.1:9").forEach { assertFalse(it, ServerUrl.needsVpn(it)) }
    }

    @Test fun debugAddsTheEmulatorHosts() {
        check(true, true, "http://10.0.2.2:8095", "http://localhost:8095", "http://127.0.0.1:1234")
        check(false, true, "http://10.1.2.3:8095", "http://10.0.2.3")
    }
}
