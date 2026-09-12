@file:JvmName("OtaPlatformDesktopKt")
package com.cncverse.stremiobridge.update

import com.cncverse.stremiobridge.state.ServerState
import kotlin.system.exitProcess

actual val isDesktopPlatform: Boolean = true

actual fun getOtaDownloadDir(): String {
    return System.getProperty("java.io.tmpdir")
}

actual fun saveOtaFile(data: ByteArray, fileName: String): String {
    val file = java.io.File(getOtaDownloadDir(), fileName)
    file.writeBytes(data)
    return file.absolutePath
}

/**
 * Desktop OTA: launch the downloaded installer (.msi/.exe/.deb/.dmg) and exit
 * so the installer can replace the running application files.
 */
actual fun installOtaUpdate(filePath: String) {
    val file = java.io.File(filePath)
    if (!file.exists()) {
        ServerState.warn("OTA file not found: $filePath")
        return
    }

    val os = System.getProperty("os.name", "").lowercase()
    try {
        when {
            os.contains("win") -> {
                if (filePath.endsWith(".msi", ignoreCase = true)) {
                    // Launch MSI installer silently, then exit
                    ProcessBuilder("msiexec", "/i", filePath).start()
                } else {
                    // Launch .exe installer
                    ProcessBuilder(filePath).start()
                }
            }
            os.contains("mac") || os.contains("darwin") -> {
                // Open .dmg or .pkg
                ProcessBuilder("open", filePath).start()
            }
            else -> {
                // Linux: .deb — install through the package manager with a GUI
                // privilege prompt (pkexec), falling back to the software store
                // (xdg-open) and finally a plain dpkg hint.
                val launched = runCatching {
                    ProcessBuilder("pkexec", "dpkg", "-i", filePath).start()
                    true
                }.getOrElse { false }
                if (!launched) {
                    runCatching { ProcessBuilder("xdg-open", filePath).start() }
                }
            }
        }
        ServerState.info("OTA installer launched: $filePath — exiting app for update…")
        // Give the installer a moment to start before we exit
        Thread.sleep(1500)
        exitProcess(0)
    } catch (e: Exception) {
        ServerState.warn("Failed to launch OTA installer: ${e.message}")
    }
}
