package com.fasterdownloads.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide download state. The foreground service writes it, Compose reads it.
 * Keeping it out of the service makes the UI trivially re-attachable after rotation.
 */
object DownloadController {

    private val mutable = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = mutable.asStateFlow()

    fun publish(update: DownloadUiState) {
        mutable.value = update
    }

    fun reset() {
        mutable.value = DownloadUiState()
    }

    // ---- URLs handed to us by other apps (magnet links, share sheet) ----

    private val incomingUrl = MutableStateFlow<String?>(null)

    /** A magnet link that arrived via an ACTION_VIEW intent and was not consumed yet. */
    val incoming: kotlinx.coroutines.flow.StateFlow<String?> = incomingUrl.asStateFlow()

    fun pushIncoming(url: String) {
        incomingUrl.value = url
    }

    fun clearIncoming() {
        incomingUrl.value = null
    }
}
