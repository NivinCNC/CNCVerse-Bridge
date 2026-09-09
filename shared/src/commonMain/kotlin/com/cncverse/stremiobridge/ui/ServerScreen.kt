package com.cncverse.stremiobridge.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cncverse.stremiobridge.state.*

import com.cncverse.stremiobridge.tunnel.CloudflaredManager
import com.cncverse.stremiobridge.tunnel.DeviceIdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URL

// ── Donation stats data ────────────────────────────────────────────────────────
private data class DonationStats(
    val currentAmount: Double,
    val targetAmount: Double,
    val percent: Int,
    val supportersCount: Int,
    val month: String,
)

// in-memory cooldown: epoch-day string of last shown date
private var donationLastShownDay: String = ""
private val STATS_URL = "https://cncverse.pages.dev/api/stats"
private val PRIMARY_DONATE_URL = "https://cncverse.pages.dev"

private fun donationToday(): String {
    // Simple epoch-day string (UTC) — same approach as DonationManager
    val dayMs = System.currentTimeMillis() / 86_400_000L
    return dayMs.toString()
}

@Composable
fun ServerScreen(
    status: ServerStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopyUrl: (String) -> Unit,
    onOpenSettings: (String) -> Unit,
) {
    var showCloudflaredDialog by remember { mutableStateOf(false) }
    var cloudflaredDownloadProgress by remember { mutableStateOf<Float?>(null) }
    var isDownloadingCloudflared by remember { mutableStateOf(false) }
    val activeTunnelUrl by ServerState.activeTunnelUrl.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    // ── Donation dialog state ──────────────────────────────────────────────────
    var showDonationDialog by remember { mutableStateOf(false) }
    var donationStats by remember { mutableStateOf<DonationStats?>(null) }
    var donationLoading by remember { mutableStateOf(false) }

    fun tryShowDonation() {
        val today = donationToday()
        if (donationLastShownDay == today) return  // already shown today
        donationLastShownDay = today
        donationLoading = true
        coroutineScope.launch {
            val stats = withContext(Dispatchers.IO) {
                try {
                    val json = Json { ignoreUnknownKeys = true }
                    val text = URL(STATS_URL).readText()
                    val obj = json.parseToJsonElement(text).jsonObject
                    DonationStats(
                        currentAmount  = obj["totalUsd"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        targetAmount   = obj["targetGoalUsd"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 100.0,
                        percent        = obj["percent"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
                        supportersCount= obj["supporterCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        month          = obj["month"]?.jsonPrimitive?.content ?: "Monthly Goal",
                    )
                } catch (_: Exception) {
                    // Fallback with zeros — still show dialog
                    DonationStats(0.0, 100.0, -1, 0, "Monthly Goal")
                }
            }
            donationStats = stats
            donationLoading = false
            showDonationDialog = true
        }
    }

    // Show donation dialog when data is ready
    if (showDonationDialog && !donationLoading) {
        val stats = donationStats
        if (stats != null) {
            DonationDialog(
                stats = stats,
                onDismiss = { showDonationDialog = false },
                onPrimary = {
                    showDonationDialog = false
                    uriHandler.openUri(PRIMARY_DONATE_URL)
                },
            )
        }
    }

    if (showCloudflaredDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDownloadingCloudflared) showCloudflaredDialog = false },
            containerColor = AmoledCard,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            shape = RoundedCornerShape(16.dp),
            title = { Text("Cloudflare Tunnel Required", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column {
                    Text(
                        "Stremio Mode uses Cloudflare Tunnel to create a secure HTTPS connection so your streams load smoothly in Stremio Web.",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This requires downloading the cloudflared executable (~15-30 MB). Would you like to download it now?",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                    if (cloudflaredDownloadProgress != null) {
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { cloudflaredDownloadProgress ?: 0f },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                            color = Violet500,
                            trackColor = AmoledSurface,
                            strokeCap = StrokeCap.Round
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isDownloadingCloudflared,
                    colors = ButtonDefaults.buttonColors(containerColor = Violet500, contentColor = TextPrimary),
                    shape = CircleShape,
                    onClick = {
                        isDownloadingCloudflared = true
                        coroutineScope.launch {
                            val success = CloudflaredManager.downloadCloudflared { progress ->
                                cloudflaredDownloadProgress = progress
                            }
                            isDownloadingCloudflared = false
                            if (success) {
                                showCloudflaredDialog = false
                                ServerState.isStremioMode.value = true
                                if (status is ServerStatus.Running) {
                                    CloudflaredManager.startTunnel(status.port)
                                }
                            }
                        }
                    }
                ) {
                    Text(if (isDownloadingCloudflared) "Downloading..." else "Download & Enable")
                }
            },
            dismissButton = {
                if (!isDownloadingCloudflared) {
                    TextButton(onClick = { showCloudflaredDialog = false }) {
                        Text("Cancel", color = TextMuted)
                    }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))

        // ── Header ────────────────────────────────────────────────────────
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = logoPainter(),
                    contentDescription = "Logo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "CNCVerse",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary,
            )
            Text(
                "STREMIO BRIDGE",
                fontSize = 11.sp,
                color = Violet400,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── Status Card ───────────────────────────────────────────────────
        AmoledCard(modifier = Modifier.fillMaxWidth()) {
            StatusRow(status)

            Spacer(Modifier.height(24.dp))

            // Start / Stop Button
            val isRunning  = status is ServerStatus.Running
            val isStarting = status is ServerStatus.Starting

            val btnText = when {
                isStarting -> "Starting…"
                isRunning  -> "Stop Server"
                else       -> "Start Server"
            }
            val btnIcon = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow
            val btnColor = if (isRunning) Color(0xFF7F1D1D) else Violet600

            Button(
                onClick = {
                    if (isRunning) onStop()
                    else {
                        onStart()
                        tryShowDonation()
                    }
                },
                enabled = !isStarting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = btnColor,
                    disabledContainerColor = AmoledCard2,
                ),
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        color = Violet400,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(btnIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(btnText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            // URL chips (only when running)
            AnimatedVisibility(visible = isRunning) {
                if (status is ServerStatus.Running) {
                    Column(modifier = Modifier.padding(top = 20.dp)) {
                        HorizontalDivider(color = DividerColor)
                        Spacer(Modifier.height(16.dp))
                        
                        var selectedTab by remember { mutableStateOf(0) }
                        val isStremioMode by ServerState.isStremioMode.collectAsState()
                        
                        TabRow(
                            selectedTabIndex = selectedTab,
                            containerColor = Color.Transparent,
                            indicator = { tabPositions ->
                                if (selectedTab < tabPositions.size) {
                                    TabRowDefaults.SecondaryIndicator(
                                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                        color = Violet400
                                    )
                                }
                            }
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Nuvio", fontWeight = FontWeight.Bold) },
                                selectedContentColor = Violet400,
                                unselectedContentColor = TextMuted
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Stremio", fontWeight = FontWeight.Bold) },
                                selectedContentColor = Violet400,
                                unselectedContentColor = TextMuted
                            )
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Connection URLs",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        
                        val isEmulator = status.ipAddress.startsWith("10.0.2.") || status.ipAddress.startsWith("10.0.3.")
                        
                        if (selectedTab == 0) {
                            // Nuvio Tab (HTTP)
                            UrlChip(label = "LAN HTTP", url = status.stremioUrl) { onCopyUrl(status.stremioUrl) }
                            
                            if (isEmulator) {
                                val adbUrl = "http://127.0.0.1:${status.port}/manifest.json"
                                Spacer(Modifier.height(8.dp))
                                UrlChip(label = "PC HOST (ADB)", url = adbUrl) { onCopyUrl(adbUrl) }
                            }
                            
                            if (status.ipAddress != "127.0.0.1" && status.ipAddress != "localhost") {
                                Spacer(Modifier.height(8.dp))
                                UrlChip(label = "LOCAL HTTP", url = status.localhostUrl) { onCopyUrl(status.localhostUrl) }
                            }
                        } else {
                            // Stremio Tab (HTTPS)
                            val tunnelUrl = status.stremioModeStremioUrl
                            UrlChip(label = "CLOUDFLARE HTTPS", url = tunnelUrl, blurred = !isStremioMode) { onCopyUrl(tunnelUrl) }
                            
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isStremioMode) {
                                    if (!activeTunnelUrl.isNullOrBlank()) {
                                        Box(modifier = Modifier.size(8.dp).background(Green400, CircleShape))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Cloudflare Tunnel Active", color = Green400, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    } else {
                                        Box(modifier = Modifier.size(8.dp).background(Amber400, CircleShape))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Connecting Cloudflare Tunnel...", color = Amber400, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                } else {
                                    Box(modifier = Modifier.size(8.dp).background(Red400, CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Stremio Tunnel Disabled", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                            
                            Spacer(Modifier.height(16.dp))
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AmoledCard2)
                                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text("Enable Stremio Mode", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("Launches a Cloudflare Tunnel HTTPS proxy for Stremio Web compatibility.", color = TextMuted, fontSize = 11.sp, lineHeight = 14.sp)
                                }
                                Switch(
                                    checked = isStremioMode,
                                    onCheckedChange = { enable ->
                                        if (enable) {
                                            if (!CloudflaredManager.isInstalled()) {
                                                showCloudflaredDialog = true
                                            } else {
                                                ServerState.isStremioMode.value = true
                                                if (status is ServerStatus.Running) {
                                                    CloudflaredManager.startTunnel(status.port)
                                                }
                                            }
                                        } else {
                                            ServerState.isStremioMode.value = false
                                            CloudflaredManager.stopTunnel()
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Violet500,
                                        uncheckedThumbColor = TextMuted,
                                        uncheckedTrackColor = AmoledCard,
                                    )
                                )
                            }
                        }
                        
                        if (isEmulator) {
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Violet500.copy(alpha = 0.12f))
                                    .border(1.dp, Violet500.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("💻", fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
                                Column {
                                    Text("Emulator Detected", color = Violet500, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Run 'adb forward tcp:${status.port} tcp:${status.port}' on PC to connect Nuvio/Stremio from PC browser to http://127.0.0.1:${status.port}/manifest.json",
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Copy & paste the URL → Settings → Add-ons",
                            color = TextMuted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Loaded Plugins ────────────────────────────────────────────────
        if (status is ServerStatus.Running && status.loadedPlugins.isNotEmpty()) {
            AmoledCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Active Plugins",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        "${status.loadedPlugins.count { it.apiRegistered }} / ${status.loadedPlugins.size}",
                        color = Violet400,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(12.dp))
                status.loadedPlugins.forEachIndexed { i, plugin ->
                    LoadedPluginRow(plugin, onOpenSettings)
                    if (i < status.loadedPlugins.lastIndex) {
                        HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Status Row ────────────────────────────────────────────────────────────────

@Composable
private fun StatusRow(status: ServerStatus) {
    val (dotColor, label) = when (status) {
        is ServerStatus.Stopped  -> Red400    to "Server Stopped"
        is ServerStatus.Starting -> Amber400  to status.message
        is ServerStatus.Running  -> Green400  to "Running · Port ${status.port} · ${status.pluginCount} plugins"
        is ServerStatus.Error    -> Red400    to "Error: ${status.message}"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse_alpha",
    )
    val dotAlpha = if (status is ServerStatus.Running) pulse else 1f

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .graphicsLayer(alpha = dotAlpha)
                .background(dotColor, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = dotColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ── URL Chip ─────────────────────────────────────────────────────────────────

@Composable
private fun UrlChip(label: String, url: String, blurred: Boolean = false, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AmoledCard2)
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .let { if (!blurred) it.clickable(onClick = onCopy) else it }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Violet400,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color(0xFF1E1043), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            url,
            color = TextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).let { if (blurred) it.blur(4.dp) else it },
            fontFamily = FontFamily.Monospace,
        )
        Icon(
            if (blurred) Icons.Filled.Lock else Icons.Filled.ContentCopy,
            contentDescription = if (blurred) "Locked" else "Copy",
            tint = TextMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── Loaded Plugin Row ──────────────────────────────────────────────────────────

@Composable
private fun LoadedPluginRow(plugin: LoadedPluginInfo, onOpenSettings: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dotColor = when {
            !plugin.apiRegistered -> Amber400
            plugin.status == 0   -> Red400
            else                 -> Green400
        }
        Box(Modifier.size(7.dp).background(dotColor, CircleShape))
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(plugin.displayName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                plugin.tvTypes.take(3).forEach { TypeBadge(it) }
                plugin.language?.let {
                    Text("[$it]", color = TextMuted, fontSize = 9.sp)
                }
            }
        }

        if (plugin.hasSettings) {
            IconButton(
                onClick = { onOpenSettings(plugin.internalName) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun TypeBadge(type: String) {
    Text(
        type.uppercase().take(6),
        color = Violet300,
        fontSize = 9.sp,
        modifier = Modifier
            .background(Color(0xFF1E1043), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

// ── Donation Dialog ────────────────────────────────────────────────────────────

@Composable
private fun DonationDialog(
    stats: DonationStats,
    onDismiss: () -> Unit,
    onPrimary: () -> Unit,
) {
    val pct = if (stats.percent in 0..100) stats.percent
              else if (stats.targetAmount <= 0) 0
              else (stats.currentAmount / stats.targetAmount * 100).toInt().coerceIn(0, 100)
    val isAchieved = pct >= 100

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xFF251442), Color(0xFF180D2D), Color(0xFF0F071B))
                    ),
                    shape = RoundedCornerShape(20.dp),
                )
                .border(1.5.dp, Violet500, RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Column {
                // ── Badge ──────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF3B1D6B))
                        .border(1.dp, Color(0xFF8B5CF6), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        "⚡ CNCVerse Repo • by NivinCNC ↗",
                        color = Color(0xFFC4B5FD),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ── Title ──────────────────────────────────────────────────
                Text(
                    if (isAchieved) "🎉  CNCVerse Goal Achieved!" else "⚠️  Help Keep CNCVerse Alive",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(Modifier.height(10.dp))

                // ── Bullet points ──────────────────────────────────────────
                val bullets = if (isAchieved) listOf(
                    "🎉 Goal achieved! Thank you for the incredible support",
                    "🛠️ Keeps the bridge alive and active",
                    "🐞 Faster bug fixes",
                    "🚫 No Ads, No Subscription: Bridge stays 100% free for everyone.",
                ) else listOf(
                    "🚫 No Ads, No Subscription: Keeps bridge 100% free.",
                    "🛠️ Active Repo Maintenance: Frequent updates to support latest available extensions",
                    "⚠️ Goal Missed = Delayed Fixes: If monthly target isn't met, latest extensions support will slow down.",
                    "💀 Zero Support = Bridge Die: Without support, providers break and links die over time.",
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    bullets.forEach { b ->
                        Text(b, color = Color(0xFFE9D5FF), fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Progress card ──────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color(0xFF1E1035), Color(0xFF120924))
                            )
                        )
                        .border(1.dp, Color(0xFF4C1D95), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    // Label row
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${stats.month} Goal",
                            color = Color(0xFFC4B5FD),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "\$${stats.currentAmount.toInt()} / \$${stats.targetAmount.toInt()}  ($pct%)",
                            color = Color(0xFF4ADE80),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // Progress bar
                    LinearProgressIndicator(
                        progress = { pct.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = Color(0xFF4ADE80),
                        trackColor = Color(0xFF2A124E),
                        strokeCap = StrokeCap.Round,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "👥 ${stats.supportersCount} supporters this month",
                        color = Color(0xFFA78BFA),
                        fontSize = 12.sp,
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ── Action row ─────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            if (isAchieved) "Awesome!" else "Maybe Later",
                            color = Color(0xFFA78BFA),
                            fontSize = 14.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onPrimary,
                        colors = ButtonDefaults.buttonColors(containerColor = Violet600, contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            if (isAchieved) "💖 Send Extra Love" else "☕ Keep It Alive",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }

            }
        }
    }
}
