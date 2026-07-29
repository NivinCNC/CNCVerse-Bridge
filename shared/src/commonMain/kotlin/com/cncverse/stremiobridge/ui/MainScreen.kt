package com.cncverse.stremiobridge.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cncverse.stremiobridge.state.*
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.window.DialogProperties
import com.cncverse.stremiobridge.update.OtaUpdater
import com.cncverse.stremiobridge.update.GithubRelease
import kotlinx.coroutines.launch

// ────────── AMOLED Color Palette ─────────────────────────────────────────────
val AmoledBlack    = Color(0xFF000000)
val AmoledSurface  = Color(0xFF0A0A0A)
val AmoledCard     = Color(0xFF111111)
val AmoledCard2    = Color(0xFF181818)

val Violet600      = Color(0xFF7C3AED)
val Violet500      = Color(0xFF8B5CF6)
val Violet400      = Color(0xFFA78BFA)
val Violet300      = Color(0xFFC4B5FD)
val Violet200      = Color(0xFFDDD6FE)

val Green400       = Color(0xFF4ADE80)
val Green500       = Color(0xFF22C55E)
val Red400         = Color(0xFFF87171)
val Amber400       = Color(0xFFFBBF24)
val Blue400        = Color(0xFF60A5FA)

val TextPrimary    = Color(0xFFFFFFFF)
val TextSecondary  = Color(0xFF9CA3AF)
val TextMuted      = Color(0xFF6B7280)

val DividerColor   = Color(0xFF1F1F1F)
val CardBorder     = Color(0xFF222222)
val VioletGlow     = Color(0x338B5CF6)

// ────────── Theme ─────────────────────────────────────────────────────────────

@Composable
fun CNCVerseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary          = Violet500,
            onPrimary        = TextPrimary,
            primaryContainer = Color(0xFF3B1FA3),
            secondary        = Violet400,
            background       = AmoledBlack,
            surface          = AmoledSurface,
            surfaceVariant   = AmoledCard,
            onBackground     = TextPrimary,
            onSurface        = TextPrimary,
            onSurfaceVariant = TextSecondary,
            outline          = CardBorder,
        ),
        content = content,
    )
}

// ────────── Navigation ────────────────────────────────────────────────────────

enum class NavDest(val label: String, val icon: ImageVector) {
    Server(     "Server",     Icons.Filled.PlayArrow),
    Extensions( "Extensions", Icons.Filled.Extension),
    Logs(       "Logs",       Icons.AutoMirrored.Filled.List),
}

// ────────── MainScreen (Navigation Host) ─────────────────────────────────────

