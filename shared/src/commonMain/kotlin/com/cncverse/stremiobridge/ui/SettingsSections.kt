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
import com.cncverse.stremiobridge.server.MediaServer
import com.cncverse.stremiobridge.server.MediaServerPrefs
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.settings.FileTransferHost
import com.cncverse.stremiobridge.settings.SettingsBackup
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
            "Stremio Mode serves the bridge over HTTPS. Leave this empty for a free temporary https link (changes each time it starts), or add your own Cloudflare Tunnel token and domain for a fixed link.",
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

        // ── Quality allotment ──
        Spacer(Modifier.height(10.dp))
        Text("Best quality for this device", color = TextSecondary, fontSize = 11.sp)
        Text("Links above the limit are listed after the ones that fit", color = TextMuted, fontSize = 10.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("" to "No limit", "2160p" to "Up to 4K", "1080p" to "Up to 1080p", "720p" to "Up to 720p", "480p" to "Up to 480p").forEach { (value, label) ->
                FilterChip(
                    selected = p.maxResolution == value,
                    onClick = { update(p.copy(maxResolution = value)) },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Violet600, selectedLabelColor = TextPrimary,
                        containerColor = AmoledCard2, labelColor = TextSecondary,
                    ),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Hide links below", color = TextSecondary, fontSize = 11.sp)
        Text("Links whose resolution is known to be lower are hidden (links of unknown quality stay, listed last)", color = TextMuted, fontSize = 10.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("" to "Show all", "480p" to "480p", "720p" to "720p", "1080p" to "1080p").forEach { (value, label) ->
                FilterChip(
                    selected = p.minResolution == value,
                    onClick = { update(p.copy(minResolution = value)) },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Violet600, selectedLabelColor = TextPrimary,
                        containerColor = AmoledCard2, labelColor = TextSecondary,
                    ),
                )
            }
        }
        SwitchRow("Show results once, after all extensions answered", "Waits for every extension (up to 45 s) and shows the full list at once instead of an early partial list", p.waitForAll) {
            update(p.copy(waitForAll = it))
        }
        SwitchRow("Load the next episode in advance", "While you watch, the links of the next episode are loaded in the background", p.prefetchNext) {
            update(p.copy(prefetchNext = it))
        }
        SwitchRow("Always play the highest resolution of adaptive streams", "Auto-resolution links are labelled with their best resolution and start at it, instead of the player choosing a lower one", p.bestHls) {
            update(p.copy(bestHls = it))
        }
        SwitchRow("Detect the quality of adaptive streams", "Reads HLS playlists to find the real resolution instead of listing them as Auto", p.probeHls) {
            update(p.copy(probeHls = it))
        }
        SwitchRow("List every quality separately", "One entry per rendition (1080p, 720p, ...) plus the adaptive one", p.splitHls) {
            update(p.copy(splitHls = it))
        }
        SwitchRow("Hide streams with unknown quality", "Links whose resolution cannot be found at all", p.hideUnknownQuality) {
            update(p.copy(hideUnknownQuality = it))
        }

        // ── Anime ──
        Spacer(Modifier.height(10.dp))
        Text("Anime audio", color = TextSecondary, fontSize = 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "No preference", "sub" to "Subbed first", "dub" to "Dubbed first").forEach { (value, label) ->
                FilterChip(
                    selected = p.animeAudio == value,
                    onClick = { update(p.copy(animeAudio = value)) },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Violet600, selectedLabelColor = TextPrimary,
                        containerColor = AmoledCard2, labelColor = TextSecondary,
                    ),
                )
            }
        }
        SwitchRow(
            title = "Hide the other audio",
            description = "Only with Subbed / Dubbed first: the other kind is not listed",
            checked = p.animeAudioOnly,
            enabled = p.animeAudio != "all",
            onCheckedChange = { update(p.copy(animeAudioOnly = it)) },
        )

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

// ── Media server ──────────────────────────────────────────────────────────────

private fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.0f MB", b / (1024.0 * 1024))
    else -> "${b / 1024} KB"
}

