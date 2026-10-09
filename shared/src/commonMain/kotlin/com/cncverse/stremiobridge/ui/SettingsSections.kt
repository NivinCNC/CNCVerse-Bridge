package com.cncverse.stremiobridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.cncverse.stremiobridge.state.AuthorCredit
import com.cncverse.stremiobridge.state.FooterCredit
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.state.ServerStatus
import com.cncverse.stremiobridge.tunnel.CloudflaredManager
import com.cncverse.stremiobridge.tunnel.TunnelSettings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val prettyJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

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

// ── Catalogs & profiles ───────────────────────────────────────────────────────

@Composable
fun AddonOptionsCard() {
    var catalogsOff by remember { mutableStateOf(ServerState.disableCatalogsGlobally) }
    var message by remember { mutableStateOf<String?>(null) }
    val profiles = remember(message) { StremioServer.profiles.entries.sortedByDescending { it.value.lastSeen }.take(50) }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle("Addon", "What every Stremio / Nuvio install of this bridge gets.")
        SwitchRow(
            title = "Hide catalogs",
            description = "Streams and search only — no home-page rows in the apps",
            checked = catalogsOff,
            onCheckedChange = {
                catalogsOff = it
                ServerState.disableCatalogsGlobally = it
                StremioServer.saveGlobalCatalogSetting()
            },
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = DividerColor)
        Spacer(Modifier.height(8.dp))
        Text("Profiles (${StremioServer.profiles.size})", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Each user's configure page creates a profile. A profile can use at most ${StremioServer.MAX_PROFILE_EXTENSIONS} extensions.",
            color = TextMuted, fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        ActionButton("Trim profiles over the limit") {
            val n = StremioServer.enforceProfileExtensionLimit()
            message = "Trimmed $n profile(s) to ${StremioServer.MAX_PROFILE_EXTENSIONS} extensions"
        }
        message?.let { Text(it, color = Violet300, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        if (profiles.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            profiles.forEach { (id, p) ->
                Text(
                    "${p.displayName.ifBlank { id }} · seen ${agoText(p.lastSeen)} · ${p.enabledOverrides.size} on / ${p.disabled.size} off",
                    color = TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
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

// ── User page look & credits ──────────────────────────────────────────────────

/** Accent colour and base theme of the users' configure page, plus the credits it shows. */
@Composable
fun UserPageCard() {
    var accent by remember { mutableStateOf(ServerState.globalAccentHex) }
    var baseTheme by remember { mutableStateOf(ServerState.globalBaseTheme) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var creditsText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val themes = listOf("slate", "charcoal", "navy", "forest", "oled", "light")
    val swatches = listOf("#8b5cf6", "#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#ec4899", "#14b8a6", "#6366f1")

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle("User page", "Look of the configure page your users open, and the credits it shows.")
        Text("Accent colour", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            swatches.forEach { hex ->
                val c = parseHexColor(hex)
                Box(
                    Modifier.size(if (accent.equals(hex, true)) 28.dp else 22.dp)
                        .background(c, CircleShape)
                        .clickable { accent = hex },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = accent, onValueChange = { accent = it.trim() },
            label = { Text("Accent hex") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), colors = amoledFieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        Text("Base theme", color = TextSecondary, fontSize = 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            themes.forEach { t ->
                FilterChip(
                    selected = baseTheme == t, onClick = { baseTheme = t },
                    label = { Text(t, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Violet600, selectedLabelColor = TextPrimary,
                        containerColor = AmoledCard2, labelColor = TextSecondary,
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ActionButton("Save look", primary = true, enabled = Regex("^#[0-9a-fA-F]{6}$").matches(accent)) {
            val c = parseHexColor(accent)
            val glow = "rgba(${(c.red * 255).toInt()}, ${(c.green * 255).toInt()}, ${(c.blue * 255).toInt()}, 0.28)"
            val hover = "#" + listOf(c.red, c.green, c.blue).joinToString("") { ((it * 0.85f) * 255).toInt().toString(16).padStart(2, '0') }
            StremioServer.saveThemeConfig(accent, glow, hover, baseTheme)
            message = "User page look saved"
        }
        message?.let { Text(it, color = Green400, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = DividerColor)
        Spacer(Modifier.height(8.dp))
        Text("Credits", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "${StremioServer.getCreditsList().size} author credit(s) · ${StremioServer.getFooterCredits().size} footer link(s). Edited as JSON.",
            color = TextMuted, fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Edit author credits") {
                editing = "authors"; error = null
                creditsText = prettyJson.encodeToString(ListSerializer(AuthorCredit.serializer()), StremioServer.getCreditsList())
            }
            ActionButton("Edit footer links") {
                editing = "footer"; error = null
                creditsText = prettyJson.encodeToString(ListSerializer(FooterCredit.serializer()), StremioServer.getFooterCredits())
            }
        }
        if (editing != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = creditsText, onValueChange = { creditsText = it; error = null },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                colors = amoledFieldColors(),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Save", primary = true) {
                    runCatching {
                        if (editing == "authors") {
                            val list = prettyJson.decodeFromString(ListSerializer(AuthorCredit.serializer()), creditsText)
                            StremioServer.saveCredits(list); "Saved ${list.size} author credit(s)"
                        } else {
                            val list = prettyJson.decodeFromString(ListSerializer(FooterCredit.serializer()), creditsText)
                            StremioServer.saveFooterCredits(list); "Saved ${list.size} footer link(s)"
                        }
                    }.onSuccess { message = it; editing = null }
                        .onFailure { error = "Invalid JSON — nothing saved (${it.message?.take(120)})" }
                }
                ActionButton("Cancel") { editing = null }
            }
            error?.let { Text(it, color = Red400, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

private fun parseHexColor(hex: String): Color = runCatching {
    Color(("ff" + hex.removePrefix("#")).toLong(16))
}.getOrDefault(Violet500)
