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
 * This is the same engine LibreTorrent, FrostWire and TorrDroid run: a native C++ BitTorrent
 * implementation, not a Java reimplementation of one. What that buys, and why the previous
 * Java-only engine could not get past "looking for peers":
 *
 *  * **UDP trackers (BEP 15).** Most public trackers are `udp://`, and the Java engine could
 *    only speak HTTP to trackers.
 *  * **DHT** that actually walks the routing table, re-bootstraps and stores peers, plus
 *    **PeX** and **LSD**, so metadata can arrive from peers discovered after the announce.
 *  * **uTP** and TCP with real connection management, encryption, and NAT-PMP/UPnP.
 *
 * The engine owns the swarm; this class owns the *user-visible* part of the job: it feeds
 * libtorrent the sanitised magnet link, polls the torrent status for progress, and turns that
 * into [DownloadUiState]. Everything shown is a real number from libtorrent (peers, seeds,
 * DHT nodes, rate) - never a guess - so a stuck download says why it is stuck instead of
 * spinning forever.
 *
 * Note: torrent traffic uses the device's default network. Only the HTTP engine can bond
 * Wi-Fi and mobile data, because a swarm's thousands of peer sockets cannot meaningfully be
 * split across two radios.
 */
class MagnetDownloader(
    private val context: Context,
    private val request: DownloadRequest,
    private val emit: (DownloadUiState) -> Unit,
) : DownloadEngine {

    private val stateLock = Any()
    private var state = DownloadUiState(
        url = request.url,
        kind = DownloadKind.TORRENT,
        message = "Reading magnet link...",
    )

    private val cancelled = AtomicBoolean(false)

    /** The native session; created on the engine thread, torn down there too. */
    @Volatile
    private var session: SessionManager? = null

    @Volatile
    private var torrent: TorrentHandle? = null

    @Volatile
    private var totalBytes = 0L

    private var torrentName = ""

    override fun cancel() {
        cancelled.set(true)
        // Unblocking only: the poll loop notices the flag within one tick, and the session
        // itself is stopped by run() on its own thread (stop() blocks).
        runCatching { torrent?.pause() }
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

            manager = SessionManager()
            // start() boots the native session: DHT with bootstrap nodes, the alert loop,
            // tracker/DHT/LSD/PeX managers and the µTP transport.
            manager.start()
            session = manager

            if (cancelled.get()) return cancelledState()

            // The rebuilt URI, not the raw text: see MagnetLink.toMagnetUri().
            manager.download(magnet.toMagnetUri(), targetDir, TorrentFlags.AUTO_MANAGED)

            emitState {
                it.copy(
                    phase = DownloadPhase.DOWNLOADING,
                    message = if (magnet.trackers.isEmpty()) {
                        "Joining the swarm over DHT..."
                    } else {
                        "Announcing to ${magnet.trackers.size} tracker(s) and DHT..."
                    },
                )
            }

            val premature = pump(manager, Sha1Hash.parseHex(magnet.infoHashHex), magnet)
            if (premature != null) return premature
            return finish(targetDir, existing)
        } catch (t: Throwable) {
            if (cancelled.get()) return cancelledState()
            val reason = when (t) {
                // Missing/swapped native library: UnsatisfiedLinkError is a LinkageError,
                // and both mean the engine is absent or built for another ABI - an install
                // problem, not a download problem.
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
            runCatching { torrent?.pause() }
            torrent = null
            session = null
            // Blocks while the native session winds down; this thread is a background one.
            runCatching { manager?.stop() }
        }
    }

    /**
     * Polls the torrent until it finishes, the transfer becomes impossible, or we are
     * cancelled. Returns null when the torrent completed, or the terminal state to return
     * when it did not.
     */
    private fun pump(
        manager: SessionManager,
        hash: Sha1Hash,
        magnet: MagnetLink,
    ): DownloadUiState? {
        val startedAt = SystemClock.elapsedRealtime()
        var discovered: TorrentHandle? = null
        var announcedOnce = false

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

            // A torrent added from a magnet may come in paused; asking once is enough.
            if (!announcedOnce) {
                announcedOnce = true
                runCatching { handle.resume() }
            }

            val hasMetadata = runCatching { status.hasMetadata() }.getOrDefault(false)
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

            val diagnostics = describe(manager, status, magnet, peers, seeds)
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
                return fail(metadataTimeoutMessage(magnet, peers, diagnostics), magnet, status)
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
        return cancelledState()
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
        peers: Int,
        seeds: Int,
    ): String {
        val dhtNodes = runCatching { manager.dhtNodes() }.getOrDefault(0L)
        val parts = mutableListOf<String>()
        parts += "libtorrent ${stateLabel(status)}"
        parts += "DHT $dhtNodes node" + (if (dhtNodes == 1L) "" else "s")
        parts += "$peers peer" + (if (peers == 1) "" else "s")
        if (seeds > 0) parts += "$seeds seed" + (if (seeds == 1) "" else "s")
        if (magnet.trackers.isNotEmpty()) {
            parts += "${magnet.trackers.size} tracker" + (if (magnet.trackers.size == 1) "" else "s")
        }
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

    private fun metadataTimeoutMessage(magnet: MagnetLink, peers: Int, diagnostics: String): String {
        val trackerNote = when {
            magnet.trackers.isEmpty() -> "The link lists no trackers at all, so DHT is the only way in. "
            else -> "The link's ${magnet.trackers.size} tracker(s) have not produced a peer. "
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
                describe(manager, status, magnet, status.numPeers(), status.numSeeds())
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

    private fun cancelledState(): DownloadUiState = emitState {
        it.copy(
            phase = DownloadPhase.CANCELLED,
            message = "Download cancelled",
            error = null,
        )
    }

    private fun finish(dir: File, existing: Set<String>): DownloadUiState {
        emitState {
            it.copy(phase = DownloadPhase.FINALIZING, message = "Finishing the torrent...")
        }

        // Stop libtorrent from touching the payload files while they are being published:
        // pause first, then give its disk thread a moment to flush.
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
                    sha256 = hash,
                    savedUri = published?.uri,
                    savedPath = path,
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

    /** First line of the exception plus its two nearest causes: the useful sentence is never outermost. */
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
        const val METADATA_TIMEOUT_MS = 180_000L
        const val STALL_TIMEOUT_MS = 480_000L
    }
}