/** Caches links that need cookies / a referrer on this device and serves them to the player from here. */
@Composable
fun MediaServerCard() {
    var p by remember { mutableStateOf(MediaServer.prefs) }
    var tick by remember { mutableStateOf(0) }
    val stats = remember(tick) { MediaServer.stats() }
    var readAhead by remember { mutableStateOf(p.readAheadMb.toString()) }
    var prefetch by remember { mutableStateOf(p.prefetchSegments.toString()) }
    var maxMb by remember { mutableStateOf(p.maxCacheMb.toString()) }
    var keep by remember { mutableStateOf(p.keepHours.toString()) }
    var message by remember { mutableStateOf<String?>(null) }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle(
            "Media server",
            "Some links only play with a Referer or cookies that your player cannot send. With this on, this device downloads " +
                "the video (or HLS segments) itself with those headers, keeps it on disk, and serves your player from there - seeking works while it downloads.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("${stats.entries}", "Cached", TextPrimary, Modifier.weight(1f))
            StatTile(fmtBytes(stats.bytes), "On disk", Violet300, Modifier.weight(1f))
            StatTile("${stats.activeDownloads}", "Downloading", Green400, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        SwitchRow("Use the media server", "Off: links go to your player exactly as the extension gave them", p.enabled) {
            p = p.copy(enabled = it); message = null
        }
        Text("Which links", color = TextSecondary, fontSize = 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("headers" to "Only links that need headers", "hls" to "Those + all HLS", "all" to "Every link").forEach { (value, label) ->
                FilterChip(
                    selected = p.mode == value,
                    onClick = { p = p.copy(mode = value); message = null },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Violet600, selectedLabelColor = TextPrimary,
                        containerColor = AmoledCard2, labelColor = TextSecondary,
                    ),
                )
            }
        }
        SwitchRow("Video files (mp4, mkv...)", "Download first, then play from this device", p.cacheFiles) { p = p.copy(cacheFiles = it); message = null }
        SwitchRow("HLS streams (m3u8)", "Cache playlists and segments, fetch ahead of the player", p.cacheHls) { p = p.copy(cacheHls = it); message = null }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = readAhead, onValueChange = { v -> readAhead = v.filter { it.isDigit() }.take(6); message = null },
                label = { Text("Read-ahead MB") }, supportingText = { Text("0 = whole file", fontSize = 10.sp) }, singleLine = true,
                modifier = Modifier.weight(1f), colors = amoledFieldColors(),
            )
            OutlinedTextField(
                value = prefetch, onValueChange = { v -> prefetch = v.filter { it.isDigit() }.take(2); message = null },
                label = { Text("HLS prefetch") }, supportingText = { Text("segments", fontSize = 10.sp) }, singleLine = true,
                modifier = Modifier.weight(1f), colors = amoledFieldColors(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = maxMb, onValueChange = { v -> maxMb = v.filter { it.isDigit() }.take(7); message = null },
                label = { Text("Max cache MB") }, supportingText = { Text("up to 2048 (2 GB)", fontSize = 10.sp) }, singleLine = true,
                modifier = Modifier.weight(1f), colors = amoledFieldColors(),
            )
            OutlinedTextField(
                value = keep, onValueChange = { v -> keep = v.filter { it.isDigit() }.take(5); message = null },
                label = { Text("Keep hours") }, singleLine = true,
                modifier = Modifier.weight(1f), colors = amoledFieldColors(),
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Save", primary = true) {
                MediaServer.updatePrefs(
                    p.copy(
                        readAheadMb = readAhead.toIntOrNull() ?: p.readAheadMb,
                        prefetchSegments = prefetch.toIntOrNull() ?: p.prefetchSegments,
                        maxCacheMb = maxMb.toIntOrNull() ?: p.maxCacheMb,
                        keepHours = keep.toIntOrNull() ?: p.keepHours,
                    )
                )
                p = MediaServer.prefs
                message = "Saved - applies to the next stream request"; tick++
            }
            ActionButton("Refresh") { tick++ }
            ActionButton("Clear cache", danger = true) { message = "Deleted ${MediaServer.clearCache()} cached item(s)"; tick++ }
        }
        message?.let { Text(it, color = Green400, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

// ── Upstream add-ons (pencarimovie / Telegram servers) ───────────────────────

/** Other Stremio add-ons (e.g. a pencarimovie Telegram server) whose streams are merged into the results. */
@Composable
fun UpstreamAddonsCard() {
    var items by remember { mutableStateOf(com.cncverse.stremiobridge.server.UpstreamAddons.all()) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle(
            "Upstream add-ons",
            "Paste the link of another Stremio add-on, such as your pencarimovie Telegram server (https://host/manifest.json). " +
                "Its streams are added to every result list; its Telegram files already stream with seeking, so they are passed through as they are.",
        )
        items.forEach { a ->
            SwitchRow(a.name, a.url, a.enabled) {
                com.cncverse.stremiobridge.server.UpstreamAddons.setEnabled(a.url, it)
                items = com.cncverse.stremiobridge.server.UpstreamAddons.all()
            }
            ActionButton("Remove ${a.name}", danger = true) {
                com.cncverse.stremiobridge.server.UpstreamAddons.remove(a.url)
                items = com.cncverse.stremiobridge.server.UpstreamAddons.all()
            }
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it; message = null },
            label = { Text("Name (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), colors = amoledFieldColors(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it; message = null },
            label = { Text("Add-on link") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), colors = amoledFieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        ActionButton("Test and add", primary = true, enabled = url.isNotBlank()) {
            val u = url; val n = name
            message = "Checking..."
            launchBackground {
                val r = com.cncverse.stremiobridge.server.UpstreamAddons.test(u)
                r.onSuccess { found ->
                    com.cncverse.stremiobridge.server.UpstreamAddons.add(n.ifBlank { found }, u)
                    items = com.cncverse.stremiobridge.server.UpstreamAddons.all()
                    url = ""; name = ""
                    message = "Added $found - clear the stream cache to see its links on titles you already opened"
                }.onFailure { message = "Could not reach that add-on: ${it.message}" }
            }
        }
        message?.let { Text(it, color = Green400, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

// ── Backup & import ───────────────────────────────────────────────────────────

/** Saves every setting to one file and restores it on this or another device. */
@Composable
fun BackupCard() {
    val clipboard = LocalClipboardManager.current
    var includeSecrets by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPaste by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }

    fun runImport(text: String) {
        val r = SettingsBackup.import(text)
        if (r.ok) {
            message = r.message; error = null
            if (r.pluginsToInstall.isNotEmpty()) {
                launchBackground {
                    val n = SettingsBackup.reinstall(r.pluginsToInstall)
                    message = r.message + " Reinstalled $n of ${r.pluginsToInstall.size}."
                }
            }
        } else {
            error = r.message; message = null
        }
    }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle(
            "Backup & import",
            "One file with your stream filters, quality and anime options, catalogs, formatter, cache and media-server settings, " +
                "repositories, installed and disabled extensions, and extension settings.",
        )
        SwitchRow(
            "Include logins and tokens",
            "Tunnel token, licence and other extension logins. Keep such a file private.",
            includeSecrets,
        ) { includeSecrets = it }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Save to file", primary = true) {
                val text = SettingsBackup.export(includeSecrets)
                val name = SettingsBackup.suggestedFileName()
                val host = FileTransferHost.saveText
                if (host != null) {
                    host(name, text) { result ->
                        if (result != null) { message = result; error = null } else { message = null; error = "Save cancelled" }
                    }
                } else {
                    try {
                        val f = java.io.File(System.getProperty("user.home") ?: ".", name)
                        f.writeText(text)
                        message = "Saved to ${f.absolutePath}"; error = null
                    } catch (e: Exception) {
                        error = "Could not save: ${e.message}"; message = null
                    }
                }
            }
            ActionButton("Import from file") {
                val host = FileTransferHost.openText
                if (host != null) {
                    host { text -> if (text != null) runImport(text) else { message = null; error = "Import cancelled" } }
                } else {
                    showPaste = true
                }
            }
            ActionButton("Copy backup") {
                clipboard.setText(AnnotatedString(SettingsBackup.export(includeSecrets)))
                message = "Backup copied to the clipboard"; error = null
            }
            ActionButton(if (showPaste) "Hide paste box" else "Paste to import") { showPaste = !showPaste }
        }
        if (showPaste) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pasted, onValueChange = { pasted = it },
                label = { Text("Paste the backup text") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                colors = amoledFieldColors(),
            )
            Spacer(Modifier.height(6.dp))
            ActionButton("Import pasted backup", primary = true, enabled = pasted.isNotBlank()) {
                runImport(pasted); pasted = ""
            }
        }
        message?.let { Text(it, color = Green400, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
        error?.let { Text(it, color = Red400, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
    }
}
