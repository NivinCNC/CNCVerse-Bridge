package com.cncverse.stremiobridge.repo

import android.content.Context

/**
 * Holds the Android application context so platform-specific code in
 * the shared module can access SharedPreferences without depending on
 * the androidApp module's StremioApp class.
 *
 * Must be initialized before any repo operations (called from StremioApp.onCreate).
 */
object AndroidContextHolder {
    lateinit var appContext: Context
}
