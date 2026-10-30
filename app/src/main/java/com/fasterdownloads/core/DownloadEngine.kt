package com.fasterdownloads.core

/**
 * A job the foreground service can run: either the dual-network HTTP engine or the
 * BitTorrent engine. Both are blocking - the service calls [run] on a background thread
 * and [cancel] / [pause] from any thread.
 */
interface DownloadEngine {
    /** Runs to completion and returns the terminal state. */
    fun run(): DownloadUiState

    /** Asks the engine to stop; [run] returns a [DownloadPhase.CANCELLED] state. */
    fun cancel()

    /**
     * Asks the engine to stop *and leave its partial data resumable*; [run] returns a
     * [DownloadPhase.PAUSED] state and the next [run] on a fresh engine picks up where this
     * one stopped.
     */
    fun pause()
}
