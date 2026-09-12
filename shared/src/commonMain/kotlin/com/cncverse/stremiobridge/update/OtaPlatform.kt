package com.cncverse.stremiobridge.update

expect fun getOtaDownloadDir(): String
expect fun saveOtaFile(data: ByteArray, fileName: String): String
expect fun installOtaUpdate(filePath: String)

/** True on desktop JVM, false on Android. */
expect val isDesktopPlatform: Boolean

/**
 * File extension to look for in release assets — platform aware:
 * .msi on Windows, .deb on Linux, .dmg on macOS, .apk on Android.
 */
val otaAssetExtension: String
    get() {
        if (!isDesktopPlatform) return ".apk"
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("win") -> ".msi"
            os.contains("mac") || os.contains("darwin") -> ".dmg"
            else -> ".deb"
        }
    }
