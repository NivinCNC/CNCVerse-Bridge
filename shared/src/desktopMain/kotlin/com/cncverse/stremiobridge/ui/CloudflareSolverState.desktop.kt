package com.cncverse.stremiobridge.ui

import com.lagradost.cloudstream3.network.CloudflareKiller

/**
 * Desktop actual — delegates directly to CloudflareKiller companion state.
 */
actual object CloudflareSolverState {
    actual val isSupported: Boolean = true

    actual var enabled: Boolean
        get() = CloudflareKiller.cfBypassEnabled
        set(value) { CloudflareKiller.cfBypassEnabled = value }

    actual val clearedDomainCount: Int
        get() = CloudflareKiller.savedCookies.size

    actual val tlsBoundDomainCount: Int
        get() = CloudflareKiller.tlsBoundHosts.size

    actual fun clearAll() {
        CloudflareKiller.clearAllClearance()
    }
}
