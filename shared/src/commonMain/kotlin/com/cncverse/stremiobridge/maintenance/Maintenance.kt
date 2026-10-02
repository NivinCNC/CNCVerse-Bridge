package com.cncverse.stremiobridge.maintenance

import com.cncverse.stremiobridge.network.geo.GeoRouter
import com.cncverse.stremiobridge.network.geo.NetContext
import com.cncverse.stremiobridge.network.geo.ProxyPool
import com.cncverse.stremiobridge.network.geo.RouteMode
import com.cncverse.stremiobridge.server.BridgeRuntime
import com.cncverse.stremiobridge.server.MainApiWrapper
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.state.HealthStatus
import com.cncverse.stremiobridge.state.RepoState
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.state.StreamTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

// ─────────────────────────────────────────────────────────────────────────────
// Health probe: does an extension still produce links — directly, or only
// through a proxy of its country?
// ─────────────────────────────────────────────────────────────────────────────

object HealthProbe {

    /** [timedOut]: the run hit its time budget — says nothing about whether links exist. */
    class Outcome(val streams: Int, val note: String, val timedOut: Boolean = false)

    private const val MODE_TIMEOUT_MS = 150_000L

    /**
     * One probe run in [mode]: the extension's own home page first (first few
     * items → load → links), falling back to a search when it has none. Uses no
     * caches so a direct failure never short-circuits the proxy run.
     */
    suspend fun probeOnce(api: MainApiWrapper, mode: RouteMode): Outcome =
        withContext(Dispatchers.IO + NetContext.RouteModeElement(mode)) {
            withTimeoutOrNull(MODE_TIMEOUT_MS) { runFlow(api) } ?: Outcome(0, "timed out", timedOut = true)
        }

    private suspend fun runFlow(api: MainApiWrapper): Outcome {
        val type = api.supportedTypes.firstOrNull() ?: "movie"
        val home = runCatching { api.getMainPage(1, type, null) }.getOrDefault(emptyList())
        val candidates = home.distinctBy { it.url }.take(2).ifEmpty {
            runCatching { api.search("Avatar") }.getOrDefault(emptyList()).take(2)
        }
        if (candidates.isEmpty()) return Outcome(0, if (home.isEmpty()) "home page and search empty" else "no items")
        var lastNote = "no links"
        for (item in candidates) {
            val info = runCatching { api.load(item.url) }.getOrNull()
            if (info == null) { lastNote = "load failed for '${item.name.take(40)}'"; continue }
            val dataUrl = info.episodes?.firstOrNull()?.dataUrl ?: info.dataUrl
            val links = runCatching { api.loadLinks(dataUrl) }.getOrDefault(emptyList())
            if (links.isNotEmpty()) return Outcome(links.size, "'${item.name.take(40)}'")
            lastNote = "0 links for '${item.name.take(40)}'"
        }
        return Outcome(0, lastNote)
    }

    /**
     * Direct first; when that finds nothing and the extension has a country,
     * again through that country's proxy pool. Records the outcome and teaches
     * the geo router when the extension only works through a proxy.
     */
    suspend fun probe(api: MainApiWrapper): String {
        val plugin = api.pluginInternalName
        val direct = probeOnce(api, RouteMode.FORCE_DIRECT)
        if (direct.streams > 0) {
            StreamTracker.recordProbe(plugin, api.internalName, api.name, direct.streams, -1, null, "direct: ${direct.note}")
            if (GeoRouter.needsProxy(plugin)) GeoRouter.markPluginNeedsProxy(plugin, false)
            return HealthStatus.WORKING
        }
        val country = GeoRouter.countryFor(plugin)
        if (country == null || !GeoRouter.settings.enabled) {
            // No proxy route exists: a clean "0 links" is the whole evidence; a timeout is none
            StreamTracker.recordProbe(plugin, api.internalName, api.name, if (direct.timedOut) -1 else 0, -1, null,
                "direct: ${direct.note}; no proxy country")
            return if (direct.timedOut) HealthStatus.UNKNOWN else HealthStatus.DEAD
        }
        ProxyPool.pickAwait(country)
        // Datacenter proxies get refused/challenged by the same sites that block this
        // server, so a failed run through them proves nothing — only a residential or
        // private proxy can turn "no links" into DEAD (auto-uninstall depends on it).
        if (ProxyPool.healthy(country).none { it.residential || it.custom }) {
            StreamTracker.recordProbe(plugin, api.internalName, api.name, 0, -2, country,
                "direct: ${direct.note}; no residential $country proxy available to verify")
            return HealthStatus.UNKNOWN
        }
        GeoRouter.resetProbeMisses(plugin)
        val proxied = probeOnce(api, RouteMode.FORCE_PROXY)
        val misses = GeoRouter.probeMisses(plugin)
        val note = "direct: ${direct.note}; $country proxy: ${proxied.note}" +
            (if (misses > 0) " ($misses request(s) found no working proxy)" else "")
        if (proxied.streams > 0) {
            StreamTracker.recordProbe(plugin, api.internalName, api.name, 0, proxied.streams, country, note)
            GeoRouter.markPluginNeedsProxy(plugin, true)
            return HealthStatus.PROXY
        }
        // DEAD needs two clean "no links" answers; a run that timed out proves nothing (-2 = inconclusive)
        if (direct.timedOut || proxied.timedOut || misses > 0) {
            StreamTracker.recordProbe(plugin, api.internalName, api.name, 0, -2, country, "$note (inconclusive)")
            return HealthStatus.UNKNOWN
        }
        StreamTracker.recordProbe(plugin, api.internalName, api.name, 0, 0, country, note)
        return HealthStatus.DEAD
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sweep over all loaded extensions
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class SweepState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val trigger: String = "",
    val working: Int = 0,
    val proxy: Int = 0,
    val dead: Int = 0,
    val unknown: Int = 0,
    val skippedRecent: Int = 0,
)

object HealthSweep {
    @Volatile var state = SweepState()
        private set
    private val mutex = Mutex()

