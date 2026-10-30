package com.fasterdownloads.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fasterdownloads.core.DownloadController
import com.fasterdownloads.core.DownloadKind
import com.fasterdownloads.core.DownloadPhase
import com.fasterdownloads.core.DownloadRequest
import com.fasterdownloads.core.DownloadUiState
import com.fasterdownloads.core.formatBytes
import com.fasterdownloads.core.formatDuration
import com.fasterdownloads.core.formatSpeed
import com.fasterdownloads.service.DownloadService

private const val SAMPLE_URL = "https://speed.hetzner.de/100MB.bin"

@Composable
fun FasterDownloadsApp(
    night: Boolean,
    onToggleTheme: () -> Unit,
    onOpenFile: (String) -> Unit,
    onEnsurePermissions: () -> Unit,
) {
    val context = LocalContext.current
    val downloads by DownloadController.downloads.collectAsState()
    val incoming by DownloadController.incoming.collectAsState()

    var url by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf("") }

    // A magnet link opened from a browser or shared to the app lands here.
    LaunchedEffect(incoming) {
        val link = incoming
        if (!link.isNullOrBlank()) {
            url = link
            DownloadController.clearIncoming()
        }
    }

    val trimmedUrl = url.trim()
    val isMagnet = trimmedUrl.startsWith("magnet:", ignoreCase = true)
    val isHttp = trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")
    val canAdd = isMagnet || isHttp
    val finishedCount = downloads.count { it.terminal }

    val fg = MaterialTheme.colorScheme.onBackground

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Header(night = night, onToggleTheme = onToggleTheme)

            Section(title = "New download") {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("File URL or magnet link") },
                    placeholder = { Text("https://... or magnet:?xt=urn:btih:...") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!isMagnet) {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("Save as (optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    onClick = {
                        url = SAMPLE_URL
                        fileName = "100MB-test.bin"
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = fg),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text("Use a 100 MB test file", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = {
                        onEnsurePermissions()
                        DownloadService.start(
                            context,
                            DownloadRequest(url = trimmedUrl, fileName = fileName.trim()),
                        )
                        url = ""
                        fileName = ""
                    },
                    enabled = canAdd,
                    shape = RoundedCornerShape(12.dp),
                    elevation = null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = fg,
                        contentColor = MaterialTheme.colorScheme.background,
                        disabledContainerColor = fg.copy(alpha = 0.2f),
                        disabledContentColor = MaterialTheme.colorScheme.background.copy(alpha = 0.75f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    Text("Add download", style = MaterialTheme.typography.labelLarge)
                }

                Text(
                    if (isMagnet) {
                        "Magnets run on libtorrent; the engine is shared, so a second magnet " +
                            "finds peers in seconds instead of bootstrapping DHT again."
                    } else {
                        "HTTP downloads always use Wi-Fi and mobile data together: 50/50 split, " +
                            "8 parallel range requests per network."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.5f),
                )
            }

            if (downloads.isEmpty()) {
                Text(
                    text = "Nothing downloading yet. Paste a link above, or open a magnet from " +
                        "a browser - the app takes over from there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = fg.copy(alpha = 0.55f),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "DOWNLOADS",
                        style = MaterialTheme.typography.labelMedium,
                        color = fg.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f),
                    )
                    if (finishedCount > 0) {
                        TextButton(
                            onClick = { DownloadController.clearFinished() },
                            colors = ButtonDefaults.textButtonColors(contentColor = fg.copy(alpha = 0.7f)),
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                        ) {
                            Text("Clear finished", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                downloads.forEach { state ->
                    DownloadCard(
                        state = state,
                        onPause = { DownloadService.pause(context, state.id) },
                        onResume = { DownloadService.resume(context, state.id) },
                        onCancel = { DownloadService.cancel(context, state.id) },
                        onRemove = { DownloadController.remove(state.id) },
                        onOpenFile = onOpenFile,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Header(night: Boolean, onToggleTheme: () -> Unit) {
    val fg = MaterialTheme.colorScheme.onBackground
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MULTIPATH DOWNLOADER",
                style = MaterialTheme.typography.labelMedium,
                color = fg.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f),
            )
            ThemeToggle(night = night, onToggleTheme = onToggleTheme)
        }
        Text(
            text = "Faster\nDownloads",
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = "One file, two radios. HTTP chunks ride Wi-Fi and mobile data at the same " +
                "time; magnet links come from the BitTorrent swarm. Several downloads run " +
                "side by side.",
            style = MaterialTheme.typography.bodyMedium,
            color = fg.copy(alpha = 0.62f),
        )
    }
}

/**
 * Day / night switch, top right of the header. Two explicit segments rather than a
 * sun/moon icon toggle, so the current mode is readable at a glance and the control keeps
 * the thin, outlined look of the rest of the app.
 */
@Composable
private fun ThemeToggle(night: Boolean, onToggleTheme: () -> Unit) {
    val fg = MaterialTheme.colorScheme.onBackground
    val bg = MaterialTheme.colorScheme.background
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, fg.copy(alpha = 0.4f), shape)
            .clickable(onClickLabel = if (night) "Switch to day mode" else "Switch to night mode") {
                onToggleTheme()
            }
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeSegment(text = "DAY", selected = !night, fg = fg, bg = bg, shape = shape)
        ThemeSegment(text = "NIGHT", selected = night, fg = fg, bg = bg, shape = shape)
    }
}

