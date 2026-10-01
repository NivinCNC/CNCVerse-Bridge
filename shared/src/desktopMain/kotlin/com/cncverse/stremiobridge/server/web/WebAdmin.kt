package com.cncverse.stremiobridge.server.web

import com.cncverse.stremiobridge.Constants
import com.cncverse.stremiobridge.format.StreamFormatter
import com.cncverse.stremiobridge.format.StreamFormatterConfig
import com.cncverse.stremiobridge.format.StreamVariables
import com.cncverse.stremiobridge.format.TemplateException
import com.cncverse.stremiobridge.plugin.GlobalPluginManager
import com.cncverse.stremiobridge.repo.PluginInstaller
import com.cncverse.stremiobridge.repo.RepoManager
import com.cncverse.stremiobridge.server.BridgeRuntime
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.settings.PluginSettingsSchemaRegistry
import com.cncverse.stremiobridge.settings.SettingsPresentation
import com.cncverse.stremiobridge.state.PluginInstallState
import com.cncverse.stremiobridge.state.RepoState
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.state.ServerStatus
import com.cncverse.stremiobridge.state.StreamTracker
import com.cncverse.stremiobridge.tunnel.CloudflaredManager
import com.cncverse.stremiobridge.update.GithubRelease
import com.cncverse.stremiobridge.update.OtaUpdater
import com.cncverse.stremiobridge.update.installOtaUpdate
import com.cncverse.stremiobridge.update.otaAssetExtension
import com.lagradost.cloudstream3.CloudStreamApp
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

private val adminJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Web admin panel: serves the single-page admin UI at `/admin` and a JSON API
 * under the `/api/admin` prefix that mirrors everything the Compose desktop UI
 * can do — server start/stop, Stremio mode + Cloudflare tunnel, repo
 * management, plugin install/uninstall/update/enable, per-plugin settings and
 * live logs.
 *
 * Enabled always in the headless server app; on the desktop when the
 * CNC_WEB_ADMIN=1 environment variable is set.
 */
object WebAdmin {

    /** Set by the headless server entrypoint — always serves the web UI there. */
    @Volatile var headlessMode: Boolean = false

    /** Preferred port for start/restart actions (recorded by the entrypoint). */
    @Volatile var preferredPort: Int = 8080

    /** Background scope (set by the app entrypoint, same as BridgeRuntime.appScope). */
    @Volatile var appScope: CoroutineScope? = null

    /** Optional bearer/query token (CNC_ADMIN_TOKEN env var) protecting /admin + /api/admin. */
    val adminToken: String?
        get() = System.getenv("CNC_ADMIN_TOKEN")?.takeIf { it.isNotBlank() }

    val isEnabled: Boolean
        get() = headlessMode || System.getenv("CNC_WEB_ADMIN") == "1"

    // ── Live progress state surfaced through /api/admin/summary ───────────────
    @Volatile var cloudflaredDownloadProgress: Float? = null
    @Volatile var updateDownloadProgress: Float? = null
    @Volatile var currentUpdateRelease: GithubRelease? = null

    /**
     * Set of repo URLs that were added locally (not yet promoted to global storage).
     * These repos are loaded for the current process session but not saved to disk.
     */
    val localOnlyRepos: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Guards "Benchmark all" so only one full probe sweep runs at a time. */
    private val benchmarkRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun scope(): CoroutineScope = appScope ?: CoroutineScope(Dispatchers.Default)

    private fun ApplicationCall.checkAdminAuth(): Boolean {
        val token = adminToken ?: return true
        val header = request.headers["Authorization"]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()
        val query = request.queryParameters["token"]
        return token == header || token == query
    }

    private suspend fun ApplicationCall.respondUnauthorized() {
        respondText("401 Unauthorized", status = io.ktor.http.HttpStatusCode.Unauthorized)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Route installer — mounted both on the main addon engine and on the
    // standalone [AdminServer] fallback engine (which keeps the panel alive
    // while the addon server is stopped in headless mode).
    // ─────────────────────────────────────────────────────────────────────────

    fun Route.adminRoutes() {
        get("/logo.png") {
            val bytes = StremioServer.logoBytes
            if (bytes != null) {
                call.respondBytes(bytes, ContentType.Image.PNG)
            } else {
                call.respondRedirect("https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/cnc.png")
            }
        }
        get("/favicon.ico") {
            val bytes = StremioServer.logoBytes
            if (bytes != null) {
                call.respondBytes(bytes, ContentType.Image.PNG)
            } else {
                call.respondRedirect("https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/cnc.png")
            }
        }

        get("/admin") {
            if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
            call.respondText(AdminHtml.page, ContentType.Text.Html)
        }

        route("/api/admin") {
            get("/summary") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                call.respond(buildSummary())
            }

            post("/server/start") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                scope().launch {
                    AdminServer.stop()
                    runCatching { BridgeRuntime.startBridge(preferredPort) }
                        .onFailure { e ->
                            ServerState.error("Fatal: ${e.message}")
                            ServerState.updateStatus(ServerStatus.Error(e.message ?: "Unknown"))
                        }
                }
                call.respond(AdminActionResult(true, "Starting server…"))
            }

            post("/server/stop") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val port = ServerState.serverPort
                scope().launch {
                    BridgeRuntime.stopBridge()
                    if (isEnabled) AdminServer.ensureRunning(port)
                }
                call.respond(AdminActionResult(true, "Stopping server…"))
            }

