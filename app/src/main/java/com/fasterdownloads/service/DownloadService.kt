package com.fasterdownloads.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fasterdownloads.MainActivity
import com.fasterdownloads.R
import com.fasterdownloads.core.DownloadController
import com.fasterdownloads.core.DownloadEngine
import com.fasterdownloads.core.DownloadPhase
import com.fasterdownloads.core.DownloadRequest
import com.fasterdownloads.core.DownloadUiState
import com.fasterdownloads.core.MagnetDownloader
import com.fasterdownloads.core.MultiPathDownloader
import com.fasterdownloads.core.formatBytes
import com.fasterdownloads.core.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that owns the running downloads.
 *
 * It holds an engine per job, up to [MAX_CONCURRENT] at a time; anything else waits in
 * [DownloadPhase.QUEUED] and is started as a slot frees up. Paused and finished jobs live in
 * [DownloadController] only - no engine, no service - which is why the list survives the
 * service stopping and why a paused download can be resumed much later.
 */
class DownloadService : Service() {

    private class Runtime(val id: String, val request: DownloadRequest) {
        @Volatile
        var engine: DownloadEngine? = null

        @Volatile
        var job: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimes = ConcurrentHashMap<String, Runtime>()
    private val schedulerLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationKey: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen within a few seconds of startForegroundService().
        ensureForeground()

        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> intent.getStringExtra(EXTRA_ID)?.let { id -> startJob(id) }
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let { id -> pauseJob(id) }
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { id -> cancelJob(id) }
            ACTION_CANCEL_ALL -> cancelAll()
            ACTION_REMOVE -> intent.getStringExtra(EXTRA_ID)?.let { id -> cancelJob(id) }
            else -> Unit
        }

        updateForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runtimes.values.forEach { runtime ->
            runtime.job?.cancel()
            runCatching { runtime.engine?.cancel() }
        }
        runtimes.clear()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------ scheduling

    /** Queues a job (again) and starts as many as the slot budget allows. */
    private fun startJob(id: String) {
        val request = DownloadController.request(id) ?: return
        val existing = runtimes[id]
        if (existing?.engine != null) return

        val runtime = existing ?: Runtime(id, request).also { runtimes[id] = it }
        runtime.engine = null

        val current = DownloadController.state(id)
            ?: DownloadUiState(id = id, url = request.url, fileName = request.fileName)
        DownloadController.update(
            current.copy(
                phase = DownloadPhase.QUEUED,
                message = "Waiting for a free slot",
                speedBps = 0L,
                etaSeconds = 0L,
                error = null,
            ),
        )
        pump()
    }

    private fun pump() {
        synchronized(schedulerLock) {
            var slots = MAX_CONCURRENT - runtimes.values.count { it.engine != null }
            if (slots <= 0) return@synchronized
            val waiting = runtimes.values
                .filter { it.engine == null }
                .sortedBy { it.id }
            for (runtime in waiting) {
                if (slots <= 0) break
                val state = DownloadController.state(runtime.id) ?: continue
                if (state.phase != DownloadPhase.QUEUED) continue
                launchEngine(runtime)
                slots--
            }
        }
        updateForeground()
    }

    private fun launchEngine(runtime: Runtime) {
        val onState: (DownloadUiState) -> Unit = { state ->
            DownloadController.update(state)
            updateForeground()
        }
        val engine: DownloadEngine = if (runtime.request.isMagnet) {
            MagnetDownloader(applicationContext, runtime.request, onState)
        } else {
            MultiPathDownloader(applicationContext, runtime.request, onState)
        }
        runtime.engine = engine
        acquireWakeLock()

        runtime.job = scope.launch {
            val finalState = engine.run()
            runtime.engine = null
            runtime.job = null
            DownloadController.update(finalState)
            // Nothing to keep in memory: paused jobs resume from the controller's request.
            runtimes.remove(runtime.id)
            onEngineFinished()
        }
    }

    private fun onEngineFinished() {
        pump()
        if (runtimes.values.none { it.engine != null }) {
            releaseWakeLock()
            // Let the last state reach the notification before the service goes away.
            updateForeground()
            finishService()
        }
    }

    // --------------------------------------------------------------- controls

    private fun pauseJob(id: String) {
        val runtime = runtimes[id]
        val engine = runtime?.engine
        if (engine != null) {
            engine.pause()
            return
        }
        // Queued behind a slot: just take it out of the queue.
        runtimes.remove(id)
        DownloadController.state(id)?.let { state ->
            DownloadController.update(
                state.copy(phase = DownloadPhase.PAUSED, message = "Paused", speedBps = 0L),
            )
        }
        pump()
        updateForeground()
    }

    private fun cancelJob(id: String) {
        val runtime = runtimes[id]
        val engine = runtime?.engine
        if (engine != null) {
            engine.cancel()
            return
        }
        runtimes.remove(id)
        DownloadController.state(id)?.let { state ->
            DownloadController.update(
                state.copy(phase = DownloadPhase.CANCELLED, message = "Download cancelled", error = null),
            )
        }
        pump()
        updateForeground()
    }

