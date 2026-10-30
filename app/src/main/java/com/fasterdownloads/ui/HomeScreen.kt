package com.fasterdownloads.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fasterdownloads.core.DownloadController
import com.fasterdownloads.core.DownloadPhase
import com.fasterdownloads.core.DownloadRequest
import com.fasterdownloads.core.DownloadUiState
import com.fasterdownloads.core.LinkProgress
import com.fasterdownloads.core.formatBytes
import com.fasterdownloads.core.formatDuration
import com.fasterdownloads.core.formatSpeed
import com.fasterdownloads.service.DownloadService
import com.fasterdownloads.ui.theme.Ink
import com.fasterdownloads.ui.theme.Neon

private const val SAMPLE_URL = "https://speed.hetzner.de/100MB.bin"

@Composable
fun FasterDownloadsApp(
    onOpenFile: (String) -> Unit,
    onEnsurePermissions: () -> Unit,
) {
    val context = LocalContext.current
    val state by DownloadController.state.collectAsState()

    var url by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf("") }
    var useMobileData by rememberSaveable { mutableStateOf(true) }
    var wifiWeight by rememberSaveable { mutableStateOf(70f) }
    var connections by rememberSaveable { mutableStateOf(4f) }

    val trimmedUrl = url.trim()
    val urlIsValid = trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")
    val canStart = urlIsValid && !state.running

    Scaffold(containerColor = Neon) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Header()

            Section(title = "Source") {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("File URL") },
                    placeholder = { Text("https://example.com/big-file.iso") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("Save as (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        url = SAMPLE_URL
                        fileName = "100MB-test.bin"
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Ink),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text("Use a 100 MB test file", style = MaterialTheme.typography.labelLarge)
                }
            }

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
                            color = Ink.copy(alpha = 0.6f),
                        )
                    }
                    Switch(
                        checked = useMobileData,
                        onCheckedChange = { useMobileData = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Neon,
                            checkedTrackColor = Ink,
                            checkedBorderColor = Ink,
                            uncheckedThumbColor = Ink.copy(alpha = 0.45f),
                            uncheckedTrackColor = Color.Transparent,
                            uncheckedBorderColor = Ink.copy(alpha = 0.55f),
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
                        Text(
                            "${wifiWeight.toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                        )
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
                    Text(
                        "${connections.toInt()}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Slider(
                    value = connections,
                    onValueChange = { connections = it },
                    valueRange = 1f..8f,
                    steps = 6,
                    colors = sliderColors(),
                )
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
                        containerColor = Ink,
                        contentColor = Neon,
                        disabledContainerColor = Ink.copy(alpha = 0.2f),
                        disabledContentColor = Neon.copy(alpha = 0.75f),
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
                    border = BorderStroke(
                        1.dp,
                        if (state.running) Ink else Ink.copy(alpha = 0.3f),
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Ink,
                        disabledContentColor = Ink.copy(alpha = 0.35f),
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
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "MULTIPATH DOWNLOADER",
            style = MaterialTheme.typography.labelMedium,
            color = Ink.copy(alpha = 0.75f),
        )
        Text(
            text = "Faster\nDownloads",
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = "One file, two radios. Chunks are split with HTTP Range requests and " +
                "pulled over Wi-Fi and mobile data at the same time.",
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.copy(alpha = 0.62f),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, Ink.copy(alpha = 0.85f)), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Ink.copy(alpha = 0.75f),
        )
        content()
    }
}

@Composable
private fun ProgressSection(state: DownloadUiState) {
    Section(title = "Progress") {
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = Ink,
            trackColor = Ink.copy(alpha = 0.16f),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "${(state.progress * 100f).toInt()}%",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (state.totalBytes > 0L) {
                    "${formatBytes(state.doneBytes)} / ${formatBytes(state.totalBytes)}"
                } else {
                    formatBytes(state.doneBytes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = Ink.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Stat("Speed", formatSpeed(state.speedBps), Modifier.weight(1f))
            Stat("ETA", formatDuration(state.etaSeconds), Modifier.weight(1f))
            Stat("Mode", if (state.dualActive) "Bonded" else "Single", Modifier.weight(1f))
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.links.forEach { link -> LinkRow(link) }
        }

        if (state.message.isNotBlank()) {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = Ink.copy(alpha = 0.6f),
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Ink.copy(alpha = 0.55f),
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LinkRow(link: LinkProgress) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (link.active) Ink else Ink.copy(alpha = 0.25f)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = link.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = link.error ?: "${formatBytes(link.bytes)} transferred",
                style = MaterialTheme.typography.labelSmall,
                color = if (link.error == null) Ink.copy(alpha = 0.55f) else MaterialTheme.colorScheme.error,
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
    Section(title = "Saved") {
        Text(text = state.savedPath ?: state.message, style = MaterialTheme.typography.bodyMedium)

        state.sha256?.let { hash ->
            Text(
                text = "SHA-256",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.copy(alpha = 0.55f),
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
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Neon),
            ) {
                Text("Open file", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    cursorColor = Ink,
    focusedBorderColor = Ink,
    unfocusedBorderColor = Ink.copy(alpha = 0.45f),
    focusedLabelColor = Ink,
    unfocusedLabelColor = Ink.copy(alpha = 0.55f),
    focusedPlaceholderColor = Ink.copy(alpha = 0.4f),
    unfocusedPlaceholderColor = Ink.copy(alpha = 0.4f),
)

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Ink,
    activeTrackColor = Ink,
    inactiveTrackColor = Ink.copy(alpha = 0.2f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)
