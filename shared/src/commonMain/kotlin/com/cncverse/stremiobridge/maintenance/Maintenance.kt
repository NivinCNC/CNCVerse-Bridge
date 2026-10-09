package com.cncverse.stremiobridge.maintenance

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
// Health probe: does an extension still produce links?
// ─────────────────────────────────────────────────────────────────────────────

object HealthProbe {

    /** [timedOut]: the run hit its time budget — says nothing about whether links exist. */
    class Outcome(val streams: Int, val note: String, val timedOut: Boolean = false)

    private const val MODE_TIMEOUT_MS = 150_000L

    /**
     * One probe run: the extension's own home page first (first few
     * items → load → links), falling back to a search when it has none. Uses no
     * caches, so the answer reflects the site right now.
     */
    suspend fun probeOnce(api: MainApiWrapper): Outcome =
        withContext(Dispatchers.IO) {
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
     * Probes [api] once and records the outcome: links → working, a clean
     * "no links" → dead, a timeout → unknown (proves nothing).
     */
    suspend fun probe(api: MainApiWrapper): String {
        val plugin = api.pluginInternalName
        val run = probeOnce(api)
        // A clean "0 links" is the evidence for DEAD; a run that timed out proves nothing
        StreamTracker.recordProbe(plugin, api.internalName, api.name, if (run.timedOut) -1 else run.streams, -1, null, run.note)
        return when {
            run.streams > 0 -> HealthStatus.WORKING
            run.timedOut -> HealthStatus.UNKNOWN
            else -> HealthStatus.DEAD
        }
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
                "[Health] sweep done: ${state.working} working, " +
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
    /** Apply auto-uninstall to every installed extension, not only the opted-in ones. */
    val autoUninstallAll: Boolean = false,
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
    val autoUninstallAll: Boolean get() = data.autoUninstallAll
    fun optedIn(plugin: String) = data.autoUninstallAll || data.autoUninstall.containsKey(plugin)
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
    fun setAutoUninstallAll(enabled: Boolean) {
        data = data.copy(autoUninstallAll = enabled)
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
     * Opted-in extensions that are DEAD (probed, no links) and
     * produced nothing for [autoUninstallDays] days — counted from the latest
     * of: last links, first seen, opt-in.
     */
    fun autoUninstallCandidates(now: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        val windowMs = data.autoUninstallDays * 24L * 60 * 60_000L
        val installed = RepoState.installedPlugins.value.associateBy { it.internalName }
        // "All installed" covers every extension; opt-in time then only counts where one was set
        val scope: Map<String, Long> = if (data.autoUninstallAll)
            installed.keys.associateWith { data.autoUninstall[it] ?: 0L } else data.autoUninstall
        return scope.mapNotNull { (plugin, optedAt) ->
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

// ─────────────────────────────────────────────────────────────────────────────
// Home-page audit: uninstall extensions whose home page no longer loads.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class HomeAuditRow(
    val plugin: String,
    val api: String,
    val verdict: String,   // ok | dead | inconclusive | skipped
    val note: String,
)

@Serializable
data class HomeAuditState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val uninstall: Boolean = false,
    val deadPlugins: List<String> = emptyList(),
    val uninstalled: List<String> = emptyList(),
    val rows: List<HomeAuditRow> = emptyList(),
)

object HomePageAudit {
    @Volatile var state = HomeAuditState()
        private set
    private val mutex = Mutex()
    private const val TIMEOUT_MS = 60_000L

    fun isRunning() = mutex.isLocked

    private suspend fun homeItems(api: MainApiWrapper): Pair<Int, Boolean> =
        withContext(Dispatchers.IO) {
            val type = api.supportedTypes.firstOrNull() ?: "movie"
            val r = withTimeoutOrNull(TIMEOUT_MS) { runCatching { api.getMainPage(1, type, null) }.getOrDefault(emptyList()) }
            if (r == null) 0 to true else r.size to false
        }

    private suspend fun check(api: MainApiWrapper): HomeAuditRow {
        val plugin = api.pluginInternalName
        if (!api.hasHomePage) return HomeAuditRow(plugin, api.name, "skipped", "search-only (no home page)")
        // Loaded fine recently (pre-warm / users) — no need to fetch it again
        val cached = StremioServer.cachedHomeItems(api)
        if (cached > 0) return HomeAuditRow(plugin, api.name, "ok", "$cached items (cached home page)")
        val (items, timedOut) = homeItems(api)
        if (items > 0) return HomeAuditRow(plugin, api.name, "ok", "$items items")
        if (timedOut) return HomeAuditRow(plugin, api.name, "inconclusive", "home page timed out")
        return HomeAuditRow(plugin, api.name, "dead", "home page empty")
    }

    /** Audits every loaded API; with [uninstall], removes plugins whose every home page is dead. */
    suspend fun run(uninstall: Boolean): HomeAuditState {
        if (!mutex.tryLock()) return state
        try {
            val apis = StremioServer.loadedApis.toList()
            state = HomeAuditState(running = true, total = apis.size, startedAt = System.currentTimeMillis(), uninstall = uninstall)
            ServerState.info("[HomeAudit] checking ${apis.size} API(s)")
            val rows = java.util.concurrent.ConcurrentLinkedQueue<HomeAuditRow>()
            val done = AtomicInteger()
            val sem = Semaphore(4)
            coroutineScope {
                apis.map { api ->
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            val row = runCatching { check(api) }
                                .getOrElse { HomeAuditRow(api.pluginInternalName, api.name, "inconclusive", "error: ${it.message}") }
                            rows += row
                            state = state.copy(done = done.incrementAndGet())
                        }
                    }
                }.joinAll()
            }
            // A plugin goes only if every home-page API it has is dead (none ok / inconclusive)
            val byPlugin = rows.groupBy { it.plugin }
            val dead = byPlugin.filter { (_, r) ->
                val relevant = r.filter { it.verdict != "skipped" }
                relevant.isNotEmpty() && relevant.all { it.verdict == "dead" }
            }.keys.sorted()
            state = state.copy(deadPlugins = dead, rows = rows.sortedWith(compareBy({ it.verdict }, { it.plugin })))
            if (uninstall && dead.isNotEmpty()) {
                ServerState.warn("[HomeAudit] uninstalling ${dead.size} extension(s) with a dead home page: ${dead.joinToString()}")
                BridgeRuntime.uninstallPlugins(dead)
                StreamTracker.forget(dead)
                state = state.copy(uninstalled = dead)
            }
            state = state.copy(running = false, finishedAt = System.currentTimeMillis())
            ServerState.info("[HomeAudit] done: ${rows.count { it.verdict == "ok" }} ok, ${rows.count { it.verdict == "dead" }} dead APIs, " +
                "${rows.count { it.verdict == "inconclusive" }} inconclusive; ${dead.size} plugin(s) " + if (uninstall) "uninstalled" else "would be uninstalled")
            return state
        } finally {
            if (state.running) state = state.copy(running = false, finishedAt = System.currentTimeMillis())
            mutex.unlock()
        }
    }
}
