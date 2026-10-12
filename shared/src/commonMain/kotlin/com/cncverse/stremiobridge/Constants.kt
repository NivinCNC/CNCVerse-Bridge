package com.cncverse.stremiobridge

object Constants {
    /** Platform-specific versions: generated from version.properties (see BuildVersion). */
    const val APP_VERSION = BuildVersion.ANDROID
    const val ANDROID_VERSION = BuildVersion.ANDROID
    const val DESKTOP_VERSION = BuildVersion.DESKTOP

    const val SUPPORT_URL = "https://buymeacoffee.com/nivincnc"
    const val TELEGRAM_URL = "https://t.me/cncverse"
    const val GITHUB_URL = "https://github.com/NivinCNC/CNCVerse-Bridge"

    /** The repository whose releases the in-app update check follows (this fork). */
    const val UPDATE_REPO = "khalsatorrent-droid/CNCVerse-Bridge"
}
