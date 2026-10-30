package com.fasterdownloads.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide download list. The foreground service writes it, Compose reads it.
 *
 * Several downloads can be in flight at once, so this is a *list* of snapshots keyed by job
 * id rather than a single state: the list also survives the service going away (a paused or
 * finished job has no engine behind it, but it is still on screen and can be resumed).
 */
object DownloadController {

    private val mutable = MutableStateFlow<List<DownloadUiState>>(emptyList())

    /** Every known download, newest first. */
    val downloads: StateFlow<List<DownloadUiState>> = mutable.asStateFlow()

    /** The request behind each job, kept so a paused job can be started again. */
    private val requests = ConcurrentHashMap<String, DownloadRequest>()

    private val sequence = AtomicInteger(0)

    /** Creates a job in [DownloadPhase.QUEUED] and returns its id. */
    fun add(request: DownloadRequest): String {
        val id = "job-${System.currentTimeMillis()}-${sequence.incrementAndGet()}"
        requests[id] = request.copy(id = id)
        val queued = DownloadUiState(
            id = id,
            phase = DownloadPhase.QUEUED,
            url = request.url,
            fileName = request.fileName,
            message = "Waiting to start",
        )
        mutable.value = listOf(queued) + mutable.value
        return id
    }

    fun request(id: String): DownloadRequest? = requests[id]

    fun state(id: String): DownloadUiState? = mutable.value.firstOrNull { it.id == id }

    /** Replaces the snapshot with this id, or appends it when the id is new. */
    fun update(state: DownloadUiState) {
        if (state.id.isBlank()) return
        val current = mutable.value
        val index = current.indexOfFirst { it.id == state.id }
        mutable.value = if (index >= 0) {
            current.toMutableList().also { it[index] = state }
        } else {
            listOf(state) + current
        }
    }

    /** Forgets a job entirely (used by the Remove button). */
    fun remove(id: String) {
        requests.remove(id)
        mutable.value = mutable.value.filterNot { it.id == id }
    }

    /** Drops everything that is finished, without touching running jobs. */
    fun clearFinished() {
        val removed = mutable.value.filter { it.terminal }.map { it.id }
        removed.forEach { requests.remove(it) }
        mutable.value = mutable.value.filterNot { it.terminal }
    }

    fun reset() {
        requests.clear()
        mutable.value = emptyList()
    }

    // ---- URLs handed to us by other apps (magnet links, share sheet) ----

    private val incomingUrl = MutableStateFlow<String?>(null)

    /** A link that arrived via an ACTION_VIEW intent and was not consumed yet. */
    val incoming: StateFlow<String?> = incomingUrl.asStateFlow()

    fun pushIncoming(url: String) {
        incomingUrl.value = url
    }

    fun clearIncoming() {
        incomingUrl.value = null
    }
}
