package com.cncverse.stremiobridge.server

import java.io.File

/**
 * Platform directory conventions for the JVM desktop/server editions.
 *
 * Overridable via environment variables so servers/Docker can keep everything
 * under a single persistent path:
 *   CNC_CONFIG_DIR — settings, repo list, cloudflared binary   (default ~/.cncverse)
 *   CNC_CACHE_DIR  — downloaded .cs3 plugin cache             (default ~/.cncverse_bridge)
 */
object PlatformPaths {

    val configDir: File
        get() {
            val env = System.getenv("CNC_CONFIG_DIR")?.takeIf { it.isNotBlank() }
            return if (env != null) File(env).apply { mkdirs() } else legacyDir(".cncverse")
        }

    val cacheDir: File
        get() {
            val env = System.getenv("CNC_CACHE_DIR")?.takeIf { it.isNotBlank() }
            return if (env != null) File(env).apply { mkdirs() } else legacyDir(".cncverse_bridge")
        }

    private fun legacyDir(name: String): File =
        File(System.getProperty("user.home", "."), name).apply { mkdirs() }

    fun fileInConfigDir(fileName: String): File = File(configDir, fileName)
}