@Composable
private fun ThemeSegment(
    text: String,
    selected: Boolean,
    fg: Color,
    bg: Color,
    shape: Shape,
) {
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) fg else Color.Transparent)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) bg else fg.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val fg = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, fg.copy(alpha = 0.85f)), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = fg.copy(alpha = 0.75f),
        )
        content()
    }
}

/**
 * One download: name, progress, live numbers, and the buttons that act on it.
 * Every card is independent - pause one, cancel another, add a third.
 */
@Composable
private fun DownloadCard(
    state: DownloadUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val fg = MaterialTheme.colorScheme.onBackground
    val determinate = state.totalBytes > 0L && state.phase != DownloadPhase.QUEUED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, fg.copy(alpha = 0.85f)), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = phaseLabel(state),
                style = MaterialTheme.typography.labelSmall,
                color = if (state.phase == DownloadPhase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    fg.copy(alpha = 0.55f)
                },
                modifier = Modifier.padding(start = 12.dp, top = 3.dp),
            )
        }

        if (determinate) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = fg,
                trackColor = fg.copy(alpha = 0.16f),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${(state.progress * 100f).toInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${formatBytes(state.doneBytes)} / ${formatBytes(state.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = fg.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        } else if (state.phase == DownloadPhase.DOWNLOADING || state.phase == DownloadPhase.PREPARING) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = fg,
                trackColor = fg.copy(alpha = 0.16f),
            )
        }

        if (state.phase == DownloadPhase.DOWNLOADING ||
            state.phase == DownloadPhase.PREPARING ||
            state.phase == DownloadPhase.FINALIZING ||
            state.phase == DownloadPhase.PAUSED
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Stat("Speed", formatSpeed(state.speedBps), Modifier.weight(1f))
                Stat("ETA", formatDuration(state.etaSeconds), Modifier.weight(1f))
                if (state.kind == DownloadKind.TORRENT) {
                    Stat("Peers", state.peers.toString(), Modifier.weight(1f))
                } else {
                    Stat("Mode", if (state.dualActive) "Bonded" else "Single", Modifier.weight(1f))
                }
            }
        }

        // Status line for the two radios: this is the bonding working, not a setting.
        if (state.kind == DownloadKind.HTTP && state.links.any { it.active || it.bytes > 0L }) {
            Text(
                text = state.links.joinToString("  \u00b7  ") { link ->
                    val speed = if (link.active) formatSpeed(link.speedBps) else "idle"
                    "${link.label} $speed"
                },
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (state.kind == DownloadKind.TORRENT) {
            state.diagnostics?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.45f),
                )
            }
        }

        if (state.message.isNotBlank()) {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = fg.copy(alpha = 0.6f),
            )
        }
        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.phase == DownloadPhase.COMPLETED) {
            SavedBlock(state = state, onOpenFile = onOpenFile)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.pausable) {
                ActionButton(text = "Pause", onClick = onPause, modifier = Modifier.weight(1f))
                ActionButton(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
            } else if (state.phase == DownloadPhase.QUEUED) {
                ActionButton(text = "Remove", onClick = onRemove, modifier = Modifier.weight(1f))
            } else if (state.resumable) {
                ActionButton(
                    text = if (state.phase == DownloadPhase.PAUSED) "Resume" else "Retry",
                    onClick = onResume,
                    modifier = Modifier.weight(1f),
                    filled = true,
                )
                ActionButton(text = "Remove", onClick = onRemove, modifier = Modifier.weight(1f))
            } else if (state.phase == DownloadPhase.COMPLETED) {
                ActionButton(text = "Remove", onClick = onRemove, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun phaseLabel(state: DownloadUiState): String = when (state.phase) {
    DownloadPhase.QUEUED -> "QUEUED"
    DownloadPhase.PREPARING -> "PREPARING"
    DownloadPhase.DOWNLOADING -> if (state.totalBytes > 0L) "DOWNLOADING" else "FINDING PEERS"
    DownloadPhase.PAUSED -> "PAUSED"
    DownloadPhase.FINALIZING -> "FINISHING"
    DownloadPhase.COMPLETED -> "DONE"
    DownloadPhase.FAILED -> "FAILED"
    DownloadPhase.CANCELLED -> "CANCELLED"
    DownloadPhase.IDLE -> ""
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val fg = MaterialTheme.colorScheme.onBackground
    val bg = MaterialTheme.colorScheme.background
    if (filled) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            elevation = null,
            colors = ButtonDefaults.buttonColors(containerColor = fg, contentColor = bg),
            modifier = modifier.height(44.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, fg.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = fg),
            modifier = modifier.height(44.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    val fg = MaterialTheme.colorScheme.onBackground
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = fg.copy(alpha = 0.55f),
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SavedBlock(state: DownloadUiState, onOpenFile: (String) -> Unit) {
    val fg = MaterialTheme.colorScheme.onBackground
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = state.savedPath ?: state.message,
            style = MaterialTheme.typography.bodySmall,
            color = fg.copy(alpha = 0.7f),
        )
        state.sha256?.let { hash ->
            Text(
                text = hash,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = fg.copy(alpha = 0.45f),
            )
        }
        val uri = state.savedUri
        if (uri != null && uri.startsWith("content://")) {
            TextButton(
                onClick = { onOpenFile(uri) },
                colors = ButtonDefaults.textButtonColors(contentColor = fg),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text("Open file", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.onBackground,
    focusedBorderColor = MaterialTheme.colorScheme.onBackground,
    unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
    focusedLabelColor = MaterialTheme.colorScheme.onBackground,
    unfocusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
    focusedPlaceholderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
)
