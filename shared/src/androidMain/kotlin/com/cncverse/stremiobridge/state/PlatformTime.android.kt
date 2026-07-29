package com.cncverse.stremiobridge.state

import android.util.Log
import com.cncverse.stremiobridge.state.LogLevel

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformLog(level: LogLevel, message: String) {
    when (level) {
        LogLevel.INFO  -> Log.i("CNCVerse", message)
        LogLevel.WARN  -> Log.w("CNCVerse", message)
        LogLevel.ERROR -> Log.e("CNCVerse", message)
    }
}
