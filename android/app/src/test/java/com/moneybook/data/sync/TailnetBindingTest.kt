package com.moneybook.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import androidx.test.core.app.ApplicationProvider
import com.moneybook.data.DeviceSettings
import com.moneybook.shared.sync.LoginRequest
import com.moneybook.testutil.FakeDeviceSettingsRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

/** The platform glue of the plain-http gate: which network is chosen, and that the request is opened over it. */
@RunWith(RobolectricTestRunner::class)
class TailnetBindingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val target = "100.101.102.103"

    private fun addNetwork(id: Int, vpn: Boolean, address: String, routeTo: String?): Network {
        val net = ShadowNetwork.newInstance(id)
        val nc = ShadowNetworkCapabilities.newInstance()
        shadowOf(nc).addTransportType(if (vpn) NetworkCapabilities.TRANSPORT_VPN else NetworkCapabilities.TRANSPORT_CELLULAR)
        val lp = LinkProperties()
        // hidden constructors and methods: not in the compile-time android.jar
        val la = ReflectionHelpers.callConstructor(LinkAddress::class.java, ClassParameter.from(InetAddress::class.java, InetAddress.getByName(address)), ClassParameter.from(Int::class.javaPrimitiveType, 32))
        ReflectionHelpers.callInstanceMethod<Boolean>(lp, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, la))
        if (routeTo != null) {
            val route = ReflectionHelpers.callConstructor(
                RouteInfo::class.java, ClassParameter.from(IpPrefix::class.java, IpPrefix(InetAddress.getByName(routeTo), 32)),
                ClassParameter.from(InetAddress::class.java, null), ClassParameter.from(String::class.java, null),
            )
            ReflectionHelpers.callInstanceMethod<Boolean>(lp, "addRoute", ClassParameter.from(RouteInfo::class.java, route))
        }
        shadowOf(cm).addNetwork(net, ShadowNetworkInfo.newInstance(null, ConnectivityManager.TYPE_VPN, 0, true, NetworkInfo.State.CONNECTED))
        shadowOf(cm).setNetworkCapabilities(net, nc)
        shadowOf(cm).setLinkProperties(net, lp)
        return net
    }

    @Test fun systemCheckReturnsTheVpnNetworkThatCoversTheTarget() {
        addNetwork(1, vpn = false, address = "10.1.2.3", routeTo = null)
        addNetwork(2, vpn = true, address = "10.2.0.2", routeTo = target) // a commercial VPN: no 100.64/10 address
        val tailnet = addNetwork(3, vpn = true, address = "100.64.0.9", routeTo = target)
        assertEquals(tailnet, SystemVpnCheck(context).tailnetFor(target))
        assertNull(SystemVpnCheck(context).tailnetFor("100.101.102.104")) // no route to this target
        assertNull(SystemVpnCheck(context).tailnetFor("10.1.2.3")) // not a Tailscale literal
    }

    @Test fun plainHttpToTailscaleIsOpenedOverTheReturnedNetwork() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/auth/login") { ex ->
                val b = """{"accessToken":"A1","refreshToken":"R1","expiresIn":900}""".toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.write(b); ex.close()
            }
            start()
        }
        try {
            val net = ShadowNetwork.newInstance(7)
            var boundTo: Network? = null
            var openedUrl: URL? = null
            val api = HttpSyncApi(
                FakeDeviceSettingsRepository(DeviceSettings()),
                openOver = { n, u -> boundTo = n; openedUrl = u; URL("http://127.0.0.1:${server.address.port}${u.path}").openConnection() },
                vpn = { net },
            )
            val url = "http://$target:8095"
            api.login(url, LoginRequest("k", "dev", "Pixel"))
            assertSame(net, boundTo)
            assertEquals("$url/v1/auth/login", openedUrl.toString())
        } finally {
            server.stop(0)
        }
    }
}
