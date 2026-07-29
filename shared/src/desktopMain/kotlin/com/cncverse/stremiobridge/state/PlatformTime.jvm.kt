package com.cncverse.stremiobridge.state

import com.cncverse.stremiobridge.state.LogLevel

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformLog(level: LogLevel, message: String) {
    println("[${level.name}] $message")
}
