package com.cncverse.stremiobridge.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Filters URLs we hand to browsers (extension/repo icons).
 *
 * Some repos publish icons on private LAN addresses (e.g. BDIX extensions:
 * http://10.16.100.244/…, http://172.19.178.180/…). A public page that loads
 * such an image makes Chrome ask the visitor for "local network access", so
 * those URLs are dropped (the UI falls back to the letter avatar).
 *
 * Literal private/loopback IPs and local-only names are rejected at once;
 * other hosts are DNS-checked in the background and cached, because a
 * domain can also point at a private IP.
 */
object PublicUrls {
    private val LOCAL_NAMES = Regex("(^localhost$)|(\\.(local|lan|internal|home|localdomain|intranet|corp)$)", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** host → resolves to a private/loopback address. */
    private val dnsVerdict = ConcurrentHashMap<String, Boolean>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun isPrivate(a: InetAddress): Boolean =
        a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress ||
            a.isMulticastAddress ||
            // CGNAT 100.64/10 and IPv6 unique-local fc00::/7 aren't covered by isSiteLocalAddress
            (a.address.size == 4 && (a.address[0].toInt() and 0xff) == 100 && ((a.address[1].toInt() and 0xff) in 64..127)) ||
            (a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc)

    /** The URL if browsers may load it without touching the visitor's local network, else null. */
    fun safeForBrowser(url: String?): String? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val host = runCatching { java.net.URI(u).host }.getOrNull()?.trim('[', ']')?.lowercase() ?: return null
        if (LOCAL_NAMES.containsMatchIn(host)) return null
        if (IPV4.matches(host) || ':' in host) {
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
            return if (isPrivate(addr)) null else u
        }
        dnsVerdict[host]?.let { private -> return if (private) null else u }
        if (pending.add(host)) {
            scope.launch {
                val private = runCatching { InetAddress.getAllByName(host).any { isPrivate(it) } }.getOrDefault(false)
                dnsVerdict[host] = private
                pending.remove(host)
            }
        }
        return u // unknown yet — allowed until the lookup says otherwise
    }
}
