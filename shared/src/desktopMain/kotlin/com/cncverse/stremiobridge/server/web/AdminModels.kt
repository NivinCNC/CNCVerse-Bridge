package com.cncverse.stremiobridge.server.web

import kotlinx.serialization.Serializable

// ─── Summary (polled by the web UI) ──────────────────────────────────────────

@Serializable
data class AdminServerInfo(
    val status: String,            // Stopped | Starting | Running | Error
    val message: String = "",
    val port: Int? = null,
    val ipAddress: String? = null,
    val pluginCount: Int = 0,
    val lanUrl: String? = null,
    val localhostUrl: String? = null,
    val stremioModeUrl: String? = null,
    val disableCatalogsGlobally: Boolean = false,
)

@Serializable
data class AdminTunnelInfo(
    val stremioMode: Boolean = false,
    val activeUrl: String? = null,
    val cloudflaredInstalled: Boolean = false,
    val downloadProgress: Float? = null,   // 0..1 while downloading cloudflared
)

@Serializable
data class AdminRepoInfo(
    val url: String,
    val name: String = "",
    val iconUrl: String? = null,
    val description: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val pluginCount: Int = 0,
    /** True = persisted globally; false = local/session-only (not saved to disk). */
    val isGlobal: Boolean = true,
)

@Serializable
data class AdminInstalledPluginInfo(
    val internalName: String,
    val displayName: String,
    val version: Int,
    val iconUrl: String? = null,
    val tvTypes: List<String> = emptyList(),
    val language: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    val apiRegistered: Boolean = false,
    val hasSettings: Boolean = false,
    val updateAvailable: Boolean = false,
    val newVersion: Int? = null,
    val health: String = "unknown",
    val autoUninstall: Boolean = false,
    val proxyCountry: String? = null,
    val proxyMode: String = "auto",
)

@Serializable
data class AdminUpdateInfo(
    val tagName: String,
    val htmlUrl: String,
    val body: String = "",
    val assetName: String,
    val assetUrl: String,
    val downloadProgress: Float? = null,   // 0..1 while downloading
)

@Serializable
data class AdminSummary(
    val headless: Boolean,
    val version: String,
    val platform: String,
    val server: AdminServerInfo,
    val tunnel: AdminTunnelInfo,
    val repos: List<AdminRepoInfo>,
    val installedPlugins: List<AdminInstalledPluginInfo>,
    val refreshing: Boolean = false,
    val update: AdminUpdateInfo? = null,
    val tokenRequired: Boolean = false,
    val themeAccent: String = "#8b5cf6",
    val themeGlow: String = "rgba(139, 92, 246, 0.28)",
    val themeHover: String = "#7c3aed",
    val baseTheme: String = "slate",
    val footerCredits: List<com.cncverse.stremiobridge.state.FooterCredit> = emptyList(),
    val cacheStats: com.cncverse.stremiobridge.cache.StreamCacheStats? = null,
)

// ─── Extensions listing ──────────────────────────────────────────────────────

@Serializable
data class AdminAvailablePlugin(
    val internalName: String,
    val displayName: String,
    val version: Int,
    val iconUrl: String? = null,
    val tvTypes: List<String> = emptyList(),
    val language: String? = null,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val repoUrl: String,
    val repoName: String = "",
    val installed: Boolean = false,
    val installedVersion: Int? = null,
    val updateAvailable: Boolean = false,
    val installState: String = "NotInstalled",  // NotInstalled|Installing|Installed|UpdateAvailable|Failed
    val installProgress: String? = null,
    val error: String? = null,
)

// ─── Profile (per-session extension disable) ──────────────────────────────────

/**
 * A per-browser-session profile: stores which extensions the user has disabled
 * in their personal manifest, plus which globally-disabled extensions they
 * have explicitly opted into. Globally installed extensions remain installed;
 * only the manifest they receive differs.
 */
@Serializable
data class AdminProfile(
    val profileId: String,
    val disabledExtensions: Set<String> = emptySet(),
    /** Globally-disabled extensions this profile has explicitly opted into. */
    val enabledExtensions: Set<String> = emptySet(),
)

// ─── Plugin settings ──────────────────────────────────────────────────────────

@Serializable
data class AdminSetting(
    val key: String,
    val storageKey: String,
    val type: String,
    val friendlyName: String,
    val description: String,
    val category: String,
    val options: Map<String, String>? = null,      // label -> value (dropdown/radio)
    val currentValue: String? = null,
    val defaultSet: List<String> = emptyList(),     // StringSet option list
    val currentSet: List<String> = emptyList(),     // StringSet current selection
    val isBooleanLike: Boolean = false,
    val isDisabledStyle: Boolean = false,          // set lists OFF providers instead of ON
    val defaultValue: String? = null,
)

