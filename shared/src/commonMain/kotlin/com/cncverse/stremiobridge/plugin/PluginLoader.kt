package com.cncverse.stremiobridge.plugin

import com.cncverse.stremiobridge.model.SitePlugin
import com.cncverse.stremiobridge.state.LoadedPluginInfo
import java.io.File

/**
 * Abstract contract for loading CS3 plugins on a given platform.
 *
 * - Android: uses [PathClassLoader] to load DEX files natively.
 *   Constructor requires android.content.Context.
 * - Desktop JVM: uses [dex2jar] to convert .cs3 → .jar, then [URLClassLoader].
 *   Default no-arg constructor.
 *
 * NOTE: The expect class has different constructors per platform.
 * Android: PluginLoader(context: Context)
 * Desktop: PluginLoader()
 */
expect class PluginLoader {
    /**
     * Loads all plugins from their cached .cs3 files.
     * Returns a list of [LoadedPluginInfo].
     */
    suspend fun loadPlugins(
        plugins: List<SitePlugin>,
        cs3Files: Map<String, File>,
    ): List<LoadedPluginInfo>

    /** Returns all registered MainAPI instances (as [Any] to avoid platform deps). */
    fun getRegisteredApis(): List<Any>

    /** Opens the settings UI for a plugin, if supported (Android only). */
    fun openPluginSettings(internalName: String, activityContext: Any? = null)

    /** Unloads all plugins and clears state. */
    fun unloadAll()
}

