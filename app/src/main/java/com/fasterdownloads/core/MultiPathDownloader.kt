package com.fasterdownloads.core

import android.content.Context
import android.net.NetworkCapabilities
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CancelledException : IOException("Download cancelled")

/**
 * Downloads one file over two radios at the same time.
 *
 * How it works:
 *  1. [NetworkLinks] asks ConnectivityManager for a Wi-Fi [android.net.Network] and a
 *     cellular [android.net.Network] *simultaneously* (Android happily keeps both up).
 *  2. The file size is probed and split into byte ranges ("chunks") using HTTP
 *     `Range` requests.
 *  3. Worker threads are pinned to one network each — every request is opened via
 *     `Network.openConnection`, so its sockets physically leave through that radio.
 *  4. Workers pull chunks from one shared queue, so a slow link never stalls the
 *     download, and the worker counts are nudged towards the requested Wi-Fi/mobile
 *     split (Plexo-style weighting) as real throughput is measured.
 *  5. Progress is journalled to disk, so an interrupted download resumes where it
 *     stopped, and the finished file is SHA-256 hashed before being published.
 */
class MultiPathDownloader(
    private val context: Context,
    private val request: DownloadRequest,
    private val emit: (DownloadUiState) -> Unit,
) {

    private class Chunk(val index: Int, val start: Long, val end: Long) {
        val length: Long get() = end - start + 1L
    }

    private class LinkRuntime(val handle: LinkHandle) {
        val bytes = AtomicLong(0L)
        val workers = AtomicInteger(0)
        val spawned = AtomicInteger(0)

        @Volatile
        var error: String? = null
    }

    private val stateLock = Any()
    private var state = DownloadUiState(url = request.url, fileName = request.fileName)

    private val cancelled = AtomicBoolean(false)
    private val fatal = AtomicReference<String?>(null)
    private val tickerStop = AtomicBoolean(false)

    private val networkLinks = NetworkLinks(context)
    private val runtimes = mutableListOf<LinkRuntime>()
    private val activeConnections: MutableSet<HttpURLConnection> =
        Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    private val doneBytes = AtomicLong(0L)
    private val completedBytes = AtomicLong(0L)
    private val workersAlive = AtomicInteger(0)
    private val workerSeq = AtomicInteger(0)
    private var recoveryAttempts = 0

    private val queueLock = Any()
    private val pending = ArrayDeque<Chunk>()
    private val doneIndices = HashSet<Int>()
    private val attempts = HashMap<Int, Int>()
    private var chunkSize = 0L
    private var totalChunks = 0

    private var totalBytes = 0L
    private var displayName = ""
    private var rangeSupported = true

    private var partFile: File? = null
    private var resumeFile: File? = null
    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private var ticker: Thread? = null
    private var lastResumeSave = 0L

    fun cancel() {
        cancelled.set(true)
        // Dropping the sockets makes an in-flight read fail immediately instead of
        // waiting for the read timeout.
        val open = synchronized(activeConnections) { activeConnections.toList() }
        open.forEach { connection -> runCatching { connection.disconnect() } }
    }

    /** Blocking entry point — call it from a background thread. Returns the final state. */
    fun run(): DownloadUiState {
        try {
            emitState {
                it.copy(
                    phase = DownloadPhase.PREPARING,
                    message = "Requesting Wi-Fi and mobile data links...",
                )
            }
            prepareLinks()
            checkCancelled()

            val info = probeServer()
            totalBytes = if (info.size > 0L) info.size else 0L
            // Without a size there is nothing to split, so fall back to one stream.
            rangeSupported = info.rangeSupported && totalBytes > 0L
            displayName = Storage.sanitizeFileName(
                request.fileName.takeIf { it.isNotBlank() }
                    ?: info.suggestedName
                    ?: "download.bin",
            )

            emitState {
                it.copy(
                    fileName = displayName,
                    totalBytes = totalBytes,
                    rangeSupported = rangeSupported,
                )
            }

            partFile = File(Storage.workingDir(context), "$displayName.part")
            resumeFile = File(Storage.workingDir(context), "$displayName.fdstate")

            startTicker()
            if (rangeSupported) {
                runRangedDownload()
            } else {
                runSingleStreamDownload()
            }
            checkCancelled()
            return finalizeDownload()
        } catch (c: CancelledException) {
            cleanupAfterFailure(keepPartial = true)
            return emitState {
                it.copy(phase = DownloadPhase.CANCELLED, message = "Download cancelled", error = null)
            }
        } catch (t: Throwable) {
            cleanupAfterFailure(keepPartial = true)
            val reason = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            return emitState {
                it.copy(phase = DownloadPhase.FAILED, error = reason, message = "Download failed")
            }
        } finally {
            stopTicker()
            networkLinks.release()
            runCatching { channel?.close() }
            channel = null
            runCatching { raf?.close() }
            raf = null
        }
    }

    // ---------------------------------------------------------------- links

    private fun prepareLinks() {
        val wifi = networkLinks.acquire(
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            id = "wifi",
            label = "Wi-Fi",
            isMobile = false,
        )
        val mobile = if (request.useMobileData) {
            networkLinks.acquire(
                transport = NetworkCapabilities.TRANSPORT_CELLULAR,
                id = "cell",
                label = "Mobile data",
                isMobile = true,
            )
        } else {
            null
        }

        val acquired = mutableListOf<LinkRuntime>()
        wifi?.let { acquired.add(LinkRuntime(it)) }
        mobile?.let { acquired.add(LinkRuntime(it)) }
        if (acquired.isEmpty()) throw IOException("No usable network connection")

        synchronized(runtimes) {
            runtimes.clear()
            runtimes.addAll(acquired)
        }

        val dual = acquired.size > 1
        emitState {
            it.copy(
                dualActive = dual,
                links = acquired.map { runtime ->
                    LinkProgress(
                        id = runtime.handle.id,
                        label = runtime.handle.label,
                        active = true,
                    )
                },
                message = if (dual) {
                    "Bonded Wi-Fi + mobile data"
                } else {
                    "Using ${acquired.first().handle.label} only"
                },
            )
        }
    }

    private fun probeServer(): RemoteInfo {
        var lastError: Throwable? = null
        var answered: RemoteInfo? = null
        for (runtime in snapshotRuntimes()) {
            checkCancelled()
            try {
                val info = HttpUtil.probe(runtime.handle.network, request.url)
                if (info.size > 0L) return info
                if (answered == null) answered = info
            } catch (t: Throwable) {
                runtime.error = t.message
                lastError = t
            }
        }
        return answered ?: throw IOException(lastError?.message ?: "Could not reach the server")
    }

    // ------------------------------------------------------------- transfer

    private fun runRangedDownload() {
        val file = partFile ?: throw IOException("Working file is unavailable")
        chunkSize = ChunkPlan.chunkSize(totalBytes, workerBudget())
        totalChunks = ChunkPlan.chunkCount(totalBytes, chunkSize)

        val randomAccessFile = RandomAccessFile(file, "rw")
        if (randomAccessFile.length() != totalBytes) {
            randomAccessFile.setLength(totalBytes)
        }
        raf = randomAccessFile
        channel = randomAccessFile.channel

        val resumed = loadResumeState()
        buildQueue(resumed)

        emitState {
            it.copy(
                phase = DownloadPhase.DOWNLOADING,
                message = when {
                    resumed.isNotEmpty() -> "Resumed ${resumed.size} finished chunks"
                    runtimes.size > 1 -> "Downloading over Wi-Fi + mobile data"
                    else -> "Downloading over one link"
                },
            )
        }

        startWorkers()
        supervise()

        fatal.get()?.let { throw IOException(it) }
        checkCancelled()

        val missing = synchronized(queueLock) { totalChunks - doneIndices.size }
        if (missing > 0) throw IOException("$missing chunk(s) did not finish")
        if (completedBytes.get() < totalBytes) {
            throw IOException("Only ${completedBytes.get()} of $totalBytes bytes arrived")
        }
        saveResumeState()
        runCatching { randomAccessFile.fd.sync() }
    }

    private fun workerBudget(): Int {
        val perLink = request.connectionsPerNetwork.coerceIn(1, 8)
        return perLink * snapshotRuntimes().size.coerceAtLeast(1)
    }

    private fun buildQueue(resumed: Set<Int>) {
        synchronized(queueLock) {
            pending.clear()
            doneIndices.clear()
            attempts.clear()
            doneIndices.addAll(resumed)
            var alreadyDone = 0L
            for (index in 0 until totalChunks) {
                val chunk = chunkAt(index)
                if (resumed.contains(index)) {
                    alreadyDone += chunk.length
                } else {
                    pending.addLast(chunk)
                }
            }
            doneBytes.set(alreadyDone)
            completedBytes.set(alreadyDone)
        }
    }

    private fun chunkAt(index: Int): Chunk {
        val range = ChunkPlan.bounds(totalBytes, chunkSize, index)
            ?: throw IOException("Chunk $index is outside the file")
        return Chunk(index, range.first, range.last)
    }

    private fun startWorkers() {
        val list = snapshotRuntimes()
        val perLink = request.connectionsPerNetwork.coerceIn(1, 8)
        if (list.size == 1) {
            repeat(perLink.coerceAtLeast(2)) { spawnWorker(list.first()) }
            return
        }
        val wifi = list.firstOrNull { !it.handle.isMobile } ?: return
        val mobile = list.firstOrNull { it.handle.isMobile } ?: return
        val totalWorkers = perLink * list.size
        val (wifiWorkers, mobileWorkers) = ChunkPlan.workerCounts(totalWorkers, request.wifiWeight)
        repeat(wifiWorkers) { spawnWorker(wifi) }
        repeat(mobileWorkers) { spawnWorker(mobile) }
    }

    private fun spawnWorker(runtime: LinkRuntime) {
        workerSeq.incrementAndGet()
        workersAlive.incrementAndGet()
        runtime.spawned.incrementAndGet()
        val thread = Thread({ runWorker(runtime) }, "fd-${runtime.handle.id}-${workerSeq.get()}")
        thread.isDaemon = true
        thread.start()
    }

    private fun runWorker(runtime: LinkRuntime) {
        runtime.workers.incrementAndGet()
        try {
            while (!cancelled.get() && fatal.get() == null) {
                val chunk = nextChunk() ?: break
                var attempt = 0
                while (true) {
                    if (cancelled.get() || fatal.get() != null) {
                        requeue(chunk)
                        return
                    }
                    try {
                        fetchChunk(runtime, chunk)
                        markCompleted(chunk)
                        runtime.error = null
                        break
                    } catch (t: Throwable) {
                        if (cancelled.get()) {
                            requeue(chunk)
                            return
                        }
                        attempt++
                        val total = synchronized(queueLock) {
                            val next = (attempts[chunk.index] ?: 0) + 1
                            attempts[chunk.index] = next
                            next
                        }
                        runtime.error = t.message
                        if (total >= MAX_ATTEMPTS) {
                            fatal.compareAndSet(
                                null,
                                "Gave up on bytes ${chunk.start}-${chunk.end} after $total attempts: ${t.message}",
                            )
                            requeue(chunk)
                            return
                        }
                        try {
                            Thread.sleep(400L * attempt)
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            requeue(chunk)
                            return
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            runtime.error = t.message
        } finally {
            runtime.workers.decrementAndGet()
            workersAlive.decrementAndGet()
        }
    }

    private fun fetchChunk(runtime: LinkRuntime, chunk: Chunk) {
        val connection = HttpUtil.open(
            network = runtime.handle.network,
            url = request.url,
            method = "GET",
            range = "bytes=${chunk.start}-${chunk.end}",
        )
        activeConnections.add(connection)
        var written = 0L
        try {
            val code = connection.responseCode
            if (code == 200) throw IOException("The server ignored the Range header")
            if (code != 206) throw IOException("HTTP $code for bytes ${chunk.start}-${chunk.end}")

            val buffer = ByteArray(BUFFER_SIZE)
            val input = connection.inputStream
            var position = chunk.start
            var remaining = chunk.length
            while (remaining > 0L) {
                if (cancelled.get()) throw CancelledException()
                val requestSize = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, requestSize)
                if (read < 0) throw IOException("Stream ended at byte $position")
                writeAt(buffer, read, position)
                position += read
                remaining -= read
                written += read
                doneBytes.addAndGet(read.toLong())
                runtime.bytes.addAndGet(read.toLong())
            }
        } catch (t: Throwable) {
            if (written > 0L) doneBytes.addAndGet(-written)
            throw t
        } finally {
            activeConnections.remove(connection)
            runCatching { connection.disconnect() }
        }
    }

    private fun writeAt(buffer: ByteArray, count: Int, position: Long) {
        val target = channel ?: throw IOException("The download file is closed")
        val byteBuffer = ByteBuffer.wrap(buffer, 0, count)
        var offset = position
        while (byteBuffer.hasRemaining()) {
            val written = target.write(byteBuffer, offset)
            if (written <= 0) throw IOException("Could not write to storage (is the disk full?)")
            offset += written
        }
    }

    private fun nextChunk(): Chunk? = synchronized(queueLock) { pending.pollFirst() }

    private fun requeue(chunk: Chunk) {
        synchronized(queueLock) { pending.addFirst(chunk) }
    }

    private fun markCompleted(chunk: Chunk) {
        val added = synchronized(queueLock) {
            if (doneIndices.add(chunk.index)) chunk.length else 0L
        }
        if (added > 0L) completedBytes.addAndGet(added)
    }

    /** Keeps the workers alive until the queue drains, and adapts the weight split. */
    private fun supervise() {
        var lastAdapt = SystemClock.elapsedRealtime()
        var lastWifiBytes = 0L
        var lastMobileBytes = 0L
        while (true) {
            if (cancelled.get()) {
                drainWorkers()
                throw CancelledException()
            }
            fatal.get()?.let { reason ->
                drainWorkers()
                throw IOException(reason)
            }

            val empty = synchronized(queueLock) { pending.isEmpty() }
            if (workersAlive.get() <= 0) {
                if (empty) break
                if (!spawnRecoveryWorker()) {
                    throw IOException("Every download worker stopped before the file was complete")
                }
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastAdapt >= ADAPT_INTERVAL_MS) {
                val wifiBytes = runtimeBytes(isMobile = false)
                val mobileBytes = runtimeBytes(isMobile = true)
                adaptWorkerAllocation(wifiBytes - lastWifiBytes, mobileBytes - lastMobileBytes)
                lastWifiBytes = wifiBytes
                lastMobileBytes = mobileBytes
                lastAdapt = now
            }

            try {
                Thread.sleep(60L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancelledException()
            }
        }
    }

    /**
     * Plexo-style weighting: if the measured Wi-Fi share drifts away from the
     * requested split, hand the faster radio another worker (up to a small cap).
     */
    private fun adaptWorkerAllocation(wifiDelta: Long, mobileDelta: Long) {
        val list = snapshotRuntimes()
        if (list.size < 2) return
        val wifi = list.firstOrNull { !it.handle.isMobile } ?: return
        val mobile = list.firstOrNull { it.handle.isMobile } ?: return

        val total = wifiDelta + mobileDelta
        if (total <= 0L) return

        val actualShare = wifiDelta.toDouble() / total.toDouble()
        val targetShare = request.wifiWeight / 100.0
        val cap = request.connectionsPerNetwork.coerceIn(1, 8) + 2

        if (actualShare < targetShare - 0.12 && wifi.spawned.get() < cap) {
            spawnWorker(wifi)
        } else if (actualShare > targetShare + 0.12 && mobile.spawned.get() < cap) {
            spawnWorker(mobile)
        }
    }

    private fun spawnRecoveryWorker(): Boolean {
        if (recoveryAttempts >= MAX_RECOVERY_WORKERS) return false
        val candidate = snapshotRuntimes()
            .filter { it.error == null }
            .maxByOrNull { it.bytes.get() }
            ?: return false
        recoveryAttempts++
        spawnWorker(candidate)
        return true
    }

    private fun drainWorkers() {
        val deadline = SystemClock.elapsedRealtime() + 4000L
        while (workersAlive.get() > 0 && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(25L)
            } catch (interrupted: InterruptedException) {
                break
            }
        }
    }

    // -------------------------------------------------------- single stream

    private fun runSingleStreamDownload() {
        val runtime = snapshotRuntimes().firstOrNull() ?: throw IOException("No usable network connection")
        val file = partFile ?: throw IOException("Working file is unavailable")
        emitState {
            it.copy(
                phase = DownloadPhase.DOWNLOADING,
                message = "This server does not support ranges - using one connection",
            )
        }

        var connection: HttpURLConnection? = null
        try {
            val active = HttpUtil.open(runtime.handle.network, request.url, "GET", null)
            connection = active
            activeConnections.add(active)
            val code = active.responseCode
            if (code < 200 || code > 299) throw IOException("HTTP $code")
            val reported = active.contentLengthLong
            if (reported > 0L) totalBytes = reported

            FileOutputStream(file).use { output ->
                active.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancelled.get()) throw CancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        doneBytes.addAndGet(read.toLong())
                        runtime.bytes.addAndGet(read.toLong())
                    }
                }
            }
        } finally {
            connection?.let { activeConnections.remove(it) }
            runCatching { connection?.disconnect() }
        }

        if (totalBytes <= 0L) {
            totalBytes = file.length()
            emitState { it.copy(totalBytes = totalBytes) }
        }
    }

    // --------------------------------------------------------------- finish

    private fun finalizeDownload(): DownloadUiState {
        val file = partFile ?: throw IOException("Nothing was downloaded")
        emitState {
            it.copy(
                phase = DownloadPhase.FINALIZING,
                message = "Verifying and saving to Downloads...",
                doneBytes = totalBytes,
            )
        }

        runCatching { channel?.force(true) }
        runCatching { channel?.close() }
        channel = null
        runCatching { raf?.close() }
        raf = null

        if (file.length() != totalBytes) {
            throw IOException("Expected $totalBytes bytes but the file has ${file.length()}")
        }
        val hash = if (request.verifyHash) sha256(file) else null
        val published = Storage.publish(context, file, displayName)
        runCatching { file.delete() }
        runCatching { resumeFile?.delete() }
        resumeFile = null

        return emitState {
            it.copy(
                phase = DownloadPhase.COMPLETED,
                doneBytes = totalBytes,
                speedBps = 0L,
                etaSeconds = 0L,
                message = "Saved to ${published.path}",
                savedUri = published.uri,
                savedPath = published.path,
                sha256 = hash,
                links = it.links.map { link -> link.copy(active = false) },
            )
        }
    }

    private fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    } catch (t: Throwable) {
        null
    }

    // --------------------------------------------------------------- resume

    private fun loadResumeState(): Set<Int> {
        val journal = resumeFile ?: return emptySet()
        val file = partFile ?: return emptySet()
        if (!journal.exists() || !file.exists() || file.length() != totalBytes) return emptySet()
        return try {
            val json = JSONObject(journal.readText())
            if (json.optString("url") != request.url) return emptySet()
            if (json.optLong("total") != totalBytes) return emptySet()
            if (json.optLong("chunkSize") != chunkSize) return emptySet()
            val array = json.optJSONArray("done") ?: return emptySet()
            val restored = HashSet<Int>()
            for (index in 0 until array.length()) restored.add(array.getInt(index))
            restored
        } catch (t: Throwable) {
            emptySet()
        }
    }

    private fun saveResumeState() {
        val journal = resumeFile ?: return
        val file = partFile ?: return
        if (!file.exists()) return
        try {
            val done = synchronized(queueLock) { doneIndices.sorted().toList() }
            val json = JSONObject().apply {
                put("url", request.url)
                put("total", totalBytes)
                put("chunkSize", chunkSize)
                put("done", JSONArray(done))
                put("updated", System.currentTimeMillis())
            }
            journal.writeText(json.toString())
        } catch (t: Throwable) {
            // Journalling is best effort; a failed write only costs resumability.
        }
    }

    private fun cleanupAfterFailure(keepPartial: Boolean) {
        runCatching { channel?.close() }
        channel = null
        runCatching { raf?.close() }
        raf = null
        if (keepPartial) {
            if (rangeSupported && totalBytes > 0L) saveResumeState()
        } else {
            runCatching { partFile?.delete() }
            runCatching { resumeFile?.delete() }
        }
    }

    // -------------------------------------------------------------- ticker

    private fun startTicker() {
        val thread = Thread({
            var lastTotal = 0L
            var lastWifi = 0L
            var lastMobile = 0L
            var lastTime = SystemClock.elapsedRealtime()
            while (!tickerStop.get()) {
                try {
                    Thread.sleep(TICK_MS)
                } catch (interrupted: InterruptedException) {
                    break
                }
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - lastTime).coerceAtLeast(1L)
                val total = minOf(doneBytes.get(), totalBytes)
                val wifi = runtimeBytes(isMobile = false)
                val mobile = runtimeBytes(isMobile = true)
                val speed = ((total - lastTotal) * 1000L / elapsed).coerceAtLeast(0L)
                val wifiSpeed = ((wifi - lastWifi) * 1000L / elapsed).coerceAtLeast(0L)
                val mobileSpeed = ((mobile - lastMobile) * 1000L / elapsed).coerceAtLeast(0L)
                lastTotal = total
                lastWifi = wifi
                lastMobile = mobile
                lastTime = now

                val remaining = (totalBytes - total).coerceAtLeast(0L)
                val eta = if (speed > 0L) remaining / speed else 0L

                emitState {
                    it.copy(
                        doneBytes = total,
                        speedBps = speed,
                        etaSeconds = eta,
                        links = buildLinkProgress(wifiSpeed, mobileSpeed),
                    )
                }
                maybeSaveResume(now)
            }
        }, "fd-ticker")
        thread.isDaemon = true
        ticker = thread
        thread.start()
    }

    private fun stopTicker() {
        tickerStop.set(true)
        runCatching { ticker?.join(600L) }
        ticker = null
    }

    private fun maybeSaveResume(now: Long) {
        if (!rangeSupported) return
        if (now - lastResumeSave < RESUME_SAVE_INTERVAL_MS) return
        lastResumeSave = now
        saveResumeState()
    }

    private fun buildLinkProgress(wifiSpeed: Long = 0L, mobileSpeed: Long = 0L): List<LinkProgress> =
        snapshotRuntimes().map { runtime ->
            LinkProgress(
                id = runtime.handle.id,
                label = runtime.handle.label,
                bytes = runtime.bytes.get(),
                speedBps = if (runtime.handle.isMobile) mobileSpeed else wifiSpeed,
                active = runtime.workers.get() > 0,
                error = runtime.error,
            )
        }

    // ------------------------------------------------------------- helpers

    private fun snapshotRuntimes(): List<LinkRuntime> = synchronized(runtimes) { runtimes.toList() }

    private fun runtimeBytes(isMobile: Boolean): Long =
        snapshotRuntimes().firstOrNull { it.handle.isMobile == isMobile }?.bytes?.get() ?: 0L

    private fun checkCancelled() {
        if (cancelled.get()) throw CancelledException()
    }

    private fun emitState(block: (DownloadUiState) -> DownloadUiState): DownloadUiState {
        val next = synchronized(stateLock) { block(state).also { state = it } }
        runCatching { emit(next) }
        return next
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val MAX_ATTEMPTS = 4
        const val MAX_RECOVERY_WORKERS = 8
        const val TICK_MS = 450L
        const val ADAPT_INTERVAL_MS = 4000L
        const val RESUME_SAVE_INTERVAL_MS = 3000L
    }
}
