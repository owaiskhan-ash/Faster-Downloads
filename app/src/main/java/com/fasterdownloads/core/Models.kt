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

    /** Waiting for a free slot: the app runs a few downloads at once, not all of them. */
    QUEUED,
    PREPARING,
    DOWNLOADING,

    /** Stopped by the user. The partial file and its journal stay on disk. */
    PAUSED,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * Everything the user (or the service) can configure before a download starts.
 *
 * There is deliberately nothing here to turn bonding down: two radios are the point of the
 * app, so HTTP downloads always split 50/50 across Wi-Fi and mobile data with the maximum
 * number of parallel range requests on each.
 */
data class DownloadRequest(
    /** Job id, filled in by [DownloadController.add]. */
    val id: String = "",
    val url: String,
    val fileName: String = "",
    /** Share of parallel workers assigned to Wi-Fi when both links are bonded. */
    val wifiWeight: Int = 50,
    /** Base number of parallel range requests per network. */
    val connectionsPerNetwork: Int = 8,
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

/** Immutable snapshot of one download, rendered by Compose. */
data class DownloadUiState(
    /** Stable job id: several downloads run at once, so state is keyed by this. */
    val id: String = "",
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
    /** Engine-level detail for the card: tracker answers, swarm state. */
    val diagnostics: String? = null,
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

    /** Occupies an engine right now (or is waiting for one). */
    val running: Boolean
        get() = phase == DownloadPhase.QUEUED ||
            phase == DownloadPhase.PREPARING ||
            phase == DownloadPhase.DOWNLOADING ||
            phase == DownloadPhase.FINALIZING

    /** Finished, one way or another: no engine needed any more. */
    val terminal: Boolean
        get() = phase == DownloadPhase.COMPLETED ||
            phase == DownloadPhase.FAILED ||
            phase == DownloadPhase.CANCELLED

    /** Can be stopped by the user. */
    val pausable: Boolean
        get() = phase == DownloadPhase.PREPARING ||
            phase == DownloadPhase.DOWNLOADING ||
            phase == DownloadPhase.FINALIZING

    /** Can be started (again) by the user. */
    val resumable: Boolean
        get() = phase == DownloadPhase.PAUSED ||
            phase == DownloadPhase.FAILED ||
            phase == DownloadPhase.CANCELLED

    /** A human name for the card: file name, then the URL. */
    val title: String
        get() = fileName.ifBlank { url }
}
