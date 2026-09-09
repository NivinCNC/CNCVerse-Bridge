package com.cncverse.stremiobridge.ui

/**
 * Android actual — no-op (Android uses the native WebView-based CloudflareKiller
 * from the cloudstream-api.jar; the settings card is hidden on Android).
 */
actual object CloudflareSolverState {
    actual val isSupported: Boolean = false
    actual var enabled: Boolean = false
    actual val clearedDomainCount: Int get() = 0
    actual val tlsBoundDomainCount: Int get() = 0
    actual fun clearAll() {}
}
