package com.cncverse.stremiobridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cncverse.stremiobridge.update.GithubRelease
import com.cncverse.stremiobridge.update.OtaUpdater
import com.cncverse.stremiobridge.update.installOtaUpdate
import com.cncverse.stremiobridge.update.otaAssetExtension
import kotlinx.coroutines.launch

/**
 * "Update available" dialog, shared by Android and desktop. The user can
 *  - **Update**: download the installer of this platform and hand it to the system installer,
 *  - **Later**: close it (it comes back at the next start),
 *  - **Skip this version**: never ask for this version again (a newer one is still offered).
 * While the file downloads, the dialog cannot be closed.
 */
@Composable
fun UpdateDialog(release: GithubRelease, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var progress by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf(false) }
    val busy = progress != null
    val notes = remember(release) { OtaUpdater.notesOf(release) }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        containerColor = AmoledCard,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        shape = RoundedCornerShape(16.dp),
        title = { Text("Update available", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
        text = {
            Column {
                Text(
                    "CNCVerse Bridge ${OtaUpdater.versionOf(release)} is available (you have ${OtaUpdater.currentVersion}).",
                    fontSize = 15.sp,
                )
                if (notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Release notes", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    val scroll = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .background(AmoledSurface, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(notes, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.verticalScroll(scroll))
                    }
                }
                progress?.let { p ->
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        color = Violet500,
                        trackColor = AmoledSurface,
                        strokeCap = StrokeCap.Round,
                    )
                }
                if (failed) {
                    Spacer(Modifier.height(12.dp))
                    Text("The download failed. Open the release page to get the file by hand.", fontSize = 13.sp, color = Amber400)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Violet500,
                    contentColor = TextPrimary,
                    disabledContainerColor = AmoledCard2,
                    disabledContentColor = TextMuted,
                ),
                shape = CircleShape,
                onClick = {
                    val asset = release.assets.firstOrNull { it.name.endsWith(otaAssetExtension, ignoreCase = true) }
                    if (failed || asset == null) {
                        uriHandler.openUri(release.htmlUrl)
                        onClose()
                    } else {
                        failed = false
                        progress = 0f
                        scope.launch {
                            val path = OtaUpdater.downloadUpdate(asset.downloadUrl, asset.name) { progress = it }
                            if (path != null) {
                                // Android: opens the package installer. Desktop: starts the installer and exits.
                                installOtaUpdate(path)
                                progress = null
                                onClose()
                            } else {
                                progress = null
                                failed = true
                            }
                        }
                    }
                },
            ) {
                Text(
                    when {
                        busy -> "Downloading… ${((progress ?: 0f) * 100).toInt()}%"
                        failed -> "Open release page"
                        else -> "Update"
                    }
                )
            }
        },
        dismissButton = {
            if (!busy) {
                Row {
                    TextButton(onClick = { OtaUpdater.skipVersion(release); onClose() }) {
                        Text("Skip this version", color = TextMuted)
                    }
                    TextButton(onClick = onClose) { Text("Later", color = Violet300) }
                }
            }
        },
    )
}
