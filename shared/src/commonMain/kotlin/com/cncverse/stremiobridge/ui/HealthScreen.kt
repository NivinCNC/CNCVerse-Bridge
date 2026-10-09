package com.cncverse.stremiobridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cncverse.stremiobridge.maintenance.HealthProbe
import com.cncverse.stremiobridge.maintenance.HealthSweep
import com.cncverse.stremiobridge.maintenance.HomePageAudit
import com.cncverse.stremiobridge.maintenance.Maintenance
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.state.HealthStatus
import com.cncverse.stremiobridge.state.PluginStreamHealth
import com.cncverse.stremiobridge.state.RepoState
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.state.StreamTracker
import kotlinx.coroutines.delay

private enum class HealthFilter(val label: String) { All("All"), Working("Working"), Dead("Dead"), Unknown("Unknown") }

/**
 * Stream health of every installed extension (live links seen + probes), plus
 * nightly maintenance, auto-uninstall and the home-page audit.
 */
@Composable
fun HealthScreen() {
    val installed by RepoState.installedPlugins.collectAsState()
    // Tracker / maintenance state is plain objects: re-read every couple of seconds
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }

    var filter by remember { mutableStateOf(HealthFilter.All) }
    var probing by remember { mutableStateOf(setOf<String>()) }
    var message by remember { mutableStateOf<String?>(null) }

    val rows: List<PluginStreamHealth> = remember(installed, tick, probing) {
        installed.map { p ->
            StreamTracker.getHealth(p.internalName, p.displayName, !StremioServer.isIdGloballyDisabled(p.internalName), p.iconUrl)
        }.sortedBy { it.pluginName.lowercase() }
    }
    val counts = rows.groupingBy { if (it.status == HealthStatus.PROXY) HealthStatus.WORKING else it.status }.eachCount()
    val shown = rows.filter {
        when (filter) {
            HealthFilter.All -> true
            HealthFilter.Working -> it.status == HealthStatus.WORKING || it.status == HealthStatus.PROXY
            HealthFilter.Dead -> it.status == HealthStatus.DEAD
            HealthFilter.Unknown -> it.status == HealthStatus.UNKNOWN
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AmoledBlack).systemBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Health", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("Which extensions still return links", color = TextMuted, fontSize = 11.sp)
        }

        item {
            AmoledCard(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("${counts[HealthStatus.WORKING] ?: 0}", "Working", Green400, Modifier.weight(1f))
                    StatTile("${counts[HealthStatus.DEAD] ?: 0}", "Dead", Red400, Modifier.weight(1f))
                    StatTile("${counts[HealthStatus.UNKNOWN] ?: 0}", "Unknown", TextMuted, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                val sweep = HealthSweep.state
                if (HealthSweep.isRunning()) {
                    Text("Checking extensions… ${sweep.done}/${sweep.total}", color = Amber400, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (sweep.total > 0) sweep.done.toFloat() / sweep.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Violet500,
                        trackColor = AmoledCard2,
                    )
                    Spacer(Modifier.height(8.dp))
                } else if (sweep.finishedAt > 0) {
                    Text(
                        "Last check ${agoText(sweep.finishedAt)}: ${sweep.working} working, ${sweep.dead} dead, ${sweep.unknown} unknown",
                        color = TextSecondary, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Check all", primary = true, enabled = !HealthSweep.isRunning()) {
                        launchBackground { HealthSweep.run("app", onlyStale = false) }
                        message = "Checking every extension in the background…"
                    }
                    ActionButton("Disable dead", enabled = (counts[HealthStatus.DEAD] ?: 0) > 0) {
                        val dead = rows.filter { it.status == HealthStatus.DEAD && it.enabled }
                        dead.forEach { StremioServer.setPluginDisabled(it.internalName, true) }
                        if (dead.isNotEmpty()) StremioServer.saveDisabledPlugins()
                        message = "Disabled ${dead.size} dead extension(s)"
                        tick++
                    }
                    ActionButton("Reset stats", danger = true) {
                        StreamTracker.clear()
                        message = "Stream statistics reset"
                        tick++
                    }
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Violet300, fontSize = 11.sp)
                }
            }
        }

        item { MaintenanceCard(tick) }
        item { HomeAuditCard(tick) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Violet600,
                            selectedLabelColor = TextPrimary,
                            containerColor = AmoledCard,
                            labelColor = TextSecondary,
                        ),
                    )
                }
            }
        }

        if (shown.isEmpty()) {
            item { Text("No extensions here", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
        }
        items(shown, key = { it.internalName }) { h ->
            HealthRow(
                health = h,
                probing = h.internalName in probing,
                autoUninstall = Maintenance.optedIn(h.internalName),
                onProbe = {
                    probing = probing + h.internalName
                    launchBackground {
                        val apis = StremioServer.loadedApis.filter { it.pluginInternalName == h.internalName }
                        if (apis.isEmpty()) ServerState.warn("${h.pluginName} is not loaded — nothing to check")
                        apis.forEach { api ->
                            val status = HealthProbe.probe(api)
                            ServerState.info("Health check [${api.name}]: $status — ${StreamTracker.statOf(api.internalName)?.lastProbeNote ?: ""}")
                        }
                        probing = probing - h.internalName
                    }
                },
                onToggleEnabled = { enabled ->
                    StremioServer.setPluginDisabled(h.internalName, !enabled)
                    StremioServer.saveDisabledPlugins()
                    tick++
                },
                onToggleAutoUninstall = { on ->
                    Maintenance.setAutoUninstall(h.internalName, on)
                    tick++
                },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HealthRow(
    health: PluginStreamHealth,
    probing: Boolean,
    autoUninstall: Boolean,
    onProbe: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleAutoUninstall: (Boolean) -> Unit,
) {
    val (color, label) = healthLook(health.status)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AmoledCard, RoundedCornerShape(14.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(health.pluginName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Links ${agoText(health.lastSuccessTime)} · ${health.successRequests}/${health.totalRequests} requests" +
                        (if (health.lastProbeTime > 0) " · checked ${agoText(health.lastProbeTime)}" else ""),
                    color = TextMuted, fontSize = 10.sp,
                )
            }
            Pill(label, color)
        }
        val note = health.lastProbeNote ?: health.lastError
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(note, color = TextSecondary, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (probing) {
                CircularProgressIndicator(color = Violet400, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Checking…", color = TextSecondary, fontSize = 11.sp)
            } else {
                ActionButton("Check now", onClick = onProbe)
            }
            Spacer(Modifier.weight(1f))
            Text("On", color = TextMuted, fontSize = 10.sp)
            Switch(
                checked = health.enabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(checkedTrackColor = Violet500, uncheckedTrackColor = AmoledCard2),
            )
        }
        if (!Maintenance.autoUninstallAll) {
            SwitchRow(
                title = "Auto-uninstall when dead",
                checked = autoUninstall,
                onCheckedChange = onToggleAutoUninstall,
            )
        }
    }
}

@Composable
private fun MaintenanceCard(tick: Int) {
    var daysText by remember { mutableStateOf(Maintenance.autoUninstallDays.toString()) }
    var allOn by remember { mutableStateOf(Maintenance.autoUninstallAll) }
    var showHistory by remember { mutableStateOf(false) }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle(
            "Nightly maintenance",
            "Every night at 00:00 IST: reload extensions, refresh home pages, check health and remove extensions that stayed dead.",
        )
        val running = Maintenance.isRunning()
        Text(
            if (running) "Running: ${Maintenance.currentStep ?: "…"}" else "Next run: ${timeText(Maintenance.nextRunAt())}",
            color = if (running) Amber400 else TextSecondary, fontSize = 12.sp,
        )
        Maintenance.lastRun?.let { r ->
            Text(
                "Last run ${timeText(r.startedAt)} (${r.trigger})" +
                    (if (r.uninstalled.isNotEmpty()) " · removed ${r.uninstalled.size}" else "") +
                    (r.error?.let { " · error: $it" } ?: ""),
                color = TextMuted, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        ActionButton("Run now", primary = true, enabled = !running) {
            launchBackground { Maintenance.runNow("app") }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = DividerColor)
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "Auto-uninstall applies to all extensions",
            description = "Off: only extensions you switch on in the list below",
            checked = allOn,
            onCheckedChange = { allOn = it; Maintenance.setAutoUninstallAll(it) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Remove after", color = TextPrimary, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = daysText,
                onValueChange = { v -> daysText = v.filter { it.isDigit() }.take(3) },
                singleLine = true,
                modifier = Modifier.width(80.dp),
                colors = amoledFieldColors(),
            )
            Spacer(Modifier.width(8.dp))
            Text("days without links", color = TextPrimary, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            ActionButton("Save", enabled = daysText.toIntOrNull()?.let { it >= 1 } == true) {
                daysText.toIntOrNull()?.let { Maintenance.setAutoUninstallDays(it); daysText = Maintenance.autoUninstallDays.toString() }
            }
        }
        val history = Maintenance.history
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showHistory = !showHistory }) {
                Text(if (showHistory) "Hide removed extensions" else "Removed extensions (${history.size})", color = Violet400, fontSize = 12.sp)
            }
            if (showHistory) {
                history.take(30).forEach { r ->
                    Text("${timeText(r.at)} · ${r.name} — ${r.reason}", color = TextSecondary, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HomeAuditCard(tick: Int) {
    val state = HomePageAudit.state
    var showRows by remember { mutableStateOf(false) }
    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle("Home-page audit", "Loads every extension's home page and lists the ones that come back empty.")
        if (state.running) {
            Text("Auditing… ${state.done}/${state.total}", color = Amber400, fontSize = 12.sp)
        } else if (state.finishedAt > 0) {
            Text(
                "Last audit ${agoText(state.finishedAt)}: ${state.rows.count { it.verdict == "ok" }} ok, " +
                    "${state.rows.count { it.verdict == "dead" }} dead, ${state.rows.count { it.verdict == "inconclusive" }} inconclusive" +
                    (if (state.uninstalled.isNotEmpty()) " · removed ${state.uninstalled.size}" else ""),
                color = TextSecondary, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Report only", enabled = !state.running) { launchBackground { HomePageAudit.run(uninstall = false) } }
            ActionButton("Audit & remove dead", danger = true, enabled = !state.running) { launchBackground { HomePageAudit.run(uninstall = true) } }
            if (state.rows.isNotEmpty()) {
                ActionButton(if (showRows) "Hide results" else "Show results") { showRows = !showRows }
            }
        }
        if (showRows) {
            Spacer(Modifier.height(8.dp))
            state.rows.forEach { r ->
                val c = when (r.verdict) { "ok" -> Green400; "dead" -> Red400; "skipped" -> TextMuted; else -> Amber400 }
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(r.verdict, c)
                    Spacer(Modifier.width(6.dp))
                    Text("${r.api} — ${r.note}", color = TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