            post("/server/restart") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                scope().launch {
                    BridgeRuntime.stopBridge()
                    AdminServer.stop()
                    runCatching { BridgeRuntime.startBridge(preferredPort) }
                        .onFailure { e ->
                            ServerState.error("Fatal: ${e.message}")
                            ServerState.updateStatus(ServerStatus.Error(e.message ?: "Unknown"))
                        }
                }
                call.respond(AdminActionResult(true, "Restarting server…"))
            }

            post("/stremio-mode") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = call.receive<AdminActionRequest>()
                val enabled = body.enabled ?: !ServerState.isStremioMode.value
                ServerState.isStremioMode.value = enabled
                if (enabled && ServerState.status.value is ServerStatus.Running) {
                    CloudflaredManager.startTunnel(ServerState.serverPort)
                } else if (!enabled) {
                    CloudflaredManager.stopTunnel()
                }
                call.respond(AdminActionResult(true, "Stremio mode " + if (enabled) "enabled" else "disabled"))
            }

            post("/tunnel/start") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                scope().launch {
                    val port = (ServerState.status.value as? ServerStatus.Running)?.port ?: ServerState.serverPort
                    if (!CloudflaredManager.isInstalled()) {
                        cloudflaredDownloadProgress = 0f
                        val ok = CloudflaredManager.downloadCloudflared { p -> cloudflaredDownloadProgress = p }
                        cloudflaredDownloadProgress = null
                        if (!ok) {
                            ServerState.error("Cloudflared download failed — tunnel not started")
                            return@launch
                        }
                    }
                    ServerState.isStremioMode.value = true
                    CloudflaredManager.startTunnel(port)
                }
                call.respond(AdminActionResult(true, "Starting Cloudflare tunnel…"))
            }

            post("/tunnel/stop") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                CloudflaredManager.stopTunnel()
                call.respond(AdminActionResult(true, "Tunnel stopped"))
            }

            post("/server/toggle-catalogs") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                ServerState.disableCatalogsGlobally = !ServerState.disableCatalogsGlobally
                StremioServer.saveGlobalCatalogSetting()
                val msg = if (ServerState.disableCatalogsGlobally) "Catalogs disabled globally (Streams & Search only)"
                          else "Catalogs enabled globally"
                ServerState.info("⚙️ $msg")
                call.respond(AdminActionResult(true, msg))
            }

            // ── Repos ────────────────────────────────────────────────────────

            post("/repos/add") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = call.receive<AdminActionRequest>()
                val url = body.url?.trim().orEmpty()
                if (url.isEmpty()) return@post call.respond(AdminActionResult(false, "Missing url"))
                val saveGlobally = body.saveGlobally ?: true
                scope().launch {
                    val entry = BridgeRuntime.addRepo(url, saveGlobally = saveGlobally, autoInstallAll = true, disableNewPluginsByDefault = true)
                    if (!saveGlobally) localOnlyRepos.add(url)
                    when {
                        entry == null -> ServerState.warn("Repo already installed: $url")
                        entry.error != null -> ServerState.warn("Repo add failed: ${entry.error}")
                        else -> ServerState.info("Repo '${entry.name.ifBlank { entry.url }}' added — sources available in catalog")
                    }
                }
                call.respond(AdminActionResult(true, "Repository added — sources available in catalog"))
            }

            post("/repos/remove") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = call.receive<AdminActionRequest>()
                val url = body.url?.trim().orEmpty()
                if (url.isEmpty()) return@post call.respond(AdminActionResult(false, "Missing url"))
                localOnlyRepos.remove(url)
                // Deletes the repo's extensions (.cs3 + converted jars), cleans the
                // disabled set and profiles, then hot-reloads once.
                scope().launch {
                    runCatching { BridgeRuntime.removeRepo(url) }
                        .onFailure { ServerState.warn("Repo removal failed: ${it.message}") }
                }
                call.respond(AdminActionResult(true, "Repo removed"))
            }


            // Promote a local-only repo to globally saved
            post("/repos/promote-global") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = call.receive<AdminActionRequest>()
                val url = body.url?.trim().orEmpty()
                if (url.isEmpty()) return@post call.respond(AdminActionResult(false, "Missing url"))
                localOnlyRepos.remove(url)
                // Re-add through manager with global persistence (+ auto-install)
                scope().launch { BridgeRuntime.addRepo(url, saveGlobally = true, autoInstallAll = true, disableNewPluginsByDefault = true) }
                call.respond(AdminActionResult(true, "Repo saved globally"))
            }

            post("/repos/refresh") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                scope().launch {
                    runCatching { RepoManager.refreshAllRepos() }
                        .onFailure { ServerState.warn("Repo refresh failed: ${it.message}") }
                }
                call.respond(AdminActionResult(true, "Refreshing repos…"))
            }

            // ── Plugins ──────────────────────────────────────────────────────

            get("/plugins") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                call.respond(buildAvailablePlugins())
            }

            post("/plugins/install") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = call.receive<AdminActionRequest>()
                val internalName = body.internalName ?: return@post call.respond(AdminActionResult(false, "Missing internalName"))
                scope().launch {
                    val ap = RepoState.availablePlugins.value.find { it.plugin.internalName == internalName }
                        ?: run {
                            ServerState.warn("Install failed: plugin '$internalName' not found in any repo")
                            return@launch
                        }
                    BridgeRuntime.installPlugin(ap)
                }
                // Return immediately; UI polls for updates
                call.respond(AdminActionResult(true, "Installing…"))
            }

            // Install every extension from a specific repo (single hot-reload at the end)
            post("/plugins/install-all-from-repo") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = call.receive<AdminActionRequest>()
                val repoUrl = body.repoUrl?.trim().orEmpty()
                if (repoUrl.isEmpty()) return@post call.respond(AdminActionResult(false, "Missing repoUrl"))
                scope().launch {
                    val installed = BridgeRuntime.installAllFromRepo(repoUrl, disableNewPluginsByDefault = true)
                    ServerState.info("Install-all finished for $repoUrl: $installed new extension(s) (disabled by default)")
                }
                call.respond(AdminActionResult(true, "Installing all extensions from repo…"))
            }

            post("/plugins/uninstall") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = call.receive<AdminActionRequest>()
                val internalName = body.internalName ?: return@post call.respond(AdminActionResult(false, "Missing internalName"))
                scope().launch { BridgeRuntime.uninstallPlugin(internalName) }
                call.respond(AdminActionResult(true, "Uninstalling…"))
            }

            post("/plugins/uninstall-batch") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = call.receive<AdminActionRequest>()
                val internalNames = body.internalNames ?: emptyList()
                if (internalNames.isEmpty()) return@post call.respond(AdminActionResult(false, "No internalNames provided"))
                scope().launch { BridgeRuntime.uninstallPlugins(internalNames) }
                call.respond(AdminActionResult(true, "Uninstalling ${internalNames.size} plugin(s)…"))
            }

            post("/plugins/toggle") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = call.receive<AdminActionRequest>()
                val internalName = body.internalName ?: return@post call.respond(AdminActionResult(false, "Missing internalName"))
                val nowEnabled = StremioServer.togglePluginDisabled(internalName)
                // Return updated plugin list so the UI can refresh without an extra roundtrip
                call.respond(buildAvailablePlugins())
            }

            post("/plugins/update-all") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                scope().launch {
                    val toUpdate = RepoState.installedPlugins.value.filter {
                        RepoState.getInstallState(it.internalName) is PluginInstallState.UpdateAvailable
                    }
                    ServerState.info("Updating ${toUpdate.size} plugin(s)…")
                    toUpdate.forEach { inst ->
                        val ap = RepoState.availablePlugins.value.find {
                            it.plugin.internalName == inst.internalName && it.repoEntry.url == inst.repoUrl
                        } ?: return@forEach
                        BridgeRuntime.installPlugin(ap)
                    }
                }
                call.respond(AdminActionResult(true, "Updating plugins…"))
            }

            // ── Profile (per-session extension disable) ──────────────────────

            // Returns the current profile for the given profileId.
            // The UI generates and stores a UUID in a cookie/localStorage and passes
            // it here; the server keeps the disabled-set per profileId.
            get("/profile/{profileId}") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                val profileId = call.parameters["profileId"] ?: return@get call.respond(AdminActionResult(false, "Missing profileId"))
                call.respond(AdminProfile(
                    profileId = profileId,
                    disabledExtensions = StremioServer.getProfileDisabled(profileId),
                    enabledExtensions = StremioServer.getProfileEnabledOverrides(profileId),
                ))
            }

            // Toggle an extension for the calling profile (does not affect global install).
            post("/profile/{profileId}/toggle") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val profileId = call.parameters["profileId"] ?: return@post call.respond(AdminActionResult(false, "Missing profileId"))
                val body = call.receive<AdminActionRequest>()
                val internalName = body.internalName ?: return@post call.respond(AdminActionResult(false, "Missing internalName"))
                StremioServer.toggleProfilePlugin(profileId, internalName)
                call.respond(AdminProfile(
                    profileId = profileId,
                    disabledExtensions = StremioServer.getProfileDisabled(profileId),
                    enabledExtensions = StremioServer.getProfileEnabledOverrides(profileId),
                ))
            }

            // ── Plugin settings ─────────────────────────────────────────────

            get("/plugins/{id}/settings") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                val id = call.parameters["id"] ?: return@get call.respond(AdminActionResult(false, "Missing id"))
                call.respond(buildPluginSettings(id))
            }

            // Runs the plugin's openSettings lambda so its keys register in the
            // schema registry (mirrors pressing the gear button on desktop).
            post("/plugins/{id}/settings/discover") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val id = call.parameters["id"] ?: return@post call.respond(AdminActionResult(false, "Missing id"))
                scope().launch(Dispatchers.IO) {
                    runCatching {
                        GlobalPluginManager.loader?.openPluginSettings(id, null)
                    }.onFailure { ServerState.warn("Settings discovery failed: ${it.message}") }
                }
                call.respond(AdminActionResult(true, "Discovering settings…"))
            }

            post("/plugins/{id}/settings/value") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = call.receive<AdminActionRequest>()
                val storageKey = body.storageKey ?: return@post call.respond(AdminActionResult(false, "Missing storageKey"))
                CloudStreamApp.setKey(storageKey, body.value)
                call.respond(AdminActionResult(true, "Saved"))
            }

            // Apply changed settings without an app restart: providers re-read
            // their prefs during load (mirrors closing the desktop gear dialog).
            post("/plugins/{id}/settings/apply") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                scope().launch {
                    ServerState.info("Applying settings — reloading plugins…")
                    BridgeRuntime.forceReloadPlugins()
                }
                call.respond(AdminActionResult(true, "Applying settings…"))
            }

            // ── Stream formatter ─────────────────────────────────────────────

            get("/formatter") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                val cfg = StreamFormatter.config
                call.respond(AdminFormatterState(
                    enabled = cfg.enabled,
                    nameTemplate = cfg.nameTemplate,
                    descriptionTemplate = cfg.descriptionTemplate,
                    presetName = StreamFormatter.PRESET_NAME,
                    presetDescription = StreamFormatter.PRESET_DESCRIPTION,
                    presets = StreamFormatter.PRESETS,
                    variables = StreamVariables.NAMES,
                ))
            }

            post("/formatter") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = call.receive<AdminFormatterRequest>()
                val cfg = StreamFormatterConfig(body.enabled, body.nameTemplate, body.descriptionTemplate)
                try {
                    StreamFormatter.save(cfg)
                    ServerState.info("Stream formatter " + (if (cfg.enabled) "enabled" else "disabled") + " — templates saved")
                    call.respond(AdminActionResult(true, if (cfg.enabled) "Formatter saved & enabled" else "Formatter saved (disabled)"))
                } catch (e: TemplateException) {
                    call.respond(AdminActionResult(false, e.message))
                }
            }

            post("/formatter/preview") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = call.receive<AdminFormatterRequest>()
                val cfg = StreamFormatterConfig(true, body.nameTemplate, body.descriptionTemplate)
                val result = try {
                    val (nameTpl, descTpl) = StreamFormatter.validate(cfg)
                    val labels = listOf("4K movie (direct file)", "Series episode (HLS)", "Live channel (minimal data)")
                    AdminFormatterPreview(ok = true, samples = StreamFormatter.samples().mapIndexed { idx, (stream, ctx) ->
                        val out = StreamFormatter.format(stream, ctx, nameTpl, descTpl)
                        AdminFormatterSample(labels.getOrElse(idx) { "Sample" }, out.name.orEmpty(), out.title.orEmpty())
                    })
                } catch (e: TemplateException) {
                    AdminFormatterPreview(ok = false, error = e.message)
                }
                call.respond(result)
            }

            // ── Logs ────────────────────────────────────────────────────────

            get("/logs") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                val logs = ServerState.logs.value.map {
                    AdminLogEntry(it.timestamp, it.level.name, it.message)
                }
                call.respond(logs)
            }

            post("/logs/clear") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                ServerState.clearLogs()
                call.respond(AdminActionResult(true, "Logs cleared"))
            }

            // ── Stream Health & Tracking ─────────────────────────────────────

            get("/stream-health") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                val installed = RepoState.installedPlugins.value
                val disabledList = StremioServer.disabledPlugins
                val healthList = installed.map { p ->
                    val isDisabled = disabledList.contains(p.internalName)
                    StreamTracker.getHealth(p.internalName, p.displayName, !isDisabled, p.iconUrl)
                }
                call.respond(healthList)
            }

            post("/stream-health/disable-dead") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val installed = RepoState.installedPlugins.value
                var count = 0
                installed.forEach { p ->
                    val health = StreamTracker.getHealth(p.internalName, p.displayName, true)
                    if (health.totalRequests > 0 && health.successRequests == 0) {
                        StremioServer.setPluginDisabled(p.internalName, true)
                        count++
                    }
                }
                if (count > 0) StremioServer.saveDisabledPlugins()
                call.respond(AdminActionResult(true, "Disabled $count dead/empty plugin(s)"))
            }

            post("/stream-health/clear") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                StreamTracker.clear()
                call.respond(AdminActionResult(true, "Stream tracking stats reset"))
            }

            post("/stream-health/probe") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val targetId = call.request.queryParameters["internalName"]
                val query = call.request.queryParameters["query"]?.takeIf { it.isNotBlank() } ?: "Avatar"
                if (!targetId.isNullOrBlank()) {
                    val api = StremioServer.loadedApis.find {
                        it.internalName == targetId || it.pluginInternalName == targetId ||
                        it.name.equals(targetId, ignoreCase = true)
                    }
                    if (api == null) {
                        return@post call.respond(AdminActionResult(false, "Source '$targetId' not found in loaded extensions"))
                    }
                    ServerState.info("🔍 Probing source [${api.name}] with query '$query'...")
                    val streamCount = StremioServer.probeApi(api, query)
                    val msg = if (streamCount > 0) "✅ [${api.name}] Probe successful: $streamCount stream(s) found"
                              else "❌ [${api.name}] Probe returned 0 streams"
                    call.respond(AdminActionResult(streamCount > 0, msg))
                } else {
                    // A full benchmark probes every extension (minutes of work on a small VPS):
                    // allow one at a time so repeated clicks / tabs can't stack sweeps.
                    if (!benchmarkRunning.compareAndSet(false, true)) {
                        return@post call.respond(AdminActionResult(false, "A benchmark is already running — wait for it to finish"))
                    }
                    try {
                        ServerState.info("🔍 Running stream probe benchmark across all active providers (query: '$query')...")
                        val results = StremioServer.probeAllApis(query)
                        val success = results.values.count { it > 0 }
                        val total = results.size
                        val msg = "Benchmark complete: $success/$total active source(s) resolved streamable links"
                        ServerState.info("🏁 $msg")
                        call.respond(AdminActionResult(true, msg))
                    } finally {
                        benchmarkRunning.set(false)
                    }
                }
            }


            // ── OTA updates ──────────────────────────────────────────────────

            get("/update/check") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                val release = runCatching { OtaUpdater.checkForUpdate() }.getOrNull()
                currentUpdateRelease = release
                val asset = release?.assets?.find { it.name.endsWith(otaAssetExtension, ignoreCase = true) }
                call.respond(
                    if (release != null && asset != null) {
                        AdminUpdateInfo(
                            tagName = release.tagName,
                            htmlUrl = release.htmlUrl,
                            body = release.body.take(800),
                            assetName = asset.name,
                            assetUrl = asset.downloadUrl,
                        )
                    } else AdminUpdateInfo("", "", "", "", "")
                )
            }

            post("/update/apply") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val release = currentUpdateRelease
                    ?: return@post call.respond(AdminActionResult(false, "Check for updates first"))
                val asset = release.assets.find { it.name.endsWith(otaAssetExtension, ignoreCase = true) }
                    ?: return@post call.respond(AdminActionResult(false, "No asset for this platform"))
                scope().launch {
                    updateDownloadProgress = 0f
                    val filePath = OtaUpdater.downloadUpdate(asset.downloadUrl, asset.name) { p ->
                        updateDownloadProgress = p
                    }
                    updateDownloadProgress = null
                    if (filePath == null) {
                        ServerState.warn("Update download failed")
                        return@launch
                    }
                    installDownloadedUpdate(filePath)
                }
                call.respond(AdminActionResult(true, "Downloading update…"))
            }

            // ── Theme / Accent Customizer ──────────────────────────────────
            post("/settings/theme") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val body = runCatching {
                    val raw = call.receiveText()
                    adminJson.decodeFromString<AdminThemeRequest>(raw)
                }.getOrNull() ?: AdminThemeRequest()
                val hex = body.accentHex?.trim().takeUnless { it.isNullOrBlank() } ?: ServerState.globalAccentHex
                val glow = body.accentGlow?.trim().takeUnless { it.isNullOrBlank() } ?: ServerState.globalAccentGlow
                val hover = body.accentHover?.trim().takeUnless { it.isNullOrBlank() } ?: ServerState.globalAccentHover
                val base = body.baseTheme?.trim().takeUnless { it.isNullOrBlank() } ?: ServerState.globalBaseTheme
                StremioServer.saveThemeConfig(hex, glow, hover, base)
                call.respond(AdminActionResult(true, "Theme configuration updated"))
            }

            // ── Authors & Credits ──────────────────────────────────────────
            get("/credits") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                call.respond(StremioServer.getCreditsList())
            }

            post("/credits/save") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val raw = call.receiveText()
                val list = runCatching {
                    adminJson.decodeFromString<List<com.cncverse.stremiobridge.state.AuthorCredit>>(raw)
                }.getOrElse {
                    runCatching {
                        val obj = adminJson.decodeFromString<Map<String, List<com.cncverse.stremiobridge.state.AuthorCredit>>>(raw)
                        obj["credits"]
                    }.getOrNull()
                } ?: return@post call.respond(AdminActionResult(false, "Invalid credits data — nothing was saved"))
                StremioServer.saveCredits(list)
                call.respond(AdminActionResult(true, "Saved ${list.size} author credit(s)"))
            }

            get("/footer-credits") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                call.respond(StremioServer.getFooterCredits())
            }

            post("/footer-credits/save") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val raw = call.receiveText()
                val list = runCatching {
                    adminJson.decodeFromString<List<com.cncverse.stremiobridge.state.FooterCredit>>(raw)
                }.getOrNull() ?: return@post call.respond(AdminActionResult(false, "Invalid footer credits data — nothing was saved"))
                StremioServer.saveFooterCredits(list)
                call.respond(AdminActionResult(true, "Saved ${list.size} footer credit(s)"))
            }

            // ── Stream Cache Management & Link Inspector ──────────────────
            get("/cache/stats") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                call.respond(com.cncverse.stremiobridge.cache.StreamCacheManager.getStats())
            }

            get("/cache/config") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                call.respond(com.cncverse.stremiobridge.cache.StreamCacheManager.config)
            }

            post("/cache/config") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val raw = call.receiveText()
                val newConfig = runCatching {
                    adminJson.decodeFromString<com.cncverse.stremiobridge.cache.StreamCacheConfig>(raw)
                }.getOrNull() ?: return@post call.respond(AdminActionResult(false, "Invalid cache configuration"))
                com.cncverse.stremiobridge.cache.StreamCacheManager.updateConfig(newConfig)
                call.respond(AdminActionResult(true, "Cache configuration saved"))
            }

            post("/cache/purge-expired") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                val purged = com.cncverse.stremiobridge.cache.StreamCacheManager.purgeExpired()
                call.respond(AdminActionResult(true, "Purged $purged expired stream entries"))
            }

            post("/cache/clear") {
                if (!call.checkAdminAuth()) return@post call.respondUnauthorized()
                com.cncverse.stremiobridge.cache.StreamCacheManager.clearAll()
                call.respond(AdminActionResult(true, "Flushed all stream caches"))
            }

            get("/cache/inspect") {
                if (!call.checkAdminAuth()) return@get call.respondUnauthorized()
                val url = call.parameters["url"] ?: ""
                val result = com.cncverse.stremiobridge.cache.StreamCacheManager.inspectLink(url)
                call.respond(result)
            }
        }
    }

    /**
     * Headless servers cannot open an installer window. On Linux, when running
     * as root (systemd/docker) we install the downloaded .deb via dpkg and exit
     * so the service manager restarts into the new version; otherwise we keep
     * the file on disk and log instructions. Non-headless falls back to the
     * platform installer flow.
     */
    private fun installDownloadedUpdate(filePath: String) {
        val os = System.getProperty("os.name", "").lowercase()
        val isRoot = System.getProperty("user.name") == "root"
        if (headlessMode && os.contains("linux") && isRoot && filePath.endsWith(".deb", ignoreCase = true)) {
            ServerState.info("Installing update package: dpkg -i $filePath")
            val ok = runCatching {
                ProcessBuilder("dpkg", "-i", filePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)
            ServerState.info(if (ok) "Update installed — restarting service…" else "dpkg install failed — keeping app running")
            kotlin.system.exitProcess(0)
        } else if (headlessMode) {
            ServerState.info(
                "Update downloaded to: $filePath — install it manually " +
                    "(root with systemd/docker: it will be applied on the next 'Download & Install' when running as root)"
            )
        } else {
            installOtaUpdate(filePath)
        }
    }

    // ── Response builders ─────────────────────────────────────────────────────

    private fun buildSummary(): AdminSummary {
        val status = ServerState.status.value
        val serverInfo = when (status) {
            is ServerStatus.Running -> {
            val displayBase = ServerState.publicBaseUrl.ifBlank { "http://${status.ipAddress}:${status.port}" }
            AdminServerInfo(
                status = "Running",
                port = status.port,
                ipAddress = status.ipAddress,
                pluginCount = status.pluginCount,
                lanUrl = "$displayBase/manifest.json",
                localhostUrl = "$displayBase/manifest.json",
                stremioModeUrl = status.stremioModeStremioUrl,
                disableCatalogsGlobally = ServerState.disableCatalogsGlobally,
            )
        }
            is ServerStatus.Starting -> AdminServerInfo(status = "Starting", message = status.message, disableCatalogsGlobally = ServerState.disableCatalogsGlobally)
            is ServerStatus.Error -> AdminServerInfo(status = "Error", message = status.message, disableCatalogsGlobally = ServerState.disableCatalogsGlobally)
            else -> AdminServerInfo(status = "Stopped", disableCatalogsGlobally = ServerState.disableCatalogsGlobally)
        }

        val repos = RepoState.repos.value.map { repo ->
            AdminRepoInfo(
                url = repo.url,
                name = repo.name.ifBlank { repo.url },
                iconUrl = repo.iconUrl,
                description = repo.description,
                isLoading = repo.isLoading,
                error = repo.error,
                pluginCount = RepoState.availablePlugins.value.count { it.repoEntry.url == repo.url },
                isGlobal = !localOnlyRepos.contains(repo.url),
            )
        }

        val installed = RepoState.installedPlugins.value.map { inst ->
            val loaded = ServerState.globalLoadedPlugins.value.find { it.internalName == inst.internalName }
            val installState = RepoState.getInstallState(inst.internalName)
            AdminInstalledPluginInfo(
                internalName = inst.internalName,
                displayName = inst.displayName,
                version = inst.version,
                iconUrl = inst.iconUrl,
                tvTypes = inst.tvTypes,
                language = inst.language,
                description = inst.description,
                enabled = !StremioServer.isIdGloballyDisabled(inst.internalName),
                apiRegistered = loaded?.apiRegistered ?: false,
                hasSettings = loaded?.hasSettings ?: false,
                updateAvailable = installState is PluginInstallState.UpdateAvailable,
                newVersion = (installState as? PluginInstallState.UpdateAvailable)?.newVersion,
            )
        }

        val release = currentUpdateRelease
        val update = release?.let { rel ->
            rel.assets.find { it.name.endsWith(otaAssetExtension, ignoreCase = true) }?.let { asset ->
                AdminUpdateInfo(
                    tagName = rel.tagName,
                    htmlUrl = rel.htmlUrl,
                    body = rel.body.take(800),
                    assetName = asset.name,
                    assetUrl = asset.downloadUrl,
                    downloadProgress = updateDownloadProgress,
                )
            }
        }

        return AdminSummary(
            headless = headlessMode,
            version = Constants.APP_VERSION,
            platform = System.getProperty("os.name", "?") + " (" + System.getProperty("os.arch", "?") + ")",
            server = serverInfo,
            tunnel = AdminTunnelInfo(
                stremioMode = ServerState.isStremioMode.value,
                activeUrl = ServerState.activeTunnelUrl.value,
                cloudflaredInstalled = CloudflaredManager.isInstalled(),
                downloadProgress = cloudflaredDownloadProgress,
            ),
            repos = repos,
            installedPlugins = installed,
            refreshing = RepoState.isRefreshing.value,
            update = update,
            tokenRequired = adminToken != null,
            themeAccent = ServerState.globalAccentHex,
            themeGlow = ServerState.globalAccentGlow,
            themeHover = ServerState.globalAccentHover,
            baseTheme = ServerState.globalBaseTheme,
            footerCredits = StremioServer.getFooterCredits(),
            cacheStats = com.cncverse.stremiobridge.cache.StreamCacheManager.getStats(),
        )
    }

    private fun buildAvailablePlugins(): List<AdminAvailablePlugin> {
        val installed = RepoState.installedPlugins.value
        return RepoState.availablePlugins.value.map { ap ->
            val inst = installed.find { it.internalName == ap.plugin.internalName }
            val state = RepoState.getInstallState(ap.plugin.internalName)
            val (stateStr, progress, error) = when (state) {
                is PluginInstallState.Installing -> Triple("Installing", state.progress, null)
                is PluginInstallState.UpdateAvailable -> Triple("UpdateAvailable", null, null)
                is PluginInstallState.Failed -> Triple("Failed", null, state.error)
                is PluginInstallState.Installed -> Triple("Installed", null, null)
                else -> Triple("NotInstalled", null, null)
            }
            AdminAvailablePlugin(
                internalName = ap.plugin.internalName,
                displayName = ap.plugin.name,
                version = ap.plugin.version,
                iconUrl = ap.plugin.iconUrl,
                tvTypes = ap.plugin.tvTypes ?: emptyList(),
                language = ap.plugin.language,
                authors = ap.plugin.authors ?: emptyList(),
                description = ap.plugin.description,
                repoUrl = ap.repoEntry.url,
                repoName = ap.repoEntry.name.ifBlank { ap.repoEntry.url },
                installed = inst != null,
                installedVersion = inst?.version,
                updateAvailable = state is PluginInstallState.UpdateAvailable,
                installState = stateStr,
                installProgress = progress,
                error = error,
            )
        }
    }

    private fun buildPluginSettings(internalName: String): AdminPluginSettings {
        val installed = RepoState.installedPlugins.value.find { it.internalName == internalName }
        val displayName = installed?.displayName ?: internalName
        val schemas = PluginSettingsSchemaRegistry.getSettingsForPlugin(internalName, displayName)

        val settings = schemas.sortedWith(SettingsPresentation.settingsComparator).map { schema ->
            val raw = runCatching { CloudStreamApp.getKey<String>(schema.storageKey) }.getOrNull()
            AdminSetting(
                key = schema.key,
                storageKey = schema.storageKey,
                type = schema.type,
                friendlyName = SettingsPresentation.getFriendlyName(schema.key),
                description = SettingsPresentation.getDescription(schema.key),
                category = SettingsPresentation.getCategory(schema.key),
                options = schema.options,
                currentValue = raw,
                defaultSet = SettingsPresentation.stringSetOptions(schema, raw),
                currentSet = SettingsPresentation.parseStoredSet(raw).toList(),
                isBooleanLike = SettingsPresentation.isBooleanLike(schema, raw),
                isDisabledStyle = SettingsPresentation.isDisabledStyleKey(schema),
                defaultValue = schema.defaultValue?.toString(),
            )
        }
        return AdminPluginSettings(internalName, displayName, settings)
    }
}