@Composable
fun MainScreen(
    statusFlow: StateFlow<ServerStatus>,
    logsFlow: StateFlow<List<LogEntry>>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopyUrl: (String) -> Unit = {},
    onCopyLogs: (String) -> Unit = {},
    onOpenSettings: (String) -> Unit = {},
    onInstallPlugin: suspend (AvailablePlugin) -> Unit = {},
    onUninstallPlugin: suspend (String) -> Unit = {},
    onAddRepo: suspend (String) -> Unit = {},
    onRemoveRepo: (String) -> Unit = {},
    onRefreshRepos: suspend () -> Unit = {},
    windowWidthClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
) {
    CNCVerseTheme {
        val status by statusFlow.collectAsState()
        val logs by logsFlow.collectAsState()
        val repos by RepoState.repos.collectAsState()
        val available by RepoState.availablePlugins.collectAsState()
        val installed by RepoState.installedPlugins.collectAsState()
        val installStates by RepoState.pluginInstallStates.collectAsState()
        val isRefreshing by RepoState.isRefreshing.collectAsState()

        var selectedDest by remember { mutableStateOf(NavDest.Server) }
        var otaRelease by remember { mutableStateOf<GithubRelease?>(null) }
        var downloadProgress by remember { mutableStateOf<Float?>(null) }
        var isDownloading by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        val uriHandler = LocalUriHandler.current

        LaunchedEffect(Unit) {
            otaRelease = OtaUpdater.checkForUpdate()
        }

        val isCompact = windowWidthClass == WindowWidthSizeClass.Compact

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AmoledBlack)
        ) {
            if (otaRelease != null) {
                AlertDialog(
                    onDismissRequest = { },
                    containerColor = AmoledCard,
                    titleContentColor = TextPrimary,
                    textContentColor = TextSecondary,
                    shape = RoundedCornerShape(16.dp),
                    title = { 
                        Text("Update Available", fontWeight = FontWeight.Bold, fontSize = 20.sp) 
                    },
                    text = { 
                        Column {
                            Text("Version ${otaRelease!!.tagName} is available. Please download the latest release.", fontSize = 15.sp)
                            
                            if (otaRelease!!.body.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Release Notes:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 120.dp)
                                        .background(AmoledSurface, RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    androidx.compose.foundation.rememberScrollState().let { scrollState ->
                                        Text(
                                            text = otaRelease!!.body,
                                            fontSize = 12.sp,
                                            color = TextSecondary,
                                            modifier = Modifier.verticalScroll(scrollState)
                                        )
                                    }
                                }
                            }
                            if (downloadProgress != null) {
                                Spacer(modifier = Modifier.height(20.dp))
                                LinearProgressIndicator(
                                    progress = { downloadProgress ?: 0f },
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
                            enabled = !isDownloading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Violet500,
                                contentColor = TextPrimary,
                                disabledContainerColor = AmoledCard2,
                                disabledContentColor = TextMuted
                            ),
                            shape = CircleShape,
                            onClick = { 
                                val apkAsset = otaRelease!!.assets.firstOrNull { it.name.endsWith(".apk") }
                                if (apkAsset != null) {
                                    isDownloading = true
                                    coroutineScope.launch {
                                        val path = OtaUpdater.downloadUpdate(apkAsset.downloadUrl, apkAsset.name) {
                                            downloadProgress = it
                                        }
                                        if (path != null) {
                                            com.cncverse.stremiobridge.update.installOtaUpdate(path)
                                        } else {
                                            uriHandler.openUri(otaRelease!!.htmlUrl)
                                        }
                                        isDownloading = false
                                    }
                                } else {
                                    uriHandler.openUri(otaRelease!!.htmlUrl)
                                }
                            }
                        ) {
                            Text(if (isDownloading) "Downloading..." else "Download Now")
                        }
                    },
                    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
                )
            }

            if (isCompact) {
                // ── Bottom navigation layout ─────────────────────────────────
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        NavContent(
                            dest = selectedDest,
                            status = status,
                            logs = logs,
                            repos = repos,
                            available = available,
                            installed = installed,
                            installStates = installStates,
                            isRefreshing = isRefreshing,
                            onStart = onStart,
                            onStop = onStop,
                            onCopyUrl = onCopyUrl,
                            onCopyLogs = onCopyLogs,
                            onOpenSettings = onOpenSettings,
                            onInstallPlugin = onInstallPlugin,
                            onUninstallPlugin = onUninstallPlugin,
                            onAddRepo = onAddRepo,
                            onRemoveRepo = onRemoveRepo,
                            onRefreshRepos = onRefreshRepos,
                        )
                    }
                    AmoledBottomBar(
                        selected = selectedDest,
                        onSelect = { selectedDest = it },
                    )
                }
            } else {
                // ── Left navigation rail layout ──────────────────────────────
                Row(modifier = Modifier.fillMaxSize()) {
                    AmoledNavRail(
                        selected = selectedDest,
                        onSelect = { selectedDest = it },
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        NavContent(
                            dest = selectedDest,
                            status = status,
                            logs = logs,
                            repos = repos,
                            available = available,
                            installed = installed,
                            installStates = installStates,
                            isRefreshing = isRefreshing,
                            onStart = onStart,
                            onStop = onStop,
                            onCopyUrl = onCopyUrl,
                            onCopyLogs = onCopyLogs,
                            onOpenSettings = onOpenSettings,
                            onInstallPlugin = onInstallPlugin,
                            onUninstallPlugin = onUninstallPlugin,
                            onAddRepo = onAddRepo,
                            onRemoveRepo = onRemoveRepo,
                            onRefreshRepos = onRefreshRepos,
                        )
                    }
                }
            }
        }
    }
}

