package com.cncverse.stremiobridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cncverse.stremiobridge.cache.StreamCacheConfig
import com.cncverse.stremiobridge.cache.StreamCacheManager
import com.cncverse.stremiobridge.format.StreamFormatter
import com.cncverse.stremiobridge.format.StreamFormatterConfig
import com.cncverse.stremiobridge.format.TemplateException
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.state.ServerStatus
import com.cncverse.stremiobridge.tunnel.CloudflaredManager
import com.cncverse.stremiobridge.tunnel.TunnelSettings


// ── Cloudflare Tunnel ─────────────────────────────────────────────────────────

/** The user's own Cloudflare Tunnel (token + domain) used by Stremio Mode. */
@Composable
fun TunnelSettingsCard() {
    val uriHandler = LocalUriHandler.current
    var token by remember { mutableStateOf(TunnelSettings.token.orEmpty()) }
    var hostname by remember { mutableStateOf(TunnelSettings.hostname.orEmpty()) }
    var showToken by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    val activeUrl by ServerState.activeTunnelUrl.collectAsState()
    val status by ServerState.status.collectAsState()

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle(
            "Cloudflare Tunnel",
            "Stremio Mode serves the bridge over HTTPS through your own Cloudflare Tunnel and domain.",
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim(); saved = null },
            label = { Text("Tunnel token") },
            placeholder = { Text("eyJhIjoi…", color = TextMuted) },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showToken = !showToken }) {
                    Text(if (showToken) "Hide" else "Show", color = Violet400, fontSize = 11.sp)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = amoledFieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = hostname,
            onValueChange = { hostname = it; saved = null },
            label = { Text("Public hostname") },
            placeholder = { Text("bridge.example.com", color = TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = amoledFieldColors(),
        )
        Spacer(Modifier.height(10.dp))
        val valid = token.length > 20 && TunnelSettings.normalizeHostname(hostname).contains('.')
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Save", primary = true, enabled = valid) {
                TunnelSettings.save(token, hostname)
                hostname = TunnelSettings.hostname.orEmpty()
                saved = "Saved — your Stremio URL is ${TunnelSettings.publicUrl}/manifest.json"
                // A running tunnel picks the new token up on restart
                if (ServerState.isStremioMode.value && status is ServerStatus.Running) {
                    CloudflaredManager.stopTunnel()
                    if (!CloudflaredManager.startTunnel((status as ServerStatus.Running).port)) ServerState.isStremioMode.value = false
                }
            }
            if (TunnelSettings.isConfigured) {
                ActionButton("Remove", danger = true) {
                    CloudflaredManager.stopTunnel()
                    ServerState.isStremioMode.value = false
                    TunnelSettings.save("", "")
                    token = ""; hostname = ""
                    saved = "Tunnel removed"
                }
            }
        }
        saved?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Green400, fontSize = 11.sp) }
        if (!activeUrl.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Green400, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("Connected: $activeUrl", color = Green400, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = DividerColor)
        Spacer(Modifier.height(8.dp))
        Text("How to set it up", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val port = (status as? ServerStatus.Running)?.port ?: 8080
        listOf(
            "1. Add your domain to Cloudflare (free plan is enough).",
            "2. Cloudflare dashboard → Zero Trust → Networks → Tunnels → Create a tunnel (Cloudflared).",
            "3. Copy the token from the install command (the long text after --token) and paste it above.",
            "4. Under Public Hostname add e.g. bridge.yourdomain.com → service HTTP, URL localhost:$port.",
            "5. Enter that hostname above, save, then turn on Stremio Mode on the Server tab.",
        ).forEach { Text(it, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp) }
        TextButton(onClick = { uriHandler.openUri("https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/get-started/create-remote-tunnel/") }) {
            Text("Cloudflare guide", color = Violet400, fontSize = 12.sp)
        }
    }
}

// ── Stream formatter ──────────────────────────────────────────────────────────

