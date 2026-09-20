package com.cncverse.stremiobridge.state

import com.cncverse.stremiobridge.state.LogLevel
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

private val logFile: File by lazy {
    File(System.getProperty("user.home"), ".cncverse_bridge/app.log")
}

/**
 * Desktop log sink: mirrors every ServerState entry into
 * ~/.cncverse_bridge/app.log so issues are diagnosable when the packaged exe
 * runs without a console.
 */
actual fun platformLog(level: LogLevel, message: String) {
    println("[${level.name}] $message")
    runCatching {
        if (logFile.exists() && logFile.length() > 50 * 1024 * 1024L) {
            logFile.writeText("") // Truncate completely when reaching 50MB
        }
        logFile.parentFile?.mkdirs()
        PrintWriter(FileOutputStream(logFile, true)).use { writer ->
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            writer.println("$ts [${level.name}] $message")
        }
    }
}
