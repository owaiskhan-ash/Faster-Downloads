package com.fasterdownloads.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.fasterdownloads.core.LinkProgress
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
    val state by DownloadController.state.collectAsState()
    val incoming by DownloadController.incoming.collectAsState()

    var url by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf("") }
    var useMobileData by rememberSaveable { mutableStateOf(true) }
    var wifiWeight by rememberSaveable { mutableStateOf(70f) }
    var connections by rememberSaveable { mutableStateOf(4f) }

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
    val canStart = (isMagnet || isHttp) && !state.running

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

            Section(title = "Source") {
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
            }

            if (isMagnet) {
                Section(title = "Torrent") {
                    Text(
                        "Magnet links are fetched from the BitTorrent swarm over DHT and " +
                            "trackers. Metadata comes first, so the file name and size appear " +
                            "once a peer answers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = fg.copy(alpha = 0.62f),
                    )
                    Text(
                        "Bonding Wi-Fi + mobile data applies to HTTP downloads only - a swarm's " +
                            "peer sockets all use the default network.",
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.5f),
                    )
                }
            } else {
                Section(title = "Links") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bond Wi-Fi + mobile data", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Half the connections leave over cellular while Wi-Fi keeps the rest.",
                                style = MaterialTheme.typography.bodySmall,
                                color = fg.copy(alpha = 0.6f),
                            )
                        }
                        Switch(
                            checked = useMobileData,
                            onCheckedChange = { useMobileData = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.background,
                                checkedTrackColor = fg,
                                checkedBorderColor = fg,
                                uncheckedThumbColor = fg.copy(alpha = 0.45f),
                                uncheckedTrackColor = Color.Transparent,
                                uncheckedBorderColor = fg.copy(alpha = 0.55f),
                            ),
                        )
                    }

                    if (useMobileData) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Wi-Fi share", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("${wifiWeight.toInt()}%", style = MaterialTheme.typography.titleMedium)
                        }
                        Slider(
                            value = wifiWeight,
                            onValueChange = { wifiWeight = it },
                            valueRange = 50f..90f,
                            steps = 7,
                            colors = sliderColors(),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Connections per network", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        Text("${connections.toInt()}", style = MaterialTheme.typography.titleMedium)
                    }
                    Slider(
                        value = connections,
                        onValueChange = { connections = it },
                        valueRange = 1f..8f,
                        steps = 6,
                        colors = sliderColors(),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        onEnsurePermissions()
                        DownloadService.start(
                            context,
                            DownloadRequest(
                                url = trimmedUrl,
                                fileName = fileName.trim(),
                                wifiWeight = wifiWeight.toInt(),
                                connectionsPerNetwork = connections.toInt(),
                                useMobileData = useMobileData,
                            ),
                        )
                    },
                    enabled = canStart,
                    shape = RoundedCornerShape(12.dp),
                    elevation = null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = fg,
                        contentColor = MaterialTheme.colorScheme.background,
                        disabledContainerColor = fg.copy(alpha = 0.2f),
                        disabledContentColor = MaterialTheme.colorScheme.background.copy(alpha = 0.75f),
                    ),
                    modifier = Modifier
                        .weight(2f)
                        .height(54.dp),
                ) {
                    Text(
                        text = if (state.phase == DownloadPhase.COMPLETED) "Download again" else "Start download",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                OutlinedButton(
                    onClick = { DownloadService.cancel(context) },
                    enabled = state.running,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (state.running) fg else fg.copy(alpha = 0.3f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = fg,
                        disabledContentColor = fg.copy(alpha = 0.35f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge)
                }
            }

            if (state.phase != DownloadPhase.IDLE) {
                ProgressSection(state)
            }

            if (state.phase == DownloadPhase.COMPLETED) {
                SavedSection(state, onOpenFile)
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
                "time; magnet links come from the BitTorrent swarm.",
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

@Composable
private fun ProgressSection(state: DownloadUiState) {
    val fg = MaterialTheme.colorScheme.onBackground
    Section(title = "Progress") {
        val determinate = state.totalBytes > 0L
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
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = fg,
                trackColor = fg.copy(alpha = 0.16f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = if (determinate) "${(state.progress * 100f).toInt()}%" else "--",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (determinate) {
                    "${formatBytes(state.doneBytes)} / ${formatBytes(state.totalBytes)}"
                } else {
                    formatBytes(state.doneBytes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = fg.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Stat("Speed", formatSpeed(state.speedBps), Modifier.weight(1f))
            Stat("ETA", formatDuration(state.etaSeconds), Modifier.weight(1f))
            if (state.kind == DownloadKind.TORRENT) {
                Stat("Peers", state.peers.toString(), Modifier.weight(1f))
            } else {
                Stat("Mode", if (state.dualActive) "Bonded" else "Single", Modifier.weight(1f))
            }
        }

        if (state.kind == DownloadKind.TORRENT) {
            Text(
                text = "BitTorrent - DHT + trackers",
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.55f),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.links.forEach { link -> LinkRow(link) }
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
private fun LinkRow(link: LinkProgress) {
    val fg = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (link.active) fg else fg.copy(alpha = 0.25f)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = link.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = link.error ?: "${formatBytes(link.bytes)} transferred",
                style = MaterialTheme.typography.labelSmall,
                color = if (link.error == null) fg.copy(alpha = 0.55f) else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatSpeed(link.speedBps),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SavedSection(state: DownloadUiState, onOpenFile: (String) -> Unit) {
    val fg = MaterialTheme.colorScheme.onBackground
    Section(title = "Saved") {
        Text(text = state.savedPath ?: state.message, style = MaterialTheme.typography.bodyMedium)

        state.sha256?.let { hash ->
            Text(
                text = "SHA-256",
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.55f),
            )
            Text(
                text = hash,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val uri = state.savedUri
        if (uri != null && uri.startsWith("content://")) {
            Button(
                onClick = { onOpenFile(uri) },
                shape = RoundedCornerShape(12.dp),
                elevation = null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = fg,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
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

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.onBackground,
    activeTrackColor = MaterialTheme.colorScheme.onBackground,
    inactiveTrackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)
