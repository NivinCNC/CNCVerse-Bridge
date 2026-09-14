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
 * in their personal manifest. Globally installed extensions remain installed;
 * only the manifest they receive omits the disabled ones.
 */
@Serializable
data class AdminProfile(
    val profileId: String,
    val disabledExtensions: Set<String> = emptySet(),
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
