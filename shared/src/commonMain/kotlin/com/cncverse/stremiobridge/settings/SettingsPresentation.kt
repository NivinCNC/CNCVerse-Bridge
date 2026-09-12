package com.cncverse.stremiobridge.settings

import com.cncverse.stremiobridge.settings.PluginSettingSchema

/**
 * Presentation metadata shared by the desktop Compose settings dialog and the
 * web admin settings API: logical categorization, friendly display names and
 * descriptions for plugin settings discovered at runtime.
 */
object SettingsPresentation {

    // ── Logical categorization (mirrors cs3-desktop-client) ─────────────────

    val categoryPriorities = mapOf(
        "General Configurations" to 0,
        "Accounts & API Integrations" to 1,
        "Stremio & External Catalogs" to 2,
        "Scrapers & Engines" to 3,
    )

    fun getCategory(key: String): String {
        val lower = key.lowercase()
        return when {
            lower.contains("stremio") || lower.contains("addon") || lower.contains("catalog") -> "Stremio & External Catalogs"
            lower.contains("key") || lower.contains("token") || lower.contains("auth") || lower.contains("api") ||
                lower.contains("password") || lower.contains("username") -> "Accounts & API Integrations"
            lower.contains("provider") || lower.contains("source") || lower.contains("channel") ||
                lower.contains("extractor") || lower.endsWith("enable") || lower.contains("concurrency") ||
                lower.startsWith("scrape") -> "Scrapers & Engines"
            else -> "General Configurations"
        }
    }

    fun getCategoryPriority(key: String): Int = categoryPriorities[getCategory(key)] ?: 4

    /** Sort order used by the settings dialog: category first, then friendly name. */
    val settingsComparator: Comparator<PluginSettingSchema> =
        compareBy<PluginSettingSchema> { getCategoryPriority(it.key) }
            .thenBy { getFriendlyName(it.key) }

    // ── Friendly names & descriptions ─────────────────────────────────────────

    /** Explicit friendly name overrides for known plugin settings. */
    val friendlyNameOverrides = mapOf(
        "ProviderCineStream" to "CineStream Catalog",
        "ProviderSimkl" to "Simkl Catalog",
        "ProviderTmdb" to "TMDB Catalog",
        "stremio_addons" to "Stremio Addon URLs",
        "token" to "FebBox Token",
        "provider_concurrency" to "Scrape Concurrency",
        "enabled_plugins_saved" to "Enabled Providers",
        "moviebox_host" to "MovieBox Server",
        "ScrapeConcurrency" to "Scrape Concurrency",
        "DownloadEnable" to "Download Only Links",
        "showbox_ui_token" to "ShowBox Token",
        "wyzie_subs_api_key" to "Wyzie Subtitles API Key",
        "gramcinema_bearer_token" to "GramCinema Token",
        "new_provider_default_on" to "Auto-Enable New Providers",
        "cloudflare_webview_bypass_enabled" to "Cloudflare Bypass",
    )

    /** Explicit description overrides for known plugin settings. */
    val descriptionOverrides = mapOf(
        "ProviderCineStream" to "Enable the Cinemeta-backed catalog for browsing movies & shows.",
        "ProviderSimkl" to "Enable the Simkl-backed catalog for anime & watchlist integration.",
        "ProviderTmdb" to "Enable the TMDB-backed catalog for trending & popular content.",
        "stremio_addons" to "Comma-separated Stremio addon manifest URLs for external catalogs.",
        "token" to "Paste your FebBox authentication token for premium source access.",
        "provider_concurrency" to "Max parallel scraping threads (-1 = unlimited).",
        "enabled_plugins_saved" to "Select which scraping engines are active for this plugin.",
        "moviebox_host" to "Select which MovieBox API server to use.",
        "ScrapeConcurrency" to "Max parallel scraping threads (default: 10).",
        "DownloadEnable" to "Only fetch direct download links (not for streaming).",
        "showbox_ui_token" to "Paste your ShowBox/FebBox UI token.",
        "wyzie_subs_api_key" to "API key for Wyzie subtitle service.",
        "gramcinema_bearer_token" to "Bearer token for GramCinema source.",
        "new_provider_default_on" to "Automatically enable newly added providers on update.",
        "cloudflare_webview_bypass_enabled" to "Use WebView to bypass Cloudflare protection on supported sites.",
    )

    fun getFriendlyName(key: String): String {
        friendlyNameOverrides[key]?.let { return it }

        var clean = key.substringAfterLast('/')
        if (clean.startsWith("Provider")) {
            clean = clean.removePrefix("Provider")
        }

        val friendly = clean.replace("_", " ")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .trim()
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

        return friendly
            .replace(" Saved Links", " Links Cache")
            .replace(" Concurrency", " Simultaneous Connections")
    }

    fun getDescription(key: String): String {
        descriptionOverrides[key]?.let { return it }
        val friendly = getFriendlyName(key)
        return when {
            key.startsWith("Provider") -> "Enable or disable the $friendly search scraper channel."
            key.lowercase().contains("concurrency") -> "Set maximum simultaneous connection threads to speed up retrieval."
            key.lowercase().contains("token") || key.lowercase().contains("key") -> "Configure authentication credentials/API key for $friendly."
            key.lowercase().contains("stremio") -> "Configure external streaming catalog source links."
            key.lowercase().contains("disabled") -> "Toggle individual sub-scrapers and data sources for this plugin."
            key.lowercase().contains("enabled") -> "Select which sub-engines are active."
            else -> ""
        }
    }

    // ── Value-shape helpers (shared by dialog and web renderer) ───────────────

    /** True when the setting should render as a toggle instead of a text field. */
    fun isBooleanLike(schema: PluginSettingSchema, rawValue: String?): Boolean {
        if (schema.type == "Boolean" || schema.defaultValue is Boolean) return true
        if (rawValue == "true" || rawValue == "false") return true
        // Plugins toggle individual providers through keys named ProviderXxx / xxxEnable
        if (schema.key.startsWith("Provider") || schema.key.endsWith("Enable")) return true
        return false
    }

    /** Parses a stored set value: newline-joined (native) or legacy "[a, b]" toStrings. */
    fun parseStoredSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val trimmed = raw.trim()
        val items = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.removeSurrounding("[", "]").split(",")
        } else {
            trimmed.split("\n")
        }
        return items.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Default option list for a StringSet setting (the plugin's declared provider set). */
    fun stringSetOptions(schema: PluginSettingSchema, rawValue: String?): List<String> {
        val current = parseStoredSet(rawValue)
        return ((schema.defaultValue as? Set<*>)?.map { it.toString() }?.toSet() ?: emptySet())
            .plus(current)
            .sorted()
    }

    /** True for keys named "…disabled…" where the stored set means OFF providers. */
    fun isDisabledStyleKey(schema: PluginSettingSchema): Boolean =
        schema.key.lowercase().contains("disabled")
}
