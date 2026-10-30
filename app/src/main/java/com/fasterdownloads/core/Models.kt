package com.fasterdownloads.core

/** Which transport a job uses. */
enum class DownloadKind {
    /** Plain HTTP(S) file, split into ranged chunks across both radios. */
    HTTP,

    /** BitTorrent magnet link, fetched from the swarm. */
    TORRENT,
}

/** Phases of a download. */
enum class DownloadPhase {
    IDLE,
    PREPARING,
    DOWNLOADING,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** Everything the user (or the service) can configure before a download starts. */
data class DownloadRequest(
    val url: String,
    val fileName: String = "",
    /** Share of parallel workers assigned to Wi-Fi when both links are bonded. */
    val wifiWeight: Int = 70,
    /** Base number of parallel range requests per network. */
    val connectionsPerNetwork: Int = 4,
    val useMobileData: Boolean = true,
    val verifyHash: Boolean = true,
) {
    val isMagnet: Boolean
        get() = url.trim().startsWith("magnet:", ignoreCase = true)
}

/** Per-network counters shown in the UI for HTTP downloads. */
data class LinkProgress(
    val id: String,
    val label: String,
    val bytes: Long = 0L,
    val speedBps: Long = 0L,
    val active: Boolean = false,
    val error: String? = null,
)

/** Immutable snapshot of the current download, rendered by Compose. */
data class DownloadUiState(
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val kind: DownloadKind = DownloadKind.HTTP,
    val url: String = "",
    val fileName: String = "",
    val totalBytes: Long = 0L,
    val doneBytes: Long = 0L,
    val speedBps: Long = 0L,
    val etaSeconds: Long = 0L,
    val peers: Int = 0,
    val links: List<LinkProgress> = listOf(
        LinkProgress(id = "wifi", label = "Wi-Fi"),
        LinkProgress(id = "cell", label = "Mobile data"),
    ),
    val message: String = "",
    val error: String? = null,
    val savedUri: String? = null,
    val savedPath: String? = null,
    val sha256: String? = null,
    val dualActive: Boolean = false,
    val rangeSupported: Boolean = true,
) {
    val progress: Float
        get() = if (totalBytes > 0L) {
            (doneBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val running: Boolean
        get() = phase == DownloadPhase.PREPARING ||
            phase == DownloadPhase.DOWNLOADING ||
            phase == DownloadPhase.FINALIZING
}