@Serializable
data class AdminPluginSettings(
    val internalName: String,
    val displayName: String,
    val settings: List<AdminSetting>,
)

@Serializable
data class AdminActionRequest(
    val url: String? = null,
    val internalName: String? = null,
    val enabled: Boolean? = null,
    val storageKey: String? = null,
    val value: String? = null,
    /** For install-all: which repo URL to install all plugins from. */
    val repoUrl: String? = null,
    /** For profile toggle: the profile ID. */
    val profileId: String? = null,
    /** For local repo: whether to promote to globally saved. */
    val saveGlobally: Boolean? = null,
    /** For batch operations (e.g. batch uninstall). */
    val internalNames: List<String>? = null,
)

// ─── Logs ────────────────────────────────────────────────────────────────────

@Serializable
data class AdminLogEntry(
    val timestamp: Long,
    val level: String,
    val message: String,
)

// ─── Generic response ────────────────────────────────────────────────────────

@Serializable
data class AdminActionResult(
    val ok: Boolean,
    val message: String? = null,
)

// ─── Theme Accent Request ────────────────────────────────────────────────────

@Serializable
data class AdminThemeRequest(
    val accentHex: String? = null,
    val accentGlow: String? = null,
    val accentHover: String? = null,
    val baseTheme: String? = null,
)

// ─── Stream formatter ────────────────────────────────────────────────────────

@Serializable
data class AdminFormatterState(
    val enabled: Boolean,
    val nameTemplate: String,
    val descriptionTemplate: String,
    val presetName: String,
    val presetDescription: String,
    val presets: List<com.cncverse.stremiobridge.format.FormatterPreset> = emptyList(),
    val variables: List<String>,
)

@Serializable
data class AdminFormatterRequest(
    val enabled: Boolean = false,
    val nameTemplate: String = "",
    val descriptionTemplate: String = "",
)

@Serializable
data class AdminFormatterSample(
    val label: String,
    val name: String,
    val description: String,
)

@Serializable
data class AdminFormatterPreview(
    val ok: Boolean,
    val error: String? = null,
    val samples: List<AdminFormatterSample> = emptyList(),
)


// ─── Nightly maintenance / health / auto-uninstall ───────────────────────────

@Serializable
data class AdminAutoUninstallEntry(
    val internalName: String,
    val displayName: String,
    val optedInAt: Long,
    val status: String,
    val lastSuccessTime: Long = 0,
    /** Days left before it would be removed (null = not dead, nothing scheduled). */
    val dueInDays: Double? = null,
)

@Serializable
data class AdminMaintenanceInfo(
    val nextRunAt: Long,
    val running: Boolean,
    val step: String? = null,
    val lastRun: com.cncverse.stremiobridge.maintenance.MaintenanceRun? = null,
    val sweep: com.cncverse.stremiobridge.maintenance.SweepState,
    val autoUninstallDays: Int,
    val optedIn: List<AdminAutoUninstallEntry> = emptyList(),
    val history: List<com.cncverse.stremiobridge.maintenance.AutoUninstallRecord> = emptyList(),
)

@Serializable
data class AdminMaintenanceRunRequest(val reload: Boolean = true, val sweep: Boolean = true)

@Serializable
data class AdminSweepRequest(val onlyStale: Boolean = false, val internalName: String? = null)

@Serializable
data class AdminDaysRequest(val days: Int)

@Serializable
data class AdminAutoUninstallToggle(val internalName: String, val enabled: Boolean)

// ─── Geo proxy ───────────────────────────────────────────────────────────────

@Serializable
data class AdminGeoCountry(
    val country: String,
    val healthy: Int,
    val refilling: Boolean,
    val lastRefill: Long? = null,
    val proxies: List<com.cncverse.stremiobridge.network.geo.PooledProxySnapshot>,
)


@Serializable
data class AdminGeoInfo(
    val settings: com.cncverse.stremiobridge.network.geo.GeoProxySettings,
    val countries: List<AdminGeoCountry>,
    val plugins: List<com.cncverse.stremiobridge.network.geo.PluginGeoStatus>,
    val events: List<com.cncverse.stremiobridge.network.geo.GeoEvent>,
    val leases: List<com.cncverse.stremiobridge.network.geo.StreamLeaseInfo>,
)

@Serializable
data class AdminGeoOverrideRequest(val plugin: String, val mode: String = "auto", val country: String? = null)

@Serializable
data class AdminGeoCountryRequest(val country: String, val key: String? = null)

@Serializable
data class AdminGeoPluginRequest(val plugin: String)
