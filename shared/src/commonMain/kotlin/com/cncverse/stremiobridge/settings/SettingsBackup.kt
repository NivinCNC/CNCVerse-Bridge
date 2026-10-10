package com.cncverse.stremiobridge.settings

import com.cncverse.stremiobridge.repo.RepoManager
import com.cncverse.stremiobridge.repo.loadExtensionSettings
import com.cncverse.stremiobridge.repo.loadRepoUrls
import com.cncverse.stremiobridge.repo.saveRepoUrls
import com.cncverse.stremiobridge.repo.setExtensionSetting
import com.cncverse.stremiobridge.server.BridgeRuntime
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.state.RepoState
import com.cncverse.stremiobridge.state.ServerState
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One JSON file with everything a user configured, so a reinstall or a second device starts
 * exactly the same: stream filters and sorting, quality and anime options, catalogs, formatter
 * templates, stream-cache and media-server settings, enabled / disabled extensions, repositories,
 * extension settings (logins, tunnel) and the list of installed extensions.
 */
@Serializable
data class BackupFile(
    val app: String = "CNCVerse Bridge",
    val format: Int = 1,
    val createdAt: Long = 0L,
    val appVersion: String? = null,
    /** Config files of the app folder by name (their JSON text). */
    val files: Map<String, String> = emptyMap(),
    val catalogsHidden: Boolean = false,
    val repos: List<String> = emptyList(),
    val extensionSettings: Map<String, String> = emptyMap(),
    /** Extensions (internal names) that were installed. */
    val installedPlugins: List<String> = emptyList(),
    val includesSecrets: Boolean = true,
)

data class ImportOutcome(val ok: Boolean, val message: String, val pluginsToInstall: List<String> = emptyList())

object SettingsBackup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** The config files that are part of a backup (nothing else is ever read or written by an import). */
    private val CONFIG_FILES = listOf(
        "stream_prefs.json",
        "disabled_plugins.json",
        "enabled_sources.json",
        "stream_formatter.json",
        "stream_cache_config.json",
        "media_server.json", "upstream_addons.json",
    )

    private const val CATALOGS_MARKER = "catalogs_disabled_globally"

    /** Setting names that hold logins, tokens or cookies. */
    private val SECRET_KEY = Regex("token|password|passwd|secret|cookie|licen[sc]e|auth|session|bearer", RegexOption.IGNORE_CASE)

    private fun dir(): File? = BridgeRuntime.cacheDir.takeIf { it.isNotBlank() }?.let { File(it) }

    fun suggestedFileName(): String {
        val t = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.ROOT).format(java.util.Date())
        return "cncverse-bridge-backup-$t.json"
    }

    /** Builds the backup text. With [includeSecrets] false, tokens / logins / cookies are left out. */
    fun export(includeSecrets: Boolean, appVersion: String? = null): String {
        val d = dir()
        val files = LinkedHashMap<String, String>()
        if (d != null) {
            for (name in CONFIG_FILES) {
                val f = File(d, name)
                if (f.isFile) runCatching { files[name] = f.readText() }
            }
        }
        val settings = runCatching { loadExtensionSettings() }.getOrDefault(emptyMap())
            .filter { (k, _) -> includeSecrets || !SECRET_KEY.containsMatchIn(k) }
        val backup = BackupFile(
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
            files = files,
            catalogsHidden = ServerState.disableCatalogsGlobally,
            repos = runCatching { loadRepoUrls() }.getOrDefault(emptyList()),
            extensionSettings = settings,
            installedPlugins = RepoState.installedPlugins.value.map { it.internalName }.sorted(),
            includesSecrets = includeSecrets,
        )
        return json.encodeToString(backup)
    }

    /** Reads a backup and applies it. Existing settings are replaced by the backup's; nothing is deleted besides that. */
    fun import(text: String): ImportOutcome {
        val backup = try {
            json.decodeFromString<BackupFile>(text.trim())
        } catch (e: Exception) {
            return ImportOutcome(false, "This is not a CNCVerse Bridge backup file.")
        }
        if (!backup.app.contains("CNCVerse", ignoreCase = true)) return ImportOutcome(false, "This backup belongs to another app.")
        if (backup.format > 1) return ImportOutcome(false, "This backup was made by a newer version of the app. Update the app first.")
        val d = dir() ?: return ImportOutcome(false, "The app is still starting. Try again in a moment.")
        d.mkdirs()

        var applied = 0
        for ((name, content) in backup.files) {
            if (name !in CONFIG_FILES) continue
            // Only well-formed JSON replaces a setting file
            val valid = runCatching { Json.parseToJsonElement(content) }.isSuccess
            if (!valid) continue
            runCatching { File(d, name).writeText(content); applied++ }
        }
        // Files the backup does not have go back to their defaults
        for (name in CONFIG_FILES) if (name !in backup.files) runCatching { File(d, name).delete() }

        val marker = File(d, CATALOGS_MARKER)
        runCatching { if (backup.catalogsHidden) marker.writeText("1") else marker.delete() }

        backup.extensionSettings.forEach { (k, v) -> runCatching { setExtensionSetting(k, v) } }

        if (backup.repos.isNotEmpty()) {
            val merged = (loadRepoUrls() + backup.repos).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            runCatching { saveRepoUrls(merged); RepoManager.loadSavedRepos() }
        }

        // The running server re-reads everything it keeps in memory
        runCatching { StremioServer.reloadConfig(d.absolutePath) }
            .onFailure { ServerState.warn("Backup import: reloading settings failed: ${it.message}") }

        val missing = backup.installedPlugins.filter { !RepoState.isInstalled(it) }
        ServerState.info("Settings backup imported: $applied file(s), ${backup.repos.size} repo(s), ${backup.extensionSettings.size} extension setting(s)")
        val msg = buildString {
            append("Imported $applied settings file(s)")
            if (backup.repos.isNotEmpty()) append(", ${backup.repos.size} repository(ies)")
            if (backup.extensionSettings.isNotEmpty()) append(", ${backup.extensionSettings.size} extension setting(s)")
            append(".")
            if (missing.isNotEmpty()) append(" ${missing.size} extension(s) will be reinstalled.")
        }
        return ImportOutcome(true, msg, missing)
    }

    /** Refreshes the repositories and installs the extensions a backup listed that are not installed yet. Returns how many. */
    suspend fun reinstall(names: List<String>): Int {
        if (names.isEmpty()) return 0
        RepoManager.refreshAllRepos()
        var ok = 0
        for (name in names) {
            if (RepoState.isInstalled(name)) continue
            val ap = RepoState.availablePlugins.value.firstOrNull { it.plugin.internalName == name } ?: continue
            if (BridgeRuntime.installPlugin(ap)) ok++
        }
        ServerState.info("Backup restore: reinstalled $ok of ${names.size} extension(s)")
        return ok
    }
}

/**
 * Hooks the platform fills in for saving / opening a file with the system file picker
 * (Android: the document picker). Without them the Settings screen falls back to copy / paste.
 */
object FileTransferHost {
    /** (suggested file name, text, onDone(message or null on cancel)) */
    @Volatile var saveText: ((String, String, (String?) -> Unit) -> Unit)? = null
    /** onResult(text, or null when cancelled) */
    @Volatile var openText: (((String?) -> Unit) -> Unit)? = null
}
