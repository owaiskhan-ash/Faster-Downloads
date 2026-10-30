package com.fasterdownloads.core

import android.content.Context
import android.os.SystemClock
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentStatus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BitTorrent downloads for `magnet:` links, driven by **libtorrent** through libtorrent4j.
 *
 * This is the engine LibreTorrent, FrostWire and TorrDroid run: a native C++ BitTorrent
 * implementation with UDP trackers (BEP-15), DHT, PeX, LSD, µTP and encryption. The session
 * itself is shared and kept warm by [TorrentEngine] - see there for why that matters for the
 * "fetching metadata" wait.
 *
 * This class owns one torrent inside that session: it hands libtorrent the sanitised magnet,
 * forces an immediate announce instead of waiting for the tracker timer, polls the torrent
 * status for progress, and removes the torrent when the job ends (paused data stays on disk).
 *
 * Note: torrent traffic uses the device's default network. Only the HTTP engine can bond
 * Wi-Fi and mobile data, because a swarm's peer sockets cannot meaningfully be split across
 * two radios.
 */
class MagnetDownloader(
    private val context: Context,
    private val request: DownloadRequest,
    private val emit: (DownloadUiState) -> Unit,
) : DownloadEngine {

    private val stateLock = Any()
    private var state = DownloadUiState(
        id = request.id,
        url = request.url,
        kind = DownloadKind.TORRENT,
        message = "Reading magnet link...",
    )

    private val cancelled = AtomicBoolean(false)

    /** Distinguishes "stop, I will resume later" from "throw this away". */
    private val paused = AtomicBoolean(false)

    @Volatile
    private var session: SessionManager? = null

    @Volatile
    private var torrent: TorrentHandle? = null

    @Volatile
    private var totalBytes = 0L

    private var torrentName = ""

    override fun cancel() {
        cancelled.set(true)
        // Unblocking only: the poll loop notices the flag within one tick, and the torrent
        // is removed by run() on its own thread.
        runCatching { torrent?.pause() }
    }

    override fun pause() {
        paused.set(true)
        cancel()
    }

    override fun run(): DownloadUiState {
        val targetDir = Storage.torrentDir(context)
        // Files already in the torrent folder from earlier downloads: only the new ones
        // belong to this torrent when it is time to publish.
        val existing = runCatching {
            targetDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        }.getOrDefault(emptySet())

        var manager: SessionManager? = null
        try {
            val magnet = MagnetLink.parse(request.url)
            torrentName = magnet.displayName?.let { Storage.sanitizeFileName(it) }.orEmpty()

            emitState {
                it.copy(
                    phase = DownloadPhase.PREPARING,
                    fileName = torrentName.ifBlank { it.fileName },
                    message = "Starting the BitTorrent engine...",
                )
            }

            // Shared, already-warm session: DHT is up before the magnet is even added.
            manager = TorrentEngine.acquire()
            session = manager

            if (cancelled.get()) return stopState()

            // A link with one or two trackers gets a few open ones appended, so peer
            // discovery is not a single point of failure. Links with their own healthy
            // tracker list are left untouched.
            val extras = DefaultTrackers.extrasFor(magnet.trackers)
            manager.download(magnet.toMagnetUri(extras), targetDir, TorrentFlags.AUTO_MANAGED)

            emitState {
                it.copy(
                    phase = DownloadPhase.DOWNLOADING,
                    message = if (magnet.trackers.isEmpty()) {
                        "Joining the swarm over DHT..."
                    } else {
                        "Announcing to ${magnet.trackers.size + extras.size} trackers and DHT..."
                    },
                )
            }

            val premature = pump(manager, Sha1Hash.parseHex(magnet.infoHashHex), magnet, extras)
            if (premature != null) return premature
            return finish(targetDir, existing)
        } catch (t: Throwable) {
            if (cancelled.get()) return stopState()
            val reason = when (t) {
                // UnsatisfiedLinkError is a LinkageError: the native engine is absent or
                // built for another ABI - an install problem, not a download problem.
                is LinkageError -> "The torrent engine could not start on this device " +
                    "(${describeFailure(t)}). Reinstall from the official APK - the engine " +
                    "is native code and this build ships arm64/armv7."

                else -> describeFailure(t)
            }
            return emitState {
                it.copy(
                    phase = DownloadPhase.FAILED,
                    error = reason,
                    message = if (t is MagnetFormatException) {
                        "That magnet link could not be read"
                    } else {
                        "Torrent download failed"
                    },
                )
            }
        } finally {
            detachTorrent()
            session = null
            // Give the session back to the shared pool (it stops itself once idle).
            runCatching { TorrentEngine.release() }
        }
    }

    /** Removes this torrent from the session, keeping whatever is already on disk. */
    private fun detachTorrent() {
        val handle = torrent
        val manager = session
        torrent = null
        if (handle != null && manager != null) {
            runCatching { handle.pause() }
            runCatching { manager.remove(handle) }
        }
    }

    /**
     * Polls the torrent until it finishes, the transfer becomes impossible, or the user
     * stops it. Returns null when the torrent completed, or the terminal state to return
     * when it did not.
     */
    private fun pump(
        manager: SessionManager,
        hash: Sha1Hash,
        magnet: MagnetLink,
        extras: List<String>,
    ): DownloadUiState? {
        val startedAt = SystemClock.elapsedRealtime()
        var discovered: TorrentHandle? = null
        var prepared = false
        var lastReannounce = 0L

        while (!cancelled.get()) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt

            // Torrents are added asynchronously, so the handle shows up a moment later.
            var candidate = discovered
            if (candidate == null) {
                candidate = runCatching { manager.find(hash) }.getOrNull()
                if (candidate != null) {
                    discovered = candidate
                    torrent = candidate
                }
            }
            val handle = candidate
            if (handle == null) {
                if (elapsed > HANDLE_TIMEOUT_MS) {
                    return fail(
                        "The engine accepted the magnet link but never created a download " +
                            "for it. That happens when the link's info-hash is malformed - " +
                            "try copying the link again.",
                        magnet,
                        null,
                    )
                }
                sleep(POLL_INTERVAL_MS)
                continue
            }

            val status = runCatching { handle.status() }.getOrNull()
            if (status == null) {
                sleep(POLL_INTERVAL_MS)
                continue
            }

            val hasMetadata = runCatching { status.hasMetadata() }.getOrDefault(false)

            if (!prepared) {
                prepared = true
                // Do not wait for libtorrent's announce timer: ask every tracker the moment
                // the torrent exists. This is the biggest single win on metadata latency.
                runCatching { handle.resume() }
                runCatching { handle.forceReannounce() }
                lastReannounce = elapsed
            } else if (!hasMetadata && elapsed - lastReannounce > REANNOUNCE_INTERVAL_MS) {
                // Still no metadata: keep the tracker announce going while DHT catches up.
                lastReannounce = elapsed
                runCatching { handle.forceReannounce() }
            }

            if (hasMetadata) {
                val wanted = runCatching { status.totalWanted() }.getOrDefault(0L)
                val total = if (wanted > 0L) wanted else runCatching { status.total() }.getOrDefault(0L)
                if (total > 0L) totalBytes = total
                val name = runCatching { status.name() }.getOrNull()
                if (!name.isNullOrBlank()) torrentName = Storage.sanitizeFileName(name)
            }

            val done = if (hasMetadata) {
                runCatching { status.totalWantedDone() }.getOrDefault(0L)
            } else {
                // Metadata does not count as payload; show the transfer as indeterminate.
                runCatching { status.totalPayloadDownload() }.getOrDefault(0L).coerceAtLeast(0L)
            }
            val peers = runCatching { status.numPeers() }.getOrDefault(0)
            val seeds = runCatching { status.numSeeds() }.getOrDefault(0)
            val rate = runCatching { status.downloadPayloadRate() }.getOrDefault(0).toLong()
            val finished = runCatching { status.isFinished() }.getOrDefault(false) ||
                (hasMetadata && totalBytes > 0L && done >= totalBytes)

            val diagnostics = describe(manager, status, magnet, extras, peers, seeds)
            val eta = if (rate > 0L && totalBytes > done) (totalBytes - done) / rate else 0L

            emitState {
                it.copy(
                    phase = DownloadPhase.DOWNLOADING,
                    fileName = torrentName.ifBlank { it.fileName },
                    totalBytes = if (hasMetadata) totalBytes else 0L,
                    doneBytes = done,
                    // libtorrent's own rate is already smoothed over several seconds, so
                    // the number does not flicker between ticks.
                    speedBps = rate,
                    etaSeconds = eta,
                    peers = peers,
                    diagnostics = diagnostics,
                    message = messageFor(hasMetadata, peers, seeds, finished),
                )
            }

            if (finished) return null

            if (!hasMetadata && elapsed > METADATA_TIMEOUT_MS) {
                return fail(metadataTimeoutMessage(magnet, extras, peers, diagnostics), magnet, status)
            }
            if (hasMetadata && done <= 0L && elapsed > STALL_TIMEOUT_MS) {
                return fail(
                    "The file list arrived, but not a single byte of the download did. " +
                        "No peer is uploading to this device - the swarm may be dead, or " +
                        "the network may block peer connections.",
                    magnet,
                    status,
                )
            }

            sleep(POLL_INTERVAL_MS)
        }
        return stopState()
    }

    private fun messageFor(hasMetadata: Boolean, peers: Int, seeds: Int, finished: Boolean): String =
        when {
            finished -> "Download complete"
            !hasMetadata && peers <= 0 -> "Looking for peers to fetch the metadata from..."
            !hasMetadata && peers == 1 -> "Metadata request sent to 1 peer..."
            !hasMetadata -> "Metadata request sent to $peers peers..."
            seeds > 0 && peers > seeds -> "Downloading from $peers peers ($seeds seeds)"
            peers <= 0 -> "Metadata received - waiting for peers to connect"
            peers == 1 -> "Downloading from 1 peer"
            else -> "Downloading from $peers peers"
        }

    /** Real engine numbers, in the card: what the swarm actually looks like right now. */
    private fun describe(
        manager: SessionManager,
        status: TorrentStatus,
        magnet: MagnetLink,
        extras: List<String>,
        peers: Int,
        seeds: Int,
    ): String {
        val dhtNodes = runCatching { manager.dhtNodes() }.getOrDefault(0L)
        val parts = mutableListOf<String>()
        parts += "libtorrent ${stateLabel(status)}"
        parts += "DHT $dhtNodes node" + (if (dhtNodes == 1L) "" else "s")
        parts += "$peers peer" + (if (peers == 1) "" else "s")
        if (seeds > 0) parts += "$seeds seed" + (if (seeds == 1) "" else "s")
        val trackers = magnet.trackers.size + extras.size
        if (trackers > 0) parts += "$trackers tracker" + (if (trackers == 1) "" else "s")
        return parts.joinToString(" \u00b7 ")
    }

    private fun stateLabel(status: TorrentStatus): String = when (status.state()) {
        TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> "checking files"
        TorrentStatus.State.DOWNLOADING_METADATA -> "fetching metadata"
        TorrentStatus.State.DOWNLOADING -> "downloading"
        TorrentStatus.State.FINISHED -> "finishing"
        TorrentStatus.State.SEEDING -> "seeding"
        else -> "waiting"
    }

    private fun metadataTimeoutMessage(
        magnet: MagnetLink,
        extras: List<String>,
        peers: Int,
        diagnostics: String,
    ): String {
        val trackers = magnet.trackers.size + extras.size
        val trackerNote = when {
            trackers == 0 -> "The link lists no trackers at all, so DHT is the only way in. "
            else -> "None of the $trackers tracker(s) produced a peer. "
        }
        return buildString {
            append("No peer returned the torrent metadata within ")
            append(METADATA_TIMEOUT_MS / 60_000)
            append(" minutes. ")
            append(trackerNote)
            if (peers <= 0) append("Nothing connected back - the swarm may be empty or the network blocks peer traffic. ")
            append("Engine state: $diagnostics.")
        }
    }

    private fun fail(reason: String, magnet: MagnetLink, status: TorrentStatus?): DownloadUiState {
        val manager = session
        val diagnostics = if (status != null && manager != null) {
            runCatching {
                describe(manager, status, magnet, emptyList(), status.numPeers(), status.numSeeds())
            }.getOrNull()
        } else {
            null
        }
        return emitState {
            it.copy(
                phase = DownloadPhase.FAILED,
                speedBps = 0L,
                etaSeconds = 0L,
                diagnostics = diagnostics ?: it.diagnostics,
                message = "Torrent download failed",
                error = reason,
            )
        }
    }

    /** Paused keeps the numbers it had; cancelled says so. */
    private fun stopState(): DownloadUiState = emitState {
        if (paused.get()) {
            it.copy(
                phase = DownloadPhase.PAUSED,
                speedBps = 0L,
                etaSeconds = 0L,
                message = if (it.totalBytes > 0L) {
                    "Paused at ${(it.progress * 100f).toInt()}% - tap resume to continue"
                } else {
                    "Paused - tap resume to keep looking for peers"
                },
                error = null,
            )
        } else {
            it.copy(phase = DownloadPhase.CANCELLED, message = "Download cancelled", error = null)
        }
    }

    private fun finish(dir: File, existing: Set<String>): DownloadUiState {
        emitState {
            it.copy(phase = DownloadPhase.FINALIZING, message = "Finishing the torrent...")
        }

        // Stop libtorrent from touching the payload files while they are being published.
        runCatching { torrent?.pause() }
        sleep(300L)

        val files = runCatching {
            dir.walkTopDown()
                .filter { it.isFile && it.absolutePath !in existing }
                .toList()
        }.getOrDefault(emptyList())
        val single = files.singleOrNull()

        if (single != null) {
            val hash = Hashing.sha256(single)
            val published = runCatching { Storage.publish(context, single, single.name) }.getOrNull()
            if (published != null) runCatching { single.delete() }
            val path = published?.path ?: single.absolutePath
            return emitState {
                it.copy(
                    phase = DownloadPhase.COMPLETED,
                    doneBytes = totalBytes,
                    speedBps = 0L,
                    etaSeconds = 0L,
                    savedUri = published?.uri,
                    savedPath = path,
                    sha256 = hash,
                    message = "Saved to $path",
                )
            }
        }

        // Multi-file torrents stay together inside the app's torrent folder.
        return emitState {
            it.copy(
                phase = DownloadPhase.COMPLETED,
                doneBytes = totalBytes,
                speedBps = 0L,
                etaSeconds = 0L,
                savedPath = dir.absolutePath,
                message = "Saved ${files.size} files to the app's torrent folder",
            )
        }
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** First line of the exception plus its two nearest causes. */
    private fun describeFailure(t: Throwable): String {
        val parts = generateSequence(t) { cause -> cause.cause }
            .map { cause -> firstLine(cause.message)?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName }
            .distinct()
            .take(3)
            .toList()
        return parts.joinToString(" -> ").ifBlank { t.javaClass.simpleName }
    }

    private fun emitState(block: (DownloadUiState) -> DownloadUiState): DownloadUiState {
        val next = synchronized(stateLock) { block(state).also { state = it } }
        runCatching { emit(next) }
        return next
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
        const val HANDLE_TIMEOUT_MS = 15_000L
        const val REANNOUNCE_INTERVAL_MS = 15_000L
        const val METADATA_TIMEOUT_MS = 180_000L
        const val STALL_TIMEOUT_MS = 480_000L
    }
}
