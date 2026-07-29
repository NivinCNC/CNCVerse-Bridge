package com.lagradost.cloudstream3

import android.content.Context
import com.cncverse.stremiobridge.repo.loadExtensionSettings
import com.cncverse.stremiobridge.repo.saveExtensionSettings

class CloudStreamApp {
    companion object {
        @PublishedApi
        internal var inMemorySettings: MutableMap<String, String>? = null

        @PublishedApi
        internal fun getSettings(): MutableMap<String, String> {
            if (inMemorySettings == null) {
                inMemorySettings = loadExtensionSettings().toMutableMap()
            }
            return inMemorySettings!!
        }

        inline fun <reified T> getKey(path: String): T? {
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
            val settings = getSettings()
            if (value == null) {
                settings.remove(path)
            } else {
                settings[path] = value.toString()
            }
            saveExtensionSettings(settings)
        }
        
        fun getContext(): Context? {
            return null
        }
    }
}