// ── Navigation Content ─────────────────────────────────────────────────────────

@Composable
private fun NavContent(
    dest: NavDest,
    status: ServerStatus,
    logs: List<LogEntry>,
    repos: List<RepoEntry>,
    available: List<AvailablePlugin>,
    installed: List<InstalledPlugin>,
    installStates: Map<String, PluginInstallState>,
    isRefreshing: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopyUrl: (String) -> Unit,
    onCopyLogs: (String) -> Unit,
    onOpenSettings: (String) -> Unit,
    onInstallPlugin: suspend (AvailablePlugin) -> Unit,
    onUninstallPlugin: suspend (String) -> Unit,
    onAddRepo: suspend (String) -> Unit,
    onRemoveRepo: (String) -> Unit,
    onRefreshRepos: suspend () -> Unit,
) {
    AnimatedContent(
        targetState = dest,
        transitionSpec = {
            fadeIn(tween(200)) togetherWith fadeOut(tween(150))
        },
        label = "nav_content",
    ) { target ->
        when (target) {
            NavDest.Server -> ServerScreen(
                status = status,
                onStart = onStart,
                onStop = onStop,
                onCopyUrl = onCopyUrl,
                onOpenSettings = onOpenSettings,
            )
            NavDest.Extensions -> ExtensionsScreen(
                status = status,
                repos = repos,
                availablePlugins = available,
                installedPlugins = installed,
                installStates = installStates,
                isRefreshing = isRefreshing,
                onInstallPlugin = onInstallPlugin,
                onUninstallPlugin = onUninstallPlugin,
                onAddRepo = onAddRepo,
                onRemoveRepo = onRemoveRepo,
                onRefreshRepos = onRefreshRepos,
                onOpenSettings = onOpenSettings,
            )
            NavDest.Logs -> LogsScreen(
                logs = logs,
                onCopyLogs = onCopyLogs,
            )
        }
    }
}

// ── Bottom Navigation Bar ─────────────────────────────────────────────────────

@Composable
private fun AmoledBottomBar(
    selected: NavDest,
    onSelect: (NavDest) -> Unit,
) {
    Surface(
        color = AmoledSurface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider(color = DividerColor)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavDest.entries.forEach { dest ->
                    BottomNavItem(
                        dest = dest,
                        selected = dest == selected,
                        onClick = { onSelect(dest) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.BottomNavItem(
    dest: NavDest,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(
        targetValue = if (selected) Violet400 else TextMuted,
        animationSpec = tween(200),
        label = "nav_color",
    )
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(VioletGlow, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(dest.icon, contentDescription = dest.label, tint = color, modifier = Modifier.size(20.dp))
            }
        } else {
            Icon(dest.icon, contentDescription = dest.label, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            dest.label,
            color = color,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ── Left Navigation Rail ──────────────────────────────────────────────────────

@Composable
private fun AmoledNavRail(
    selected: NavDest,
    onSelect: (NavDest) -> Unit,
) {
    Surface(
        color = AmoledSurface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxHeight()
            .width(80.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .systemBarsPadding()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Logo
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = logoPainter(),
                    contentDescription = "Logo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = DividerColor, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(8.dp))

            NavDest.entries.forEach { dest ->
                RailNavItem(
                    dest = dest,
                    selected = dest == selected,
                    onClick = { onSelect(dest) },
                )
            }
        }
    }
}

@Composable
private fun RailNavItem(
    dest: NavDest,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(
        targetValue = if (selected) Violet400 else TextMuted,
        animationSpec = tween(200),
        label = "rail_color",
    )
    Column(
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(if (selected) VioletGlow else Color.Transparent)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(dest.icon, contentDescription = dest.label, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            dest.label,
            color = color,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ── Reusable Card ─────────────────────────────────────────────────────────────

@Composable
fun AmoledCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AmoledCard)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content,
    )
}
