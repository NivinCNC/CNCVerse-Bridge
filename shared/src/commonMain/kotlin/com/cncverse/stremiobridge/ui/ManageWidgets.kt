package com.cncverse.stremiobridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cncverse.stremiobridge.server.BridgeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Small building blocks shared by the management screens (Health, Settings sections).

private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Runs [block] on the app-wide scope, so long jobs (health sweeps, audits, bulk
 * installs) keep going when the user switches tabs.
 */
fun launchBackground(block: suspend CoroutineScope.() -> Unit) {
    (BridgeRuntime.appScope ?: fallbackScope).launch(Dispatchers.IO, block = block)
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (description != null) Text(description, color = TextMuted, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = Violet500,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = AmoledCard2,
            ),
        )
    }
}

@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 36.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Violet600,
                contentColor = Color.White,
                disabledContainerColor = AmoledCard2,
                disabledContentColor = TextMuted,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    } else {
        val tint = if (danger) Red400 else TextPrimary
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 36.dp),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, if (danger) Red400.copy(alpha = 0.5f) else CardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = tint, disabledContentColor = TextMuted),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
fun amoledFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = AmoledCard2,
    unfocusedContainerColor = AmoledCard2,
    focusedBorderColor = Violet500,
    unfocusedBorderColor = CardBorder,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Violet400,
    focusedLabelColor = Violet400,
    unfocusedLabelColor = TextMuted,
)

/** A small coloured pill (health status, verdicts). */
@Composable
fun Pill(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Colour + label for a [com.cncverse.stremiobridge.state.HealthStatus] value. */
fun healthLook(status: String): Pair<Color, String> = when (status) {
    "working" -> Green400 to "Working"
    "proxy" -> Blue400 to "Working"
    "dead" -> Red400 to "Dead"
    else -> TextMuted to "Unknown"
}

/** "5 min ago", "3 h ago", "2 d ago", or "never". */
fun agoText(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0L) return "never"
    val s = (now - epochMs) / 1000
    return when {
        s < 0 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

/** Local date-time for timestamps shown in the UI. */
fun timeText(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    return java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(java.util.Date(epochMs))
}

/** A label/value pair inside a stats grid. */
@Composable
fun StatTile(value: String, label: String, color: Color = TextPrimary, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(AmoledCard2, RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextSecondary, fontSize = 10.sp)
    }
}
