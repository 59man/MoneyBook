package com.moneybook.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlTest {
    private fun check(expected: Boolean, debug: Boolean, vararg urls: String) =
        urls.forEach { assertEquals("$it debug=$debug", expected, ServerUrl.isAllowed(it, debug)) }

    @Test fun releaseAllowsHttpsAndTailscaleHttp() = check(
        true, false,
        "https://host.ts.net", "https://example.com:8443", "https://100.103.137.108",
        "http://100.103.137.108:8095", "http://100.64.0.1", "http://100.127.255.254:8095",
    )

    @Test fun releaseRefusesOtherHttpAndGarbage() = check(
        false, false,
        "http://100.63.255.255", "http://100.128.0.1", "http://192.168.0.103:8095", "http://10.0.2.2:8095",
        "http://localhost:8095", "http://127.0.0.1:8095", "http://host.ts.net", "http://100.103.137.108.evil.com",
        "http://100.64.0.1@evil.com", "http://999.1.1.1", "ftp://100.64.0.1", "100.64.0.1:8095", "", "https://",
    )

    @Test fun debugAddsTheEmulatorHosts() {
        check(true, true, "http://10.0.2.2:8095", "http://localhost:8095", "http://127.0.0.1:1234")
        check(false, true, "http://192.168.0.103:8095", "http://10.0.2.3")
    }
}
