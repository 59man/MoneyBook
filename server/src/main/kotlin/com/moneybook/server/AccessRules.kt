package com.moneybook.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.net.InetAddress

/** An IP literal (v4 or v6, optional `%scope`) or null. Never resolves a name: only literals reach `getByName`. */
fun ipOrNull(raw: String): InetAddress? {
    val s = raw.substringBefore('%')
    if (':' in s) {
        if (!s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
    } else {
        val p = s.split('.')
        if (p.size != 4 || p.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) || it.toInt() > 255 }) return null
    }
    return runCatching { InetAddress.getByName(s) }.getOrNull() // ::ffff:a.b.c.d comes back as an Inet4Address
}

class Cidr private constructor(private val net: ByteArray, private val bits: Int) {
    fun contains(addr: InetAddress): Boolean {
        val a = addr.address
        if (a.size != net.size) return false
        var left = bits
        var i = 0
        while (left > 0) {
            val mask = if (left >= 8) 0xFF else (0xFF shl (8 - left)) and 0xFF
            if ((a[i].toInt() and mask) != (net[i].toInt() and mask)) return false
            left -= 8; i++
        }
        return true
    }

    companion object {
        fun parse(s: String): Cidr {
            val ip = ipOrNull(s.substringBefore('/'))
            val bits = s.substringAfter('/', "").toIntOrNull()
            require(ip != null && bits != null && '/' in s && bits in 0..(ip.address.size * 8)) { "bad network" }
            return Cidr(ip.address, bits)
        }
        fun parseList(s: String): List<Cidr> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::parse)
        val DEFAULTS: List<Cidr> = parseList("100.64.0.0/10,127.0.0.0/8,::1/128")
    }
}

/** Who may talk to the server. Pure: [check] returns the error code to answer with (403), or null to serve. */
class AccessRules(private val trusted: List<Cidr>, private val allowedHosts: Set<String>) {
    fun check(peer: String?, hostHeader: String?, method: String, path: String, origin: String?, secFetchSite: String?): String? {
        if (path == "/health") return null
        val ip = peer?.let(::ipOrNull)
        if (ip == null || trusted.none { it.contains(ip) }) return "forbidden_network"
        val host = hostHeader?.let(::hostName)
        if (host == null || (ipOrNull(host) == null && host != "localhost" && host !in allowedHosts)) return "forbidden_host"
        if (method == "POST" && path.startsWith("/admin/")) {
            val ok = if (origin != null) origin.equals("http://$hostHeader", ignoreCase = true) else secFetchSite == "same-origin"
            if (!ok) return "forbidden_origin"
        }
        return null
    }

    private fun hostName(h: String): String? {
        val name = if (h.startsWith("[")) h.substring(1).substringBefore(']') else h.substringBefore(':')
        return name.lowercase().takeIf { it.isNotEmpty() }
    }
}

/** First thing on every call: answers 403 before routing, auth or body parsing. The peer is the TCP peer, never a header. */
fun Application.installAccessRules(rules: AccessRules, peer: (ApplicationCall) -> String) {
    install(createApplicationPlugin("AccessRules") {
        onCall { call ->
            val r = call.request
            val code = rules.check(
                peer(call), r.headers[HttpHeaders.Host], r.httpMethod.value, r.path(),
                r.headers[HttpHeaders.Origin], r.headers["Sec-Fetch-Site"],
            )
            if (code != null) call.respond(HttpStatusCode.Forbidden, errorBody(code))
        }
    })
}
