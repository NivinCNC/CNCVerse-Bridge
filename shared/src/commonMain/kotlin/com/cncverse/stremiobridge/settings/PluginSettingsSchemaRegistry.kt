package com.cncverse.stremiobridge.settings

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

data class PluginSettingSchema(
    val pluginPrefName: String,
    val key: String,
    val type: String, // "Boolean", "String", "Int", "Long", "Float", "StringSet"
    val defaultValue: Any?,
    /**
     * The exact key in the shared settings map. SharedPreferences-backed keys
     * are stored as "<pluginPrefName><key>", but keys registered through
     * CloudStreamApp.getKey/setKey use the plugin's raw path unchanged — the
     * settings dialog must read/write this, not pluginPrefName + key.
     */
    val storageKey: String,
    /** Optional map of Display Name -> Value for Dropdown/Radio settings. */
    val options: Map<String, String>? = null,
)

/**
 * Registry of plugin settings discovered at runtime. Plugins declare settings
 * in code and read them through CloudStreamApp/DataStore — every getKey/setKey
 * call from plugin code registers the key here (see the desktop CloudStreamApp),
 * which is what the desktop settings dialog renders.
 */
object PluginSettingsSchemaRegistry {
    // Map of pluginPrefName (e.g. "AniKoto_") to a map of keys and their schemas
    val schemas = ConcurrentHashMap<String, ConcurrentHashMap<String, PluginSettingSchema>>()

    // Observable flow to trigger UI updates when new settings are detected
    val schemaUpdates = MutableStateFlow(0)

    fun register(
        pluginPrefName: String,
        key: String,
        type: String,
        defaultValue: Any?,
        storageKey: String? = null,
    ) {
        val normalizedPref = if (pluginPrefName.endsWith("_")) pluginPrefName else "${pluginPrefName}_"
        val pluginMap = schemas.getOrPut(normalizedPref) { ConcurrentHashMap() }

        val existing = pluginMap[key]
        if (existing == null) {
            pluginMap[key] = PluginSettingSchema(
                normalizedPref, key, type, defaultValue,
                storageKey ?: normalizedPref + key,
            )
            schemaUpdates.value++
            com.cncverse.stremiobridge.state.ServerState.info(
                "Setting discovered: ${normalizedPref.removeSuffix("_")} → $key ($type${defaultValue?.let { " = $it" } ?: ""})"
            )
        } else if (existing.type != type && defaultValue != null &&
            (existing.defaultValue == null || (existing.type == "Boolean" && existing.defaultValue == true))
        ) {
            // Refine schema when a typed read reveals more than an earlier
            // untyped/contains-probe registration; keep the original storage
            // key so the dialog hits the same slot
            pluginMap[key] = PluginSettingSchema(
                normalizedPref, key, type, defaultValue, existing.storageKey,
            )
            schemaUpdates.value++
        } else if (
            existing.type == "StringSet" &&
            defaultValue is Set<*> &&
            existing.defaultValue is Set<*> &&
            defaultValue != existing.defaultValue
        ) {
            // Plugin updated — replace the old provider list with the new one.
            // Providers removed from the new version disappear from the options UI;
            // providers added in the new version appear immediately.
            // The stored preference value on disk is intentionally NOT touched:
            // providers the user enabled that still exist in the new set remain
            // enabled; providers dropped from the new set are simply not rendered.
            pluginMap[key] = existing.copy(defaultValue = defaultValue)
            schemaUpdates.value++
        }
    }

    /** Alphanumeric-only lowercase form, so "cnc_verse" matches "CNCVerseStudios". */
    private fun normalize(s: String): String = s.filter { it.isLetterOrDigit() }.lowercase()

    fun resolvePrefName(pluginInternalName: String, pluginDisplayName: String? = null): String {
        val withUnderscore = if (pluginInternalName.endsWith("_")) pluginInternalName else "${pluginInternalName}_"
        if (schemas.containsKey(withUnderscore) && schemas[withUnderscore]!!.isNotEmpty()) {
            return withUnderscore
        }
        val cleanName = normalize(pluginInternalName)
        val cleanDisplay = pluginDisplayName?.let { normalize(it) } ?: ""
        val nonEmpty = schemas.entries.filter { it.value.isNotEmpty() }

        // Fuzzy match: the internal/display name may differ from the pref name
        // the plugin used (case, underscores, suffixes like "...Studios")
        return nonEmpty.firstOrNull { normalize(it.key.removeSuffix("_")) == cleanName }?.key
            ?: nonEmpty.firstOrNull {
                val k = normalize(it.key.removeSuffix("_"))
                k.contains(cleanName) || cleanName.contains(k)
            }?.key
            ?: nonEmpty.firstOrNull {
                cleanDisplay.isNotEmpty() && run {
                    val k = normalize(it.key.removeSuffix("_"))
                    k.contains(cleanDisplay) || cleanDisplay.contains(k)
                }
            }?.key
            ?: withUnderscore
    }

    /**
     * Keys that are internal serialized data, not user-editable settings.
     * These are filtered out of the settings dialog.
     */
    private val hiddenKeys = setOf(
        "seen_providers",
        "provider_order",
        "StreamPlay_enabled_plugins_set",
        "streamplay_stremio_saved_links",
        "cf_cookie_",
    )

    private fun isHiddenKey(key: String): Boolean =
        hiddenKeys.any { key == it || key.startsWith(it) }

    fun getSettingsForPlugin(pluginInternalName: String, pluginDisplayName: String? = null): List<PluginSettingSchema> {
        val resolved = resolvePrefName(pluginInternalName, pluginDisplayName)
        val schemasList = schemas[resolved]?.values?.toList() ?: emptyList()
        return schemasList
            .filter { !isHiddenKey(it.key) }
            .map { schema ->
                if (schema.key == "moviebox_host") {
                    schema.copy(
                        options = mapOf(
                            "Server 1 (api3.aoneroom.com)" to "https://api3.aoneroom.com",
                            "Server 2 (api.aoneroom.com)" to "https://api.aoneroom.com",
                            "Server 3 (api1.aoneroom.com)" to "https://api1.aoneroom.com",
                            "Server 4 (api2.aoneroom.com)" to "https://api2.aoneroom.com"
                        )
                    )
                } else schema
            }
    }

    fun hasSettings(pluginInternalName: String, pluginDisplayName: String? = null): Boolean {
        val resolved = resolvePrefName(pluginInternalName, pluginDisplayName)
        return schemas.containsKey(resolved) && schemas[resolved]!!.isNotEmpty()
    }

    fun removePlugin(pluginInternalName: String) {
        val resolved = resolvePrefName(pluginInternalName)
        schemas.remove(resolved)
        schemaUpdates.value++
    }
}
