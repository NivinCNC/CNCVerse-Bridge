package com.cncverse.stremiobridge.tunnel

import com.cncverse.stremiobridge.server.PlatformPaths
import java.io.File

actual fun getCloudflaredBinaryPath(): String {
    val os = System.getProperty("os.name", "").lowercase()
    val fileName = if (os.contains("win")) "cloudflared.exe" else "cloudflared"

    return File(PlatformPaths.configDir, fileName).absolutePath
}

actual fun setFileExecutable(filePath: String) {
    val file = File(filePath)
    if (file.exists()) {
        file.setExecutable(true, false)
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("win")) {
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", filePath)).waitFor()
            } catch (_: Exception) {}
        }
    }
}

actual fun getPlatformCloudflaredDownloadUrl(): String {
    val os = System.getProperty("os.name", "").lowercase()
    val arch = System.getProperty("os.arch", "").lowercase()
    
    return when {
        os.contains("win") -> {
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
        }
        os.contains("mac") || os.contains("darwin") -> {
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-darwin-arm64"
            } else {
                "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-darwin-amd64"
            }
        }
        else -> { // Linux
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
            } else if (arch.contains("arm")) {
                "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm"
            } else {
                "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"
            }
        }
    }
}

actual fun resolveCloudflareEdgeIps(): List<String> {
    // Desktop systems have /etc/resolv.conf and handle DNS resolution correctly
    return emptyList()
}
