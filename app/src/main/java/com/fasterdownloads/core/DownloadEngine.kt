package com.fasterdownloads.core

/**
 * A job the foreground service can run: either the dual-network HTTP engine or the
 * BitTorrent engine. Both are blocking - the service calls [run] on a background thread
 * and [cancel] from any thread.
 */
interface DownloadEngine {
    /** Runs to completion and returns the terminal state. */
    fun run(): DownloadUiState

    /** Asks the engine to stop; [run] returns a [DownloadPhase.CANCELLED] state. */
    fun cancel()
}
