package com.cncverse.stremiobridge.ui

import com.lagradost.cloudstream3.network.CloudflareKiller

/**
 * Desktop actual — delegates to CloudflareKiller companion state.
 * Note: CF bypass is now plain-HTTP only (no solver toggle needed),
 * so [enabled] is always true and [tlsBoundDomainCount] is always 0.
 */
actual object CloudflareSolverState {
    actual val isSupported: Boolean = true

    /** No solver to toggle — plain HTTP is always active. */
    actual var enabled: Boolean
        get() = true
        set(_) {}   // no-op

    actual val clearedDomainCount: Int
        get() = CloudflareKiller.savedCookies.size

    /** TLS-bound tracking removed — always 0. */
    actual val tlsBoundDomainCount: Int
        get() = 0

    actual fun clearAll() {
        CloudflareKiller.clearAllClearance()
    }
}
