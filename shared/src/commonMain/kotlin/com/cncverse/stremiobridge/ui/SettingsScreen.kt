package com.cncverse.stremiobridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen() {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Settings",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

            // About Section
            AmoledCard {
                Text(
                    "About CNCVerse",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { uriHandler.openUri("https://github.com/NivinCNC") }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Code, contentDescription = "GitHub", tint = Violet400)
                    Spacer(Modifier.width(16.dp))
                    Text("GitHub Source", color = TextPrimary, fontSize = 16.sp)
                }
                HorizontalDivider(color = DividerColor)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { uriHandler.openUri("https://t.me/cncverse") }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.ChatBubble, contentDescription = "Telegram", tint = Blue400)
                    Spacer(Modifier.width(16.dp))
                    Text("Join Telegram", color = TextPrimary, fontSize = 16.sp)
                }
                HorizontalDivider(color = DividerColor)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { uriHandler.openUri("https://cncverse.pages.dev") }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Favorite, contentDescription = "Donate", tint = Color(0xFFE040FB))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Donate", color = TextPrimary, fontSize = 16.sp)
                        Text("Support CNCVerse development", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }

            // ── Cloudflare Solver ──────────────────────────────────────────
            var cfEnabled by remember { mutableStateOf(CloudflareSolverState.enabled) }

            AmoledCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Cloudflare Solver",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Opens an isolated Edge/Chrome window to solve Turnstile challenges automatically",
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = cfEnabled,
                        onCheckedChange = { enabled ->
                            cfEnabled = enabled
                            CloudflareSolverState.enabled = enabled
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = Violet500,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = AmoledCard2,
                        ),
                    )
                }

                if (cfEnabled) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = DividerColor)
                    Spacer(Modifier.height(12.dp))

                    // Status row — how many domains have active clearance
                    val clearedCount = CloudflareSolverState.clearedDomainCount
                    val tlsBoundCount = CloudflareSolverState.tlsBoundDomainCount

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Clearance badge
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (clearedCount > 0) Color(0xFF1B4332) else AmoledCard2,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "$clearedCount",
                                    color = if (clearedCount > 0) Green400 else TextMuted,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text("Cleared domains", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                        // TLS-proxy badge
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (tlsBoundCount > 0) Color(0xFF1A237E) else AmoledCard2,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "$tlsBoundCount",
                                    color = if (tlsBoundCount > 0) Blue400 else TextMuted,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text("Browser-proxy hosts", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }

                    if (clearedCount > 0) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { CloudflareSolverState.clearAll() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AmoledSurface,
                                contentColor = Red400,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear")
                            Spacer(Modifier.width(8.dp))
                            Text("Clear All CF Clearance Cookies")
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "⚠️ Requires Microsoft Edge or Google Chrome to be installed. The browser window is opened in a sandboxed private session with no access to your personal data.",
                        color = TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }

    }
}