/** Templates that rewrite every stream's name and description. */
@Composable
fun FormatterCard() {
    val cfg = StreamFormatter.config
    var enabled by remember { mutableStateOf(cfg.enabled) }
    var nameTpl by remember { mutableStateOf(cfg.nameTemplate.ifBlank { StreamFormatter.PRESET_NAME }) }
    var descTpl by remember { mutableStateOf(cfg.descriptionTemplate.ifBlank { StreamFormatter.PRESET_DESCRIPTION }) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle("Stream formatter", "Rewrites how stream names and descriptions look in Stremio / Nuvio.")
        SwitchRow(title = "Use custom format", checked = enabled, onCheckedChange = { enabled = it; result = null })
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide templates" else "Edit templates", color = Violet400, fontSize = 12.sp)
        }
        if (expanded) {
            Text("Presets", color = TextSecondary, fontSize = 11.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StreamFormatter.PRESETS.forEach { p ->
                    ActionButton(p.title) { nameTpl = p.nameTemplate; descTpl = p.descriptionTemplate; result = null }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = nameTpl, onValueChange = { nameTpl = it; result = null },
                label = { Text("Name template") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                colors = amoledFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = descTpl, onValueChange = { descTpl = it; result = null },
                label = { Text("Description template") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                colors = amoledFieldColors(),
            )
            Text("Variables: " + com.cncverse.stremiobridge.format.StreamVariables.NAMES.joinToString(", "), color = TextMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Preview") {
                try {
                    val (n, d) = StreamFormatter.validate(StreamFormatterConfig(true, nameTpl, descTpl))
                    val labels = listOf("4K movie (direct file)", "Series episode (HLS)", "Live channel (minimal data)")
                    preview = StreamFormatter.samples().mapIndexed { i, (stream, ctx) ->
                        val out = StreamFormatter.format(stream, ctx, n, d)
                        labels.getOrElse(i) { "Sample" } to (out.name.orEmpty() + "\n" + out.title.orEmpty())
                    }
                    error = null
                } catch (e: TemplateException) {
                    error = e.message; preview = emptyList()
                }
            }
            ActionButton("Save", primary = true) {
                try {
                    StreamFormatter.save(StreamFormatterConfig(enabled, nameTpl, descTpl))
                    result = if (enabled) "Saved — formatter on" else "Saved (formatter off)"
                    error = null
                } catch (e: TemplateException) {
                    error = e.message
                }
            }
        }
        result?.let { Text(it, color = Green400, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        error?.let { Text(it, color = Red400, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        preview.forEach { (label, text) ->
            Spacer(Modifier.height(6.dp))
            Text(label, color = TextMuted, fontSize = 10.sp)
            Text(
                text, color = TextPrimary, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().background(AmoledCard2, RoundedCornerShape(8.dp)).padding(8.dp),
            )
        }
    }
}

// ── Stream cache ──────────────────────────────────────────────────────────────

@Composable
fun StreamCacheCard() {
    var tick by remember { mutableStateOf(0) }
    val stats = remember(tick) { StreamCacheManager.getStats() }
    var cfg by remember { mutableStateOf(StreamCacheManager.config) }
    var ttl by remember { mutableStateOf(cfg.defaultTtlMinutes.toString()) }
    var maxEntries by remember { mutableStateOf(cfg.maxRamEntries.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var inspectUrl by remember { mutableStateOf("") }
    var inspectResult by remember { mutableStateOf<String?>(null) }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle("Stream cache", "Remembers found links so repeat requests answer instantly.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("${stats.hitRate}%", "Hit rate", Green400, Modifier.weight(1f))
            StatTile("${stats.activeRamEntries}", "Cached", TextPrimary, Modifier.weight(1f))
            StatTile("${stats.requestsSaved}", "Saved", Violet300, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        SwitchRow("Cache streams", null, cfg.enabled) { cfg = cfg.copy(enabled = it) }
        SwitchRow("Share in-flight lookups", "Concurrent requests for the same title wait for one lookup", cfg.singleFlightEnabled) {
            cfg = cfg.copy(singleFlightEnabled = it)
        }
        SwitchRow("Keep across restarts", null, cfg.diskPersistenceEnabled) { cfg = cfg.copy(diskPersistenceEnabled = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ttl, onValueChange = { v -> ttl = v.filter { it.isDigit() }.take(5) },
                label = { Text("Default TTL (min)") }, singleLine = true,
                modifier = Modifier.weight(1f), colors = amoledFieldColors(),
            )
            OutlinedTextField(
                value = maxEntries, onValueChange = { v -> maxEntries = v.filter { it.isDigit() }.take(6) },
                label = { Text("Max entries") }, singleLine = true,
                modifier = Modifier.weight(1f), colors = amoledFieldColors(),
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Save", primary = true) {
                val newCfg: StreamCacheConfig = cfg.copy(
                    defaultTtlMinutes = ttl.toLongOrNull()?.coerceAtLeast(1) ?: cfg.defaultTtlMinutes,
                    maxRamEntries = maxEntries.toIntOrNull()?.coerceAtLeast(10) ?: cfg.maxRamEntries,
                )
                StreamCacheManager.updateConfig(newCfg)
                cfg = StreamCacheManager.config
                message = "Cache settings saved"; tick++
            }
            ActionButton("Purge expired") { message = "Purged ${StreamCacheManager.purgeExpired()} expired entries"; tick++ }
            ActionButton("Clear all", danger = true) { StreamCacheManager.clearAll(); message = "Cache cleared"; tick++ }
        }
        message?.let { Text(it, color = Violet300, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(10.dp))
        Text("Link inspector", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = inspectUrl, onValueChange = { inspectUrl = it; inspectResult = null },
                placeholder = { Text("Paste a stream URL", color = TextMuted) }, singleLine = true,
                modifier = Modifier.weight(1f), colors = amoledFieldColors(),
            )
            Spacer(Modifier.width(8.dp))
            ActionButton("Inspect", enabled = inspectUrl.startsWith("http")) {
                val r = StreamCacheManager.inspectLink(inspectUrl.trim())
                inspectResult = "${r.category} · " + (if (r.isSigned) "signed" else "unsigned") +
                    (r.remainingValidityMs?.let { " · valid ${it / 60_000} more min" } ?: "") +
                    " · cache ${r.computedTtlMs / 60_000} min" + (if (!r.isCacheable) " (not cached)" else "") +
                    "\n${r.reason}"
            }
        }
        inspectResult?.let { Text(it, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

// ── Streams: filters & sorting ────────────────────────────────────────────────

private val RESOLUTIONS = listOf("2160p", "1080p", "720p", "480p", "360p")

/** How stream lists are filtered and ordered (applied to every request). */
@Composable
fun StreamsCard() {
    var p by remember { mutableStateOf(StremioServer.streamPrefs) }
    var minGb by remember { mutableStateOf(p.minSizeGb.takeIf { it > 0 }?.toString().orEmpty()) }
    var maxGb by remember { mutableStateOf(p.maxSizeGb.takeIf { it > 0 }?.toString().orEmpty()) }
    var maxPer by remember { mutableStateOf(p.maxStreamsPerResolution.takeIf { it > 0 }?.toString().orEmpty()) }
    var showOrder by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    fun update(n: StremioServer.StreamPrefs) { p = n; saved = null }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle("Streams", "Which links Stremio / Nuvio get, and in what order.")

        Text("Resolutions", color = TextSecondary, fontSize = 11.sp)
        Text("None selected = all", color = TextMuted, fontSize = 10.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RESOLUTIONS.forEach { r ->
                val on = r in p.allowedResolutions
                FilterChip(
                    selected = on,
                    onClick = { update(p.copy(allowedResolutions = if (on) p.allowedResolutions - r else p.allowedResolutions + r)) },
                    label = { Text(r, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Violet600, selectedLabelColor = TextPrimary,
                        containerColor = AmoledCard2, labelColor = TextSecondary,
                    ),
                )
            }
        }
        SwitchRow("Hide CAM / TeleSync", "Drops cinema recordings and screeners", p.excludeCam) { update(p.copy(excludeCam = it)) }
        SwitchRow("Hide non-seekable / non-playable files", "Direct files whose server can't seek, and archives", p.filterNonSeekable) {
            update(p.copy(filterNonSeekable = it))
        }
        SwitchRow("Hide subtitles", "Streams go out without subtitle tracks", p.hideSubtitles) { update(p.copy(hideSubtitles = it)) }
        SwitchRow("Show \"support the project\" entry", "One donate link on top of stream lists", p.showSupport) { update(p.copy(showSupport = it)) }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = maxPer, onValueChange = { v -> maxPer = v.filter { it.isDigit() }.take(3); saved = null },
                label = { Text("Max per quality") }, placeholder = { Text("no limit", color = TextMuted) }, singleLine = true,
                modifier = Modifier.weight(1f), colors = amoledFieldColors(),
            )
            OutlinedTextField(
                value = minGb, onValueChange = { v -> minGb = v.filter { it.isDigit() || it == '.' }.take(6); saved = null },
                label = { Text("Min GB") }, singleLine = true,
                modifier = Modifier.weight(1f), colors = amoledFieldColors(),
            )
            OutlinedTextField(
                value = maxGb, onValueChange = { v -> maxGb = v.filter { it.isDigit() || it == '.' }.take(6); saved = null },
                label = { Text("Max GB") }, singleLine = true,
                modifier = Modifier.weight(1f), colors = amoledFieldColors(),
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("Sort", color = TextSecondary, fontSize = 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Triple("default", "default", "Best quality first"),
                Triple("default", "size", "Quality, then biggest file"),
                Triple("provider", "default", "By extension"),
                Triple("provider", "size", "By extension, biggest first"),
            ).forEach { (group, sort, label) ->
                FilterChip(
                    selected = p.groupBy == group && p.sortBy == sort,
                    onClick = { update(p.copy(groupBy = group, sortBy = sort)) },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Violet600, selectedLabelColor = TextPrimary,
                        containerColor = AmoledCard2, labelColor = TextSecondary,
                    ),
                )
            }
        }

        // Extension priority: earlier ones come first inside each group
        TextButton(onClick = { showOrder = !showOrder }) {
            Text(
                if (showOrder) "Hide extension priority" else "Extension priority (${p.providerOrder.size} set)",
                color = Violet400, fontSize = 12.sp,
            )
        }
        if (showOrder) {
            val names = remember(p.providerOrder) {
                val loaded = StremioServer.loadedApis.filter { !StremioServer.isGloballyDisabled(it) }.map { it.name }.distinct()
                p.providerOrder.filter { it in loaded } + loaded.filter { it !in p.providerOrder }.sorted()
            }
            Text("Move extensions up to have their links listed first.", color = TextMuted, fontSize = 10.sp)
            names.forEachIndexed { i, name ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text("${i + 1}.", color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(28.dp))
                    Text(name, color = TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(enabled = i > 0, onClick = {
                        val l = names.toMutableList(); l.add(i - 1, l.removeAt(i)); update(p.copy(providerOrder = l))
                    }) { Text("Up", fontSize = 11.sp) }
                    TextButton(enabled = i < names.size - 1, onClick = {
                        val l = names.toMutableList(); l.add(i + 1, l.removeAt(i)); update(p.copy(providerOrder = l))
                    }) { Text("Down", fontSize = 11.sp) }
                }
            }
            if (p.providerOrder.isNotEmpty()) {
                TextButton(onClick = { update(p.copy(providerOrder = emptyList())) }) { Text("Reset priority", color = Red400, fontSize = 11.sp) }
            }
        }

        Spacer(Modifier.height(8.dp))
        ActionButton("Save", primary = true) {
            StremioServer.updateStreamPrefs(
                p.copy(
                    maxStreamsPerResolution = maxPer.toIntOrNull() ?: 0,
                    minSizeGb = minGb.toDoubleOrNull() ?: 0.0,
                    maxSizeGb = maxGb.toDoubleOrNull() ?: 0.0,
                )
            )
            p = StremioServer.streamPrefs
            saved = "Saved — applies to the next stream request"
        }
        saved?.let { Text(it, color = Green400, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

// ── Catalogs ──────────────────────────────────────────────────────────────────

/** Which catalogs (home-page rows) the addon offers. */
@Composable
fun CatalogsCard() {
    var catalogsOff by remember { mutableStateOf(ServerState.disableCatalogsGlobally) }
    var disabled by remember { mutableStateOf(StremioServer.streamPrefs.disabledCatalogs) }
    var expanded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }

    fun save(set: Set<String>) {
        disabled = set
        StremioServer.updateStreamPrefs(StremioServer.streamPrefs.copy(disabledCatalogs = set))
    }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle("Catalogs", "Home-page rows shown in Stremio / Nuvio. Genres come from each extension's home page.")
        SwitchRow(
            title = "Hide all catalogs",
            description = "Streams and search only",
            checked = catalogsOff,
            onCheckedChange = {
                catalogsOff = it
                ServerState.disableCatalogsGlobally = it
                StremioServer.saveGlobalCatalogSetting()
            },
        )
        if (!catalogsOff) {
            val all = remember(expanded) { StremioServer.allCatalogs() }
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) "Hide catalog list" else "Choose catalogs (${all.count { it.first !in disabled }} of ${all.size} on)",
                    color = Violet400, fontSize = 12.sp,
                )
            }
            if (expanded) {
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it },
                    placeholder = { Text("Filter catalogs", color = TextMuted) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = amoledFieldColors(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    ActionButton("All on") { save(emptySet()) }
                    ActionButton("All off") { save(all.map { it.first }.toSet()) }
                }
                all.filter { filter.isBlank() || it.second.contains(filter, ignoreCase = true) }.forEach { (key, name) ->
                    SwitchRow(title = name, checked = key !in disabled, onCheckedChange = { on ->
                        save(if (on) disabled - key else disabled + key)
                    })
                }
            }
        }
    }
}
