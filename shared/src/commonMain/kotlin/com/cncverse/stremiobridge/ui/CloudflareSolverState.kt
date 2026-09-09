package com.cncverse.stremiobridge.ui

/**
 * Platform bridge for the Cloudflare Solver settings card.
 *
 * On desktop: delegates to CloudflareKiller (the actual CDP bypass).
 * On Android: no-op (Android uses the native WebView-based CloudflareKiller).
 */
expect object CloudflareSolverState {
    /** Whether the desktop CF solver is enabled. */
    var enabled: Boolean

    /** Number of domains that currently have active clearance cookies. */
    val clearedDomainCount: Int

    /** Number of domains using the browser as a TLS fetch proxy. */
    val tlsBoundDomainCount: Int

    /** Clear all stored clearance cookies and proxy sessions. */
    fun clearAll()
}