    private fun cancelAll() {
        val engines = runtimes.values.mapNotNull { it.engine }
        runtimes.clear()
        if (engines.isEmpty()) {
            DownloadController.downloads.value
                .filter { it.running }
                .forEach { state ->
                    DownloadController.update(
                        state.copy(phase = DownloadPhase.CANCELLED, message = "Download cancelled"),
                    )
                }
            updateForeground()
            finishService()
            return
        }
        engines.forEach { engine -> runCatching { engine.cancel() } }
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    // ---------------------------------------------------------- notification

    private fun ensureForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    /**
     * One notification for the whole batch. Rebuilt only when something visible changed, so
     * several downloads at 60 updates a second do not hammer the notification manager.
     */
    private fun updateForeground() {
        val states = DownloadController.downloads.value
        val key = states.filter { it.running }
            .joinToString("|") { "${it.id}:${(it.progress * 100f).toInt()}:${it.phase}" } +
            "#${states.size}"
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val states = DownloadController.downloads.value
        val active = states.filter { it.running }
        val paused = states.count { it.phase == DownloadPhase.PAUSED }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val single: DownloadUiState? = active.singleOrNull()
        val totalSpeed = active.sumOf { it.speedBps }
        val (doneBytes, totalBytes) = active.fold(0L to 0L) { acc, state ->
            (acc.first + state.doneBytes) to (acc.second + state.totalBytes)
        }

        val title = when {
            single != null -> single.title.ifBlank { "Downloading" }
            active.size > 1 -> "${active.size} downloads running"
            paused > 0 && states.isEmpty() -> "Downloads paused"
            paused > 0 -> "$paused paused"
            else -> "Faster Downloads"
        }
        val text = when {
            single != null -> when (single.phase) {
                DownloadPhase.COMPLETED -> single.savedPath ?: single.message
                DownloadPhase.FAILED -> single.error ?: "Unknown error"
                DownloadPhase.PREPARING, DownloadPhase.QUEUED -> single.message
                else -> "${(single.progress * 100f).toInt()}% of " +
                    "${formatBytes(single.totalBytes)} - ${formatSpeed(single.speedBps)}"
            }

            active.size > 1 -> "${formatSpeed(totalSpeed)} across ${active.size} downloads"
            else -> states.firstOrNull { it.terminal }?.let { state ->
                "${state.title}: ${state.phase.name.lowercase()}"
            } ?: "No active downloads"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setOngoing(active.isNotEmpty())
            .setAutoCancel(active.isEmpty())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)

        if (active.isNotEmpty()) {
            builder.addAction(0, if (active.size > 1) "Cancel all" else "Cancel", cancelIntent)
        }
        if (totalBytes > 0L && doneBytes >= 0L) {
            builder.setProgress(1000, ((doneBytes.toDouble() / totalBytes) * 1000).toInt(), false)
        } else if (single != null && single.phase != DownloadPhase.COMPLETED) {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_downloads),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_downloads_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    // ------------------------------------------------------------ wake lock

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FasterDownloads:transfer").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4711
        private const val MAX_WAKE_LOCK_MS = 60L * 60L * 1000L

        /** Downloads that may run at the same time; the rest wait as QUEUED. */
        private const val MAX_CONCURRENT = 3

        const val ACTION_START = "com.fasterdownloads.action.START"
        const val ACTION_RESUME = "com.fasterdownloads.action.RESUME"
        const val ACTION_PAUSE = "com.fasterdownloads.action.PAUSE"
        const val ACTION_CANCEL = "com.fasterdownloads.action.CANCEL"
        const val ACTION_CANCEL_ALL = "com.fasterdownloads.action.CANCEL_ALL"
        const val ACTION_REMOVE = "com.fasterdownloads.action.REMOVE"

        private const val EXTRA_ID = "extra_id"

        /** Adds a download to the list and starts it (or queues it). */
        fun start(context: Context, request: DownloadRequest) {
            val id = DownloadController.add(request)
            send(context, ACTION_START, id, foreground = true)
        }

        fun resume(context: Context, id: String) = send(context, ACTION_RESUME, id, foreground = true)

        fun pause(context: Context, id: String) = send(context, ACTION_PAUSE, id, foreground = false)

        fun cancel(context: Context, id: String) = send(context, ACTION_CANCEL, id, foreground = false)

        fun cancelAll(context: Context) = send(context, ACTION_CANCEL_ALL, null, foreground = false)

        private fun send(context: Context, action: String, id: String?, foreground: Boolean) {
            val intent = Intent(context, DownloadService::class.java).apply {
                this.action = action
                if (id != null) putExtra(EXTRA_ID, id)
            }
            runCatching {
                if (foreground) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