    private const val CONCURRENCY = 3
    private const val RECENT_MS = 24 * 60 * 60_000L

    fun isRunning() = mutex.isLocked

    /**
     * Probes every loaded API. With [onlyStale], APIs that produced links for
     * real users in the last 24 h are skipped (they're evidently working).
     */
    suspend fun run(trigger: String, onlyStale: Boolean = true, only: Set<String>? = null): SweepState {
        if (!mutex.tryLock()) return state
        try {
            val now = System.currentTimeMillis()
            val all = StremioServer.loadedApis.filter { only == null || it.pluginInternalName in only || it.internalName in only }
            val todo = all.filter { api ->
                !onlyStale || (StreamTracker.statOf(api.internalName)?.lastSuccessTime ?: 0L) < now - RECENT_MS
            }
            val skipped = all.size - todo.size
            state = SweepState(running = true, total = todo.size, startedAt = now, trigger = trigger, skippedRecent = skipped)
            ServerState.info("[Health] sweep ($trigger): probing ${todo.size} API(s), $skipped skipped (links in last 24h)")
            val counts = mapOf(
                HealthStatus.WORKING to AtomicInteger(), HealthStatus.PROXY to AtomicInteger(),
                HealthStatus.DEAD to AtomicInteger(), HealthStatus.UNKNOWN to AtomicInteger(),
            )
            val done = AtomicInteger()
            val sem = Semaphore(CONCURRENCY)
            coroutineScope {
                todo.map { api ->
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            val status = runCatching { HealthProbe.probe(api) }
                                .onFailure { ServerState.warn("[Health] probe ${api.name} failed: ${it.message}") }
                                .getOrDefault(HealthStatus.UNKNOWN)
                            counts[status]?.incrementAndGet()
                            state = state.copy(
                                done = done.incrementAndGet(),
                                working = counts.getValue(HealthStatus.WORKING).get(),
                                proxy = counts.getValue(HealthStatus.PROXY).get(),
                                dead = counts.getValue(HealthStatus.DEAD).get(),
                                unknown = counts.getValue(HealthStatus.UNKNOWN).get(),
                            )
                        }
                    }
                }.joinAll()
            }
            state = state.copy(running = false, finishedAt = System.currentTimeMillis())
            StreamTracker.saveNow()
            ServerState.info(
                "[Health] sweep done: ${state.working} working, ${state.proxy} via proxy, " +
                    "${state.dead} dead, ${state.unknown} unknown"
            )
            return state
        } finally {
            if (state.running) state = state.copy(running = false, finishedAt = System.currentTimeMillis())
            mutex.unlock()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Auto-uninstall + nightly maintenance (00:00 IST)
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class AutoUninstallRecord(val plugin: String, val name: String, val at: Long, val reason: String)

@Serializable
data class MaintenanceRun(
    val trigger: String,
    val startedAt: Long,
    val finishedAt: Long = 0,
    val reloaded: Boolean = false,
    val sweep: SweepState? = null,
    val uninstalled: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
private data class MaintenanceFile(
    val autoUninstallDays: Int = 3,
    /** Extensions opted in to auto-uninstall → opt-in time. */
    val autoUninstall: Map<String, Long> = emptyMap(),
    val history: List<AutoUninstallRecord> = emptyList(),
    val lastRun: MaintenanceRun? = null,
)

object Maintenance {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var file: File? = null
    @Volatile private var data = MaintenanceFile()
    private var loopJob: Job? = null
    private val runMutex = Mutex()

    @Volatile var currentStep: String? = null
        private set

    val autoUninstallDays: Int get() = data.autoUninstallDays
    val history: List<AutoUninstallRecord> get() = data.history
    val lastRun: MaintenanceRun? get() = data.lastRun
    fun isRunning() = runMutex.isLocked
    fun optedIn(plugin: String) = data.autoUninstall.containsKey(plugin)
    fun optedInAll(): Map<String, Long> = data.autoUninstall

    fun init(cacheDir: String) {
        if (file != null) return
        val f = File(cacheDir, "maintenance.json")
        file = f
        if (f.exists()) runCatching { data = json.decodeFromString(MaintenanceFile.serializer(), f.readText()) }
            .onFailure { ServerState.warn("Could not read maintenance.json: ${it.message}") }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(MaintenanceFile.serializer(), data))
            java.nio.file.Files.move(
                tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { ServerState.warn("Could not save maintenance.json: ${it.message}") }
    }

    @Synchronized
    fun setAutoUninstallDays(days: Int) {
        data = data.copy(autoUninstallDays = days.coerceIn(1, 90))
        save()
    }

    @Synchronized
    fun setAutoUninstall(plugin: String, enabled: Boolean) {
        val m = data.autoUninstall.toMutableMap()
        if (enabled) m.putIfAbsent(plugin, System.currentTimeMillis()) else m.remove(plugin)
        data = data.copy(autoUninstall = m)
        save()
    }

    /** Next 00:00 in India. */
    fun nextRunAt(now: ZonedDateTime = ZonedDateTime.now(IST)): Long =
        now.toLocalDate().plusDays(1).atStartOfDay(IST).toInstant().toEpochMilli()

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch(Dispatchers.IO) {
            // Persist health stats regularly so a crash loses at most a few minutes
            launch {
                while (isActive) {
                    delay(5 * 60_000L)
                    runCatching { StreamTracker.saveIfDirty() }
                }
            }
            while (isActive) {
                val wait = nextRunAt() - System.currentTimeMillis()
                ServerState.info("[Maintenance] next nightly run in ${wait / 60_000} min (00:00 IST)")
                delay(wait.coerceAtLeast(1_000))
                runCatching { runNow("nightly") }
                    .onFailure { ServerState.warn("[Maintenance] nightly run failed: ${it.message}") }
                delay(60_000) // never run twice in the same minute
            }
        }
    }

    /**
     * Reload all plugins → refresh home pages → health sweep → auto-uninstall.
     * Returns null when a run is already in progress.
     */
    suspend fun runNow(trigger: String, reload: Boolean = true, sweep: Boolean = true): MaintenanceRun? {
        if (!runMutex.tryLock()) return null
        var run = MaintenanceRun(trigger = trigger, startedAt = System.currentTimeMillis())
        try {
            ServerState.info("[Maintenance] run started ($trigger)")
            if (reload) {
                currentStep = "Reloading plugins"
                BridgeRuntime.reloadAllPluginsSafely()
                run = run.copy(reloaded = true)
                currentStep = "Refreshing home pages"
                runCatching { StremioServer.preWarmHomepages() }
                    .onFailure { ServerState.warn("[Maintenance] pre-warm failed: ${it.message}") }
            }
            if (sweep) {
                currentStep = "Health sweep"
                run = run.copy(sweep = HealthSweep.run(trigger, onlyStale = true))
            }
            currentStep = "Auto-uninstall"
            run = run.copy(uninstalled = autoUninstallDead())
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            run = run.copy(error = e.message)
            ServerState.warn("[Maintenance] run error: ${e.message}")
        } finally {
            currentStep = null
            run = run.copy(finishedAt = System.currentTimeMillis())
            synchronized(this) { data = data.copy(lastRun = run); save() }
            runMutex.unlock()
        }
        ServerState.info("[Maintenance] run finished ($trigger): ${run.uninstalled.size} auto-uninstalled")
        return run
    }

    /**
     * Opted-in extensions that are DEAD (probed direct + proxy, no links) and
     * produced nothing for [autoUninstallDays] days — counted from the latest
     * of: last links, first seen, opt-in.
     */
    fun autoUninstallCandidates(now: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        val windowMs = data.autoUninstallDays * 24L * 60 * 60_000L
        val installed = RepoState.installedPlugins.value.associateBy { it.internalName }
        return data.autoUninstall.mapNotNull { (plugin, optedAt) ->
            val ip = installed[plugin] ?: return@mapNotNull null
            val s = StreamTracker.statOf(plugin) ?: return@mapNotNull null
            if (s.status(now) != HealthStatus.DEAD) return@mapNotNull null
            val since = maxOf(s.lastSuccessTime, s.firstSeen, optedAt)
            if (now - since < windowMs) return@mapNotNull null
            val days = (now - since) / (24L * 60 * 60_000L)
            plugin to "no links for $days day(s): ${s.lastProbeNote ?: "probe found nothing"}" + "|" + ip.displayName
        }
    }

    private suspend fun autoUninstallDead(): List<String> {
        val candidates = autoUninstallCandidates()
        if (candidates.isEmpty()) return emptyList()
        val names = candidates.map { it.first }
        ServerState.warn("[Maintenance] auto-uninstalling ${names.size} dead extension(s): ${names.joinToString()}")
        BridgeRuntime.uninstallPlugins(names)
        StreamTracker.forget(names)
        names.forEach { GeoRouter.clearLearned(it) }
        val now = System.currentTimeMillis()
        synchronized(this) {
            val records = candidates.map { (p, r) ->
                AutoUninstallRecord(p, r.substringAfterLast('|'), now, r.substringBeforeLast('|'))
            }
            data = data.copy(
                autoUninstall = data.autoUninstall - names.toSet(),
                history = (records + data.history).take(100),
            )
            save()
        }
        return names
    }
}
