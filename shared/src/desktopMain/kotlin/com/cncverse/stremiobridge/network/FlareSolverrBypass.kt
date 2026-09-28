package com.cncverse.stremiobridge.network

/**
 * FlareSolverr support has been removed.
 * This stub keeps the class visible in the classpath so older plugin JARs
 * that happen to reference it don't cause a NoClassDefFoundError, but
 * all methods are no-ops and solve() always returns null.
 */
object FlareSolverrBypass {
    /** Always empty — FlareSolverr is disabled. */
    var solverrUrl: String = ""

    val isEnabled: Boolean get() = false

    data class SolveResult(
        val cookies: Map<String, String>,
        val userAgent: String,
        val html: String = "",
    )

    /** Always returns null — FlareSolverr support removed. */
    suspend fun solve(targetUrl: String, cookies: Map<String, String> = emptyMap()): SolveResult? = null
}
