package com.cncverse.stremiobridge.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cncverse.stremiobridge.state.*

@Composable
fun LogsScreen(
    logs: List<LogEntry>,
    onCopyLogs: (String) -> Unit,
) {
    var filterLevel by remember { mutableStateOf<LogLevel?>(null) }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val filteredLogs = remember(logs, filterLevel) {
        if (filterLevel == null) logs else logs.filter { it.level == filterLevel }
    }

    // Auto-scroll to bottom on new log
    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.size - 1)
        }
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
                    "Logs",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "${filteredLogs.size} entries",
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Auto-scroll toggle
                IconButton(
                    onClick = { autoScroll = !autoScroll },
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (autoScroll) Violet600 else AmoledCard,
                            CircleShape
                        ),
                ) {
                    Icon(
                        Icons.Filled.VerticalAlignBottom,
                        contentDescription = "Auto scroll",
                        tint = if (autoScroll) Color.White else TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Copy button
                if (logs.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            val text = logs.joinToString("\n") { "[${it.level}] ${it.message}" }
                            onCopyLogs(text)
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(AmoledCard, CircleShape),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy logs", tint = Violet400, modifier = Modifier.size(16.dp))
                    }
                }

                // Clear button
                if (logs.isNotEmpty()) {
                    IconButton(
                        onClick = { ServerState.clearLogs() },
                        modifier = Modifier
                            .size(36.dp)
                            .background(AmoledCard, CircleShape),
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear logs", tint = Red400, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // ── Level Filter Chips ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LevelChip(label = "All", color = TextSecondary, selected = filterLevel == null, onClick = { filterLevel = null })
            LevelChip(label = "Info", color = Blue400, selected = filterLevel == LogLevel.INFO, onClick = { filterLevel = LogLevel.INFO })
            LevelChip(label = "Warn", color = Amber400, selected = filterLevel == LogLevel.WARN, onClick = { filterLevel = LogLevel.WARN })
            LevelChip(label = "Error", color = Red400, selected = filterLevel == LogLevel.ERROR, onClick = { filterLevel = LogLevel.ERROR })
        }

        Spacer(Modifier.height(12.dp))

        HorizontalDivider(color = DividerColor)

        // ── Log List ───────────────────────────────────────────────────────
        if (filteredLogs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("📋", fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text("No Logs Yet", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Start the server to see activity", color = TextMuted, fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(AmoledBlack),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filteredLogs) { entry ->
                    LogRow(entry)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun LevelChip(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.2f),
            selectedLabelColor = color,
            containerColor = AmoledCard,
            labelColor = TextMuted,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = CardBorder,
            selectedBorderColor = color.copy(alpha = 0.5f),
        ),
    )
}

@Composable
private fun LogRow(entry: LogEntry) {
    val (color, prefix) = when (entry.level) {
        LogLevel.INFO  -> Blue400   to "I"
        LogLevel.WARN  -> Amber400  to "W"
        LogLevel.ERROR -> Red400    to "E"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                when (entry.level) {
                    LogLevel.ERROR -> Color(0x14F87171)
                    LogLevel.WARN  -> Color(0x14FBBF24)
                    else           -> Color.Transparent
                }
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Level badge
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color.copy(alpha = 0.15f), CircleShape)
                .border(1.dp, color.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(prefix, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))

        // Timestamp
        val ts = formatTimestamp(entry.timestamp)
        Text(
            ts,
            color = TextMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(52.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(6.dp))

        // Message
        Text(
            entry.message,
            color = color.copy(alpha = if (entry.level == LogLevel.INFO) 0.85f else 1f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = (totalSeconds / 3600) % 24
    val m = (totalSeconds / 60) % 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
