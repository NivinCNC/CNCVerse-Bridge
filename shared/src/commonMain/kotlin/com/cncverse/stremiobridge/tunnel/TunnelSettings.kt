package com.cncverse.stremiobridge.tunnel

import com.cncverse.stremiobridge.repo.getExtensionSetting
import com.cncverse.stremiobridge.repo.setExtensionSetting

/**
 * The user's own Cloudflare Tunnel: a tunnel token from the Cloudflare
 * dashboard (Zero Trust → Networks → Tunnels) and the public hostname routed
 * to this bridge. Stored in the shared settings map (same store as extension
 * settings), so both desktop and Android keep it across restarts.
 */
object TunnelSettings {
    private const val KEY_TOKEN = "KEY_CF_TUNNEL_TOKEN"
    private const val KEY_HOSTNAME = "KEY_CF_TUNNEL_HOSTNAME"

    /** The tunnel token (`eyJ…`), or null when not set up. */
    val token: String?
        get() = getExtensionSetting(KEY_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }

    /** The public hostname (e.g. `bridge.example.com`), without scheme or path. */
    val hostname: String?
        get() = getExtensionSetting(KEY_HOSTNAME)?.let(::normalizeHostname)?.takeIf { it.isNotEmpty() }

    val isConfigured: Boolean get() = token != null && hostname != null

    /** `https://<hostname>`, or null when not set up. */
    val publicUrl: String? get() = hostname?.let { "https://$it" }

    fun save(token: String, hostname: String) {
        setExtensionSetting(KEY_TOKEN, token.trim())
        setExtensionSetting(KEY_HOSTNAME, normalizeHostname(hostname))
    }

    /** Accepts `https://bridge.example.com/` as well as the bare host. */
    fun normalizeHostname(input: String): String =
        input.trim()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/')
            .trim()
            .lowercase()
}
