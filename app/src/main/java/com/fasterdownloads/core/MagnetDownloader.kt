package com.fasterdownloads.core

import android.content.Context
import android.os.SystemClock
import bt.Bt
import bt.data.file.FileSystemStorage
import bt.dht.DHTConfig
import bt.dht.DHTModule
import bt.runtime.BtClient
import bt.runtime.Config
import bt.torrent.TorrentSessionState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BitTorrent downloads for `magnet:` links, driven by the `bt` library (DHT + trackers +
 * peer wire protocol are all handled there).
 *
 * A magnet link carries only an info-hash, so the first phase has to fetch metadata from
 * the swarm; until then there is no file name and no size, which the UI shows as an
 * indeterminate transfer.
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
    private val metadataReady = AtomicBoolean(false)
    private val speedMeter = SpeedMeter(SPEED_WINDOW_MS)

    @Volatile
    private var client: BtClient? = null

    @Volatile
    private var totalBytes = 0L

    @Volatile
    private var torrentName = ""

    override fun cancel() {
        cancelled.set(true)
        runCatching { client?.stop() }
    }

    override fun run(): DownloadUiState {
        val targetDir = Storage.torrentDir(context)
        var watchdog: Thread? = null
        try {
            emitState {
                it.copy(phase = DownloadPhase.PREPARING, message = "Joining the swarm...")
            }

            // Hashing runs in parallel with the transfer, so give it a few threads.
            val config = object : Config() {
                override fun getNumOfHashingThreads(): Int =
                    (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(2)
            }
            val dhtModule = DHTModule(
                DHTConfig().apply { setShouldUseRouterBootstrap(true) },
            )

            val built = Bt.client()
                .config(config)
                .storage(FileSystemStorage(targetDir.toPath()))
                .magnet(request.url.trim())
                .autoLoadModules()
                .module(dhtModule)
                .stopWhenDownloaded()
                .afterTorrentFetched { torrent ->
                    val size = runCatching { torrent.getSize() }.getOrDefault(0L)
                    val name = runCatching { torrent.getName() }.getOrNull().orEmpty()
                    totalBytes = size
                    torrentName = Storage.sanitizeFileName(name.ifBlank { "torrent" })
                    metadataReady.set(true)
                    emitState {
                        it.copy(
                            phase = DownloadPhase.DOWNLOADING,
                            fileName = torrentName,
                            totalBytes = size,
                            message = "Metadata received - looking for peers...",
                        )
                    }
                }
                .build()

            client = built
            watchdog = startMetadataWatchdog()

            emitState {
                it.copy(
                    phase = DownloadPhase.DOWNLOADING,
                    message = "Fetching metadata from DHT...",
                )
            }

            built.startAsync({ session -> onSessionState(session) }, STATE_INTERVAL_MS).join()

            watchdog.interrupt()

            if (cancelled.get()) {
                return emitState {
                    it.copy(
                        phase = DownloadPhase.CANCELLED,
                        message = "Download cancelled",
                        error = null,
                    )
                }
            }
            return finish(targetDir)
        } catch (t: Throwable) {
            if (cancelled.get()) {
                return emitState {
                    it.copy(
                        phase = DownloadPhase.CANCELLED,
                        message = "Download cancelled",
                        error = null,
                    )
                }
            }
            val reason = firstLine(t.message) ?: t.javaClass.simpleName
            return emitState {
                it.copy(
                    phase = DownloadPhase.FAILED,
                    error = reason,
                    message = "Torrent download failed",
                )
            }
        } finally {
            watchdog?.interrupt()
            runCatching { client?.stop() }
            client = null
        }
    }

    private fun onSessionState(session: TorrentSessionState) {
        if (cancelled.get()) return
        val now = SystemClock.elapsedRealtime()
        val transferred = runCatching { session.getDownloaded() }.getOrDefault(0L)
        speedMeter.add(now, transferred)
        val speed = speedMeter.rate()
        val peers = runCatching { session.getConnectedPeers()?.size ?: 0 }.getOrDefault(0)

        val total = totalBytes
        val left = runCatching { session.getLeft() }.getOrDefault(-1L)
        val done = if (total > 0L && left >= 0L) (total - left).coerceIn(0L, total) else transferred
        val eta = if (speed > 0L && total > done) (total - done) / speed else 0L

        emitState {
            it.copy(
                phase = DownloadPhase.DOWNLOADING,
                totalBytes = total,
                doneBytes = done,
                speedBps = speed,
                etaSeconds = eta,
                peers = peers,
                message = when {
                    total <= 0L -> "Fetching metadata from DHT..."
                    peers <= 0 -> "No peers yet - waiting on trackers/DHT"
                    peers == 1 -> "1 peer connected"
                    else -> "$peers peers connected"
                },
            )
        }
    }

    /**
     * A magnet link with no reachable peers never fires `afterTorrentFetched`, so without a
     * watchdog the UI would sit on "Fetching metadata" forever.
     */
    private fun startMetadataWatchdog(): Thread {
        val task = object : Runnable {
            override fun run() {
                val deadline = SystemClock.elapsedRealtime() + METADATA_TIMEOUT_MS
                while (!cancelled.get() && !metadataReady.get()) {
                    if (SystemClock.elapsedRealtime() > deadline) {
                        emitState {
                            it.copy(
                                phase = DownloadPhase.FAILED,
                                message = "Torrent download failed",
                                error = "No peer returned the torrent metadata. Check the " +
                                    "magnet link, and that DHT or a tracker is reachable.",
                            )
                        }
                        runCatching { client?.stop() }
                        return
                    }
                    try {
                        Thread.sleep(500L)
                    } catch (interrupted: InterruptedException) {
                        return
                    }
                }
            }
        }
        val thread = Thread(task, "fd-magnet-watchdog")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun finish(dir: File): DownloadUiState {
        emitState {
            it.copy(phase = DownloadPhase.FINALIZING, message = "Verifying torrent data...")
        }

        val files = runCatching { dir.walkTopDown().filter { it.isFile }.toList() }
            .getOrDefault(emptyList())
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

    private fun emitState(block: (DownloadUiState) -> DownloadUiState): DownloadUiState {
        val next = synchronized(stateLock) { block(state).also { state = it } }
        runCatching { emit(next) }
        return next
    }

    private companion object {
        const val STATE_INTERVAL_MS = 500L
        const val SPEED_WINDOW_MS = 3000L
        const val METADATA_TIMEOUT_MS = 120_000L
    }
}
