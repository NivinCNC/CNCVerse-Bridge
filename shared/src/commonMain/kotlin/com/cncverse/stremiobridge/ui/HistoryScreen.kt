package com.cncverse.stremiobridge.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cncverse.stremiobridge.state.HistoryEntry
import com.cncverse.stremiobridge.state.StreamHistory

private fun clock(ms: Long): String {
    val t = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${p(t.get(java.util.Calendar.DAY_OF_MONTH))}/${p(t.get(java.util.Calendar.MONTH) + 1)} " +
        "${p(t.get(java.util.Calendar.HOUR_OF_DAY))}:${p(t.get(java.util.Calendar.MINUTE))}:${p(t.get(java.util.Calendar.SECOND))}"
}

/** Every answered stream request: title, episode, what each extension found and the exact list the client got. */
@Composable
fun HistoryScreen(onCopy: (String) -> Unit = {}) {
    val entries by StreamHistory.entries.collectAsState()
    var open by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize().background(AmoledBlack).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("History", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("${entries.size} request(s) answered", fontSize = 12.sp, color = TextSecondary)
            }
            TextButton(onClick = { StreamHistory.clear() }, enabled = entries.isNotEmpty()) { Text("Clear") }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing yet - open a movie, series or anime in your player.", color = TextMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.time.toString() + it.id }) { e -> HistoryCard(e, open == e.time, { open = if (open == e.time) null else e.time }, onCopy) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryCard(e: HistoryEntry, expanded: Boolean, toggle: () -> Unit, onCopy: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AmoledCard)
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp)).clickable { toggle() }.padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                e.title + if (e.episode.isNotEmpty()) "  ·  ${e.episode}" else "",
                color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f),
            )
            Text(clock(e.time), color = TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        val color = if (e.shown > 0) Green400 else Red400
        Text(
            "${e.type} · found ${e.found} · shown ${e.shown} · ${"%.1f".format(e.tookMs / 1000.0)}s",
            color = color, fontSize = 12.sp,
        )
        if (e.providers.isNotEmpty()) {
            Text(e.providers.joinToString("  •  "), color = TextSecondary, fontSize = 11.sp)
        }
        Text(e.id, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = DividerColor)
            Spacer(Modifier.height(8.dp))
            if (e.lines.isEmpty()) Text("No links were sent.", color = TextMuted, fontSize = 12.sp)
            e.lines.forEachIndexed { i, l ->
                Text("${i + 1}. $l", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
            TextButton(onClick = {
                onCopy(buildString {
                    appendLine("${e.title} ${e.episode} (${e.id})")
                    e.lines.forEachIndexed { i, l -> appendLine("${i + 1}. $l") }
                })
            }) { Text("Copy list") }
        } else {
            Text("Tap to see the list as shown", color = TextMuted, fontSize = 10.sp)
        }
    }
}
