package com.fasterdownloads.ui

import androidx.compose.foundation.background
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

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()

            SectionCard(title = "FILE") {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("File URL") },
                    placeholder = { Text("https://example.com/big-file.iso") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("Save as (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        url = SAMPLE_URL
                        fileName = "100MB-test.bin"
                    },
                ) {
                    Text("Use a 100 MB test file")
                }
            }

            SectionCard(title = "NETWORKS") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bond Wi-Fi + mobile data", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Runs half the connections over cellular while Wi-Fi keeps the other half.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = useMobileData, onCheckedChange = { useMobileData = it })
                }

                if (useMobileData) {
                    Text(
                        "Wi-Fi share: ${wifiWeight.toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = wifiWeight,
                        onValueChange = { wifiWeight = it },
                        valueRange = 50f..90f,
                        steps = 7,
                    )
                    Text(
                        "Mobile data carries the other ${100 - wifiWeight.toInt()}% of the connections. " +
                            "Adaptive weighting nudges this split towards what each radio actually delivers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    "Connections per network: ${connections.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = connections,
                    onValueChange = { connections = it },
                    valueRange = 1f..8f,
                    steps = 6,
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
                    modifier = Modifier.weight(2f),
                ) {
                    Text(if (state.phase == DownloadPhase.COMPLETED) "Download again" else "Start download")
                }
                OutlinedButton(
                    onClick = { DownloadService.cancel(context) },
                    enabled = state.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
            }

            if (state.phase != DownloadPhase.IDLE) {
                StatusCard(state = state)
            }

            if (state.phase == DownloadPhase.COMPLETED) {
                ResultCard(state = state, onOpenFile = onOpenFile)
            }

            CaveatCard(dualActive = state.dualActive)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Faster Downloads",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "One file, two radios. Chunks are split with HTTP Range requests and pulled " +
                "over Wi-Fi and mobile data at the same time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun StatusCard(state: DownloadUiState) {
    SectionCard(title = "PROGRESS") {
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(6.dp)),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${(state.progress * 100f).toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (state.totalBytes > 0L) {
                    "${formatBytes(state.doneBytes)} / ${formatBytes(state.totalBytes)}"
                } else {
                    formatBytes(state.doneBytes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Stat(label = "Speed", value = formatSpeed(state.speedBps), modifier = Modifier.weight(1f))
            Stat(
                label = "ETA",
                value = formatDuration(state.etaSeconds),
                modifier = Modifier.weight(1f),
            )
            Stat(
                label = "Mode",
                value = if (state.dualActive) "Bonded" else "Single link",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        state.links.forEach { link -> LinkRow(link) }

        if (state.message.isNotBlank()) {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleSmall)
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
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (link.active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = link.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = link.error ?: "${formatBytes(link.bytes)} transferred",
                style = MaterialTheme.typography.labelSmall,
                color = if (link.error == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(text = formatSpeed(link.speedBps), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ResultCard(state: DownloadUiState, onOpenFile: (String) -> Unit) {
    SectionCard(title = "SAVED") {
        Text(
            text = state.savedPath ?: state.message,
            style = MaterialTheme.typography.bodyMedium,
        )
        state.sha256?.let { hash ->
            Text(
                text = "SHA-256",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Button(onClick = { onOpenFile(uri) }) {
                Text("Open file")
            }
        }
    }
}

@Composable
private fun CaveatCard(dualActive: Boolean) {
    SectionCard(title = "GOOD TO KNOW") {
        Text(
            text = "\u2022 The host must support HTTP Range requests; otherwise the app falls " +
                "back to a single connection.\n" +
                "\u2022 Some OEM skins restrict dual-network use, so behaviour varies by device.\n" +
                "\u2022 Mobile data is really used while bonded. Watch your data cap and battery.\n" +
                "\u2022 Interrupted transfers resume from the chunks that already finished.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (dualActive) {
                "Current run is bonded across Wi-Fi and mobile data."
            } else {
                "Connections are pinned with Android's Network API, one socket group per radio."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
