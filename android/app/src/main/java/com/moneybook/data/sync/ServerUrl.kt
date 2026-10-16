package com.moneybook.data.sync

import java.net.URI

/** Where the app may send the API key: https anywhere, plain http only to a Tailscale address (100.64.0.0/10). */
object ServerUrl {
    private val debugHosts = setOf("10.0.2.2", "localhost", "127.0.0.1")

    fun isAllowed(url: String, debug: Boolean): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty() || uri.userInfo != null) return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> isTailscale(host) || (debug && host in debugHosts)
            else -> false
        }
    }

    /** True for an IPv4 literal in 100.64.0.0/10 (100.64.0.0 – 100.127.255.255). */
    private fun isTailscale(host: String): Boolean {
        val p = host.split('.')
        if (p.size != 4 || p.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return false
        val n = p.map { it.toInt() }
        return n.all { it in 0..255 } && n[0] == 100 && n[1] in 64..127
    }
}
