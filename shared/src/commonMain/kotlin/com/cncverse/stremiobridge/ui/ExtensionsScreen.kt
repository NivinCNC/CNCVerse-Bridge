package com.cncverse.stremiobridge.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.cncverse.stremiobridge.state.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
fun ExtensionsScreen(
    status: ServerStatus,
    repos: List<RepoEntry>,
    availablePlugins: List<AvailablePlugin>,
    installedPlugins: List<InstalledPlugin>,
    installStates: Map<String, PluginInstallState>,
    isRefreshing: Boolean,
    onInstallPlugin: suspend (AvailablePlugin) -> Unit,
    onUninstallPlugin: suspend (String) -> Unit,
    onAddRepo: suspend (String) -> Unit,
    onRemoveRepo: (String) -> Unit,
    onRefreshRepos: suspend () -> Unit,
    onOpenSettings: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val globalLoadedPlugins by ServerState.globalLoadedPlugins.collectAsState()
    
    var searchQuery by remember { mutableStateOf("") }
    var selectedRepoFilter by remember { mutableStateOf<String?>(null) }
    var showAddRepoDialog by remember { mutableStateOf(false) }
    var showOnlyInstalled by remember { mutableStateOf(false) }

    // Filter available plugins
    val filteredPlugins = remember(availablePlugins, searchQuery, selectedRepoFilter, showOnlyInstalled, installedPlugins) {
        availablePlugins
            .filter { ap ->
                (selectedRepoFilter == null || ap.repoEntry.url == selectedRepoFilter) &&
                (searchQuery.isBlank() || ap.plugin.name.contains(searchQuery, ignoreCase = true) ||
                    ap.plugin.description?.contains(searchQuery, ignoreCase = true) == true) &&
                (!showOnlyInstalled || installedPlugins.any { it.internalName == ap.plugin.internalName && it.repoUrl == ap.repoEntry.url })
            }
            .sortedBy { it.plugin.name }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .systemBarsPadding(),
    ) {
        // ── Top Bar ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Extensions",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "${installedPlugins.size} installed · ${availablePlugins.size} available",
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        color = Violet400,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(
                        onClick = { scope.launch { onRefreshRepos() } },
                        modifier = Modifier
                            .size(36.dp)
                            .background(AmoledCard, CircleShape),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Violet400, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(
                    onClick = { showAddRepoDialog = true },
                    modifier = Modifier
                        .size(36.dp)
                        .background(Violet600, CircleShape),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Repo", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }

        // ── Search Bar ─────────────────────────────────────────────────────
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            placeholder = { Text("Search extensions…", color = TextMuted, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AmoledCard,
                unfocusedContainerColor = AmoledCard,
                focusedBorderColor = Violet500,
                unfocusedBorderColor = CardBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Violet400,
            ),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
        )

        Spacer(Modifier.height(12.dp))

        // ── Repo Filter Chips ──────────────────────────────────────────────
        if (repos.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = selectedRepoFilter == null && !showOnlyInstalled,
                        onClick = { selectedRepoFilter = null; showOnlyInstalled = false },
                        label = { Text("All", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Violet600,
                            selectedLabelColor = Color.White,
                            containerColor = AmoledCard,
                            labelColor = TextSecondary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = selectedRepoFilter == null && !showOnlyInstalled,
                            borderColor = CardBorder,
                            selectedBorderColor = Violet600,
                        ),
                    )
                }
                item {
                    FilterChip(
                        selected = showOnlyInstalled,
                        onClick = { showOnlyInstalled = !showOnlyInstalled; selectedRepoFilter = null },
                        label = { Text("Installed", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp),
                                tint = if (showOnlyInstalled) Color.White else Green400)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF14532D),
                            selectedLabelColor = Color.White,
                            containerColor = AmoledCard,
                            labelColor = TextSecondary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = showOnlyInstalled,
                            borderColor = CardBorder,
                            selectedBorderColor = Green500,
                        ),
                    )
                }
                items(repos) { repo ->
                    FilterChip(
                        selected = selectedRepoFilter == repo.url,
                        onClick = {
                            selectedRepoFilter = if (selectedRepoFilter == repo.url) null else repo.url
                            showOnlyInstalled = false
                        },
                        label = {
                            Text(
                                repo.name.ifBlank { repo.url.removePrefix("https://").removePrefix("http://").take(24) },
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Violet600,
                            selectedLabelColor = Color.White,
                            containerColor = AmoledCard,
                            labelColor = TextSecondary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = selectedRepoFilter == repo.url,
                            borderColor = CardBorder,
                            selectedBorderColor = Violet600,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── Plugin List ────────────────────────────────────────────────────
        if (availablePlugins.isEmpty() && !isRefreshing) {
            EmptyExtensionsState(onAddRepo = { showAddRepoDialog = true })
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = filteredPlugins,
                    key = { "${it.repoEntry.url}|${it.plugin.internalName}" },
                ) { ap ->
                    val globalState = installStates[ap.plugin.internalName] ?: PluginInstallState.NotInstalled
                    val inst = installedPlugins.find { it.internalName == ap.plugin.internalName }
                    val installState = if (inst != null && inst.repoUrl != ap.repoEntry.url) {
                        if (globalState is PluginInstallState.Installing) globalState else PluginInstallState.NotInstalled
                    } else {
                        globalState
                    }
                    val loadedInfo = globalLoadedPlugins.find { it.internalName == ap.plugin.internalName }
                    PluginCard(
                        ap = ap,
                        installState = installState,
                        loadedInfo = loadedInfo,
                        onInstall = { scope.launch { onInstallPlugin(ap) } },
                        onUninstall = { scope.launch { onUninstallPlugin(ap.plugin.internalName) } },
                        onOpenSettings = { onOpenSettings(ap.plugin.internalName) },
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    // ── Add Repo Dialog ────────────────────────────────────────────────────
    if (showAddRepoDialog) {
        AddRepoDialog(
            existingRepos = repos,
            onDismiss = { showAddRepoDialog = false },
            onAdd = { url ->
                showAddRepoDialog = false
                scope.launch { onAddRepo(url) }
            },
            onRemove = onRemoveRepo,
        )
    }
}

// ── Plugin Card ────────────────────────────────────────────────────────────────

@Composable
private fun PluginCard(
    ap: AvailablePlugin,
    installState: PluginInstallState,
    loadedInfo: LoadedPluginInfo?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val plugin = ap.plugin
    val isInstalled = installState is PluginInstallState.Installed || installState is PluginInstallState.UpdateAvailable
    val isInstalling = installState is PluginInstallState.Installing
    val hasUpdate = installState is PluginInstallState.UpdateAvailable

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AmoledCard)
            .border(
                1.dp,
                if (isInstalled) Violet600.copy(alpha = 0.4f) else CardBorder,
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Plugin Icon
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AmoledCard2),
            contentAlignment = Alignment.Center,
        ) {
            if (plugin.iconUrl != null) {
                AsyncImage(
                    model = plugin.iconUrl,
                    contentDescription = plugin.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(plugin.name.take(1).uppercase(), color = Violet400, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            // Installed badge
            if (isInstalled) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .background(if (hasUpdate) Amber400 else Green500, CircleShape)
                        .border(1.5.dp, AmoledCard, CircleShape),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Plugin Info
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plugin.name,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "v${plugin.version}",
                    color = TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            if (hasUpdate) {
                Text(
                    "Update Available → v${(installState as PluginInstallState.UpdateAvailable).newVersion}",
                    color = Amber400,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            plugin.description?.let { desc ->
                Text(
                    desc,
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Spacer(Modifier.height(6.dp))

            // Badges row
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                plugin.tvTypes?.take(3)?.forEach { type ->
                    Text(
                        type.take(6).uppercase(),
                        color = Violet300,
                        fontSize = 8.sp,
                        modifier = Modifier
                            .background(Color(0xFF1E1043), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
                plugin.language?.let { lang ->
                    Text(
                        lang.uppercase(),
                        color = Blue400,
                        fontSize = 8.sp,
                        modifier = Modifier
                            .background(Color(0xFF1E3A5F), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
                StatusDot(plugin.status)
            }

            if (plugin.authors.isNotEmpty()) {
                Text(
                    plugin.authors.take(2).joinToString(", "),
                    color = TextMuted,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Install / Uninstall Button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isInstalling) {
                CircularProgressIndicator(
                    color = Violet400,
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                )
            } else if (isInstalled) {
                if (hasUpdate) {
                    // Update button
                    OutlinedButton(
                        onClick = onInstall,
                        modifier = Modifier.width(80.dp).height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Amber400),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Icon(Icons.Filled.Upgrade, contentDescription = "Update", tint = Amber400, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Update", color = Amber400, fontSize = 10.sp)
                    }
                }
                if (loadedInfo?.hasSettings == true) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.width(80.dp).height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, CardBorder),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextPrimary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Settings", color = TextPrimary, fontSize = 10.sp)
                    }
                }
                // Uninstall button
                OutlinedButton(
                    onClick = onUninstall,
                    modifier = Modifier.width(80.dp).height(30.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Red400.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Uninstall", tint = Red400, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Remove", color = Red400, fontSize = 10.sp)
                }
            } else {
                Button(
                    onClick = onInstall,
                    modifier = Modifier.width(80.dp).height(30.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet600),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = "Install", tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Install", color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: Int) {
    val (color, label) = when (status) {
        0    -> Red400   to "Down"
        1    -> Green400 to "OK"
        2    -> Amber400 to "Slow"
        3    -> Blue400  to "Beta"
        else -> TextMuted to "Unknown"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Text(label, color = color, fontSize = 8.sp)
    }
}

// ── Add Repo Dialog ────────────────────────────────────────────────────────────

@Composable
private fun AddRepoDialog(
    existingRepos: List<RepoEntry>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(AmoledCard)
                .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
                .padding(24.dp),
        ) {
            Text("Manage Repos", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Add repository URLs or shortcodes (e.g., phisherrepo)", color = TextMuted, fontSize = 12.sp)

            Spacer(Modifier.height(20.dp))

            // Existing repos
            if (existingRepos.isNotEmpty()) {
                Text("Added Repos", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                Spacer(Modifier.height(8.dp))
                existingRepos.forEach { repo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AmoledCard2)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                repo.name.ifBlank { repo.url.removePrefix("https://").removePrefix("http://").take(30) },
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                repo.url,
                                color = TextMuted,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (repo.isLoading) {
                            CircularProgressIndicator(color = Violet400, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else if (repo.url != com.cncverse.stremiobridge.repo.DEFAULT_REPO_URL) {
                            IconButton(
                                onClick = { onRemove(repo.url) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = Red400, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            // Add new URL
            Text("Add New Repo", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("URL or Shortcode (e.g., phisherrepo)", color = TextMuted, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AmoledCard2,
                    unfocusedContainerColor = AmoledCard2,
                    focusedBorderColor = Violet500,
                    unfocusedBorderColor = CardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Violet400,
                ),
                shape = RoundedCornerShape(10.dp),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, CardBorder),
                ) {
                    Text("Cancel", color = TextSecondary)
                }
                Button(
                    onClick = { if (url.isNotBlank()) onAdd(url.trim()) },
                    modifier = Modifier.weight(1f),
                    enabled = url.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet600, disabledContainerColor = AmoledCard2),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }
    }
}

// ── Empty State ────────────────────────────────────────────────────────────────

@Composable
private fun EmptyExtensionsState(onAddRepo: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🧩", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text("No Repos Added", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add a repo to discover and install extensions",
            color = TextMuted,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAddRepo,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Violet600),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add Repo")
        }
    }
}
