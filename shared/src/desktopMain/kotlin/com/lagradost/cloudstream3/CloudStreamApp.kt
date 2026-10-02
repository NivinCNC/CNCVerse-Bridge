package com.lagradost.cloudstream3

import android.content.Context
import com.cncverse.stremiobridge.plugin.PluginCallContext
import com.cncverse.stremiobridge.repo.loadExtensionSettings
import com.cncverse.stremiobridge.repo.saveExtensionSettings
import com.cncverse.stremiobridge.settings.PluginSettingsSchemaRegistry

class CloudStreamApp {
    companion object {
        @PublishedApi
        internal var inMemorySettings: MutableMap<String, String>? = null

        /**
         * Returns the settings map wrapped in a discovery hook: every read by
         * PLUGIN code (inline getKey is compiled into plugin bytecode and reads
         * this map directly) registers the key in the settings schema so the
         * desktop gear dialog can render it.
         */
        @PublishedApi
        internal fun getSettings(): MutableMap<String, String> {
            inMemorySettings?.let { return it }
            synchronized(this) {
                return inMemorySettings ?: SettingsHookMap().also { map ->
                    map.putAll(loadExtensionSettings())
                    inMemorySettings = map
                }
            }
        }

        // Concurrent: plugins on many threads read/write it at once. A plain
        // LinkedHashMap silently dropped entries under concurrent puts, and the
        // next save then persisted the loss (plugin settings vanishing).
        private class SettingsHookMap : java.util.concurrent.ConcurrentHashMap<String, String>() {
            override fun get(key: String): String? {
                if (!PluginCallContext.suppressSettingsHook.get()) {
                    val caller = PluginCallContext.getCallingPluginName()
                    if (caller != null) {
                        PluginSettingsSchemaRegistry.register(caller, key, "String", null, storageKey = key)
                    }
                }
                return super.get(key)
            }
        }

        /**
         * Attributes a DataStore access to the calling plugin and registers the
         * key in the settings schema so the desktop settings dialog can show it.
         */
        @PublishedApi
        internal fun registerSchemaKey(path: String, value: Any?) {
            val pluginName = PluginCallContext.getCallingPluginName() ?: return
            val type = when (value) {
                is Boolean -> "Boolean"
                is Int -> "Int"
                is Long -> "Long"
                is Float -> "Float"
                is Set<*> -> "StringSet"
                else -> "String"
            }
            // The plugin's path is used verbatim as the settings-map key (no
            // pref prefix), so the dialog must read/write exactly this key
            PluginSettingsSchemaRegistry.register(pluginName, path, type, value, storageKey = path)
        }

        inline fun <reified T> getKey(path: String): T? {
            registerSchemaKey(path, null)
            val strValue = getSettings()[path] ?: return null
            return when (T::class) {
                String::class -> strValue as T
                Int::class -> strValue.toIntOrNull() as? T
                Boolean::class -> strValue.toBooleanStrictOrNull() as? T
                Float::class -> strValue.toFloatOrNull() as? T
                Long::class -> strValue.toLongOrNull() as? T
                Double::class -> strValue.toDoubleOrNull() as? T
                else -> null
            }
        }

        fun setKey(path: String, value: Any?) {
            registerSchemaKey(path, value)
            val settings = getSettings()
            if (value == null) {
                settings.remove(path)
            } else {
                settings[path] = value.toString()
            }
            scheduleSave()
        }

        private val saveExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ext-settings-writer").apply { isDaemon = true }
        }
        private val savePending = java.util.concurrent.atomic.AtomicBoolean(false)

        init {
            Runtime.getRuntime().addShutdownHook(Thread { flushSettings() })
        }

        /**
         * Coalesces bursts of setKey calls (cookies, channel caches, a dialog
         * clearing + re-adding 40 keys) into one write of the settings file
         * a second later, instead of rewriting the whole file on every call.
         */
        private fun scheduleSave() {
            if (savePending.compareAndSet(false, true)) {
                saveExecutor.schedule({ flushSettings() }, 1, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        /** Writes pending settings now (also runs on shutdown). */
        fun flushSettings() {
            if (!savePending.getAndSet(false)) return
            val settings = inMemorySettings ?: return
            runCatching { saveExtensionSettings(settings) }
                .onFailure { System.err.println("ext settings save failed: ${it.message}") }
        }

        fun getContext(): Context? {
            return android.content.DesktopContext
        }
    }
}