/**
 * Minimal standalone engine that keeps the web admin panel reachable while the
 * main addon server is stopped (headless mode). Binds the same port the main
 * server used, so the browser URL never changes between stop/start cycles.
 */
/**
 * Top-level mount point for the web admin routes — the member-extension
 * inside [WebAdmin] needs both receivers bound, so this trampoline makes it
 * installable from plain `routing { }` blocks.
 */
fun Route.installWebAdminRoutes() = with(WebAdmin) { adminRoutes() }

object AdminServer {
    private var engine: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    @Volatile private var boundPort: Int? = null

    val isRunning: Boolean get() = engine != null

    fun ensureRunning(preferredPort: Int): Int {
        if (engine != null) return boundPort ?: preferredPort
        val port = findAvailablePort(preferredPort)
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { json(adminJson) }
            routing { installWebAdminRoutes() }
        }.start(wait = false)
        boundPort = port
        ServerState.info("Web admin panel available at http://<host>:$port/admin")
        return port
    }

    fun stop() {
        engine?.stop(0, 1000)
        engine = null
        boundPort = null
    }

    private fun isPortAvailable(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
            true
        }
    } catch (_: Throwable) {
        false
    }

    private fun findAvailablePort(defaultPort: Int): Int {
        for (port in defaultPort..defaultPort + 20) {
            if (isPortAvailable(port)) return port
        }
        ServerSocket(0).use { return it.localPort }
    }
}
