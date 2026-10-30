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
import com.fasterdownloads.core.DownloadPhase
import com.fasterdownloads.core.DownloadRequest
import com.fasterdownloads.core.DownloadUiState
import com.fasterdownloads.core.DownloadEngine
import com.fasterdownloads.core.MagnetDownloader
import com.fasterdownloads.core.MultiPathDownloader
import com.fasterdownloads.core.formatBytes
import com.fasterdownloads.core.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the live download. Android kills background work
 * quickly, and a bonded Wi-Fi + cellular transfer can take a long time, so the
 * engine runs here behind an ongoing notification.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var downloader: DownloadEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen within a few seconds of startForegroundService().
        ensureForeground(DownloadController.state.value)

        when (intent?.action) {
            ACTION_CANCEL -> {
                val engine = downloader
                if (engine != null) {
                    engine.cancel()
                } else {
                    DownloadController.publish(
                        DownloadController.state.value.copy(
                            phase = DownloadPhase.CANCELLED,
                            message = "Download cancelled",
                        ),
                    )
                    finishService(keepNotification = false)
                }
            }
            else -> {
                val url = intent?.getStringExtra(EXTRA_URL)
                if (url.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val request = DownloadRequest(
                    url = url,
                    fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty(),
                    wifiWeight = intent.getIntExtra(EXTRA_WIFI_WEIGHT, 70),
                    connectionsPerNetwork = intent.getIntExtra(EXTRA_CONNECTIONS, 4),
                    useMobileData = intent.getBooleanExtra(EXTRA_USE_MOBILE_DATA, true),
                )
                beginDownload(request)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        downloader?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------ download

    private fun beginDownload(request: DownloadRequest) {
        job?.cancel()
        downloader?.cancel()

        acquireWakeLock()
        DownloadController.publish(
            DownloadUiState(
                phase = DownloadPhase.PREPARING,
                url = request.url,
                fileName = request.fileName,
                message = "Preparing download...",
            ),
        )

        val onState: (DownloadUiState) -> Unit = { state ->
            DownloadController.publish(state)
            updateNotification(state)
        }
        val engine: DownloadEngine = if (request.isMagnet) {
            MagnetDownloader(applicationContext, request, onState)
        } else {
            MultiPathDownloader(applicationContext, request, onState)
        }
        downloader = engine

        job = scope.launch {
            val finalState = engine.run()
            // A newer download may have replaced this engine in the meantime.
            if (downloader !== engine) return@launch
            DownloadController.publish(finalState)
            updateNotification(finalState)
            delay(400L)
            finishService(keepNotification = finalState.phase != DownloadPhase.CANCELLED)
        }
    }

    private fun finishService(keepNotification: Boolean) {
        releaseWakeLock()
        val mode = if (keepNotification) {
            ServiceCompat.STOP_FOREGROUND_DETACH
        } else {
            ServiceCompat.STOP_FOREGROUND_REMOVE
        }
        ServiceCompat.stopForeground(this, mode)
        stopSelf()
    }

    // -------------------------------------------------------- notification

    private fun ensureForeground(state: DownloadUiState) {
        startForeground(NOTIFICATION_ID, buildNotification(state))
    }

    private fun updateNotification(state: DownloadUiState) {
        val percent = (state.progress * 100f).toInt()
        if (state.running && percent == lastNotifiedPercent && state.phase != DownloadPhase.PREPARING) {
            return
        }
        lastNotifiedPercent = percent
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(state)) }
    }

    private fun buildNotification(state: DownloadUiState): Notification {
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
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (state.phase) {
            DownloadPhase.COMPLETED -> "Download complete"
            DownloadPhase.FAILED -> "Download failed"
            DownloadPhase.CANCELLED -> "Download cancelled"
            DownloadPhase.PREPARING -> "Preparing download"
            DownloadPhase.FINALIZING -> "Finishing up"
            else -> state.fileName.ifBlank { "Downloading" }
        }
        val text = when (state.phase) {
            DownloadPhase.DOWNLOADING, DownloadPhase.FINALIZING ->
                "${(state.progress * 100f).toInt()}% of ${formatBytes(state.totalBytes)} - ${formatSpeed(state.speedBps)}"
            DownloadPhase.COMPLETED -> state.savedPath ?: state.message
            DownloadPhase.FAILED -> state.error ?: "Unknown error"
            else -> state.message
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setOngoing(state.running)
            .setAutoCancel(!state.running)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)

        if (state.running) {
            builder.addAction(0, "Cancel", cancelIntent)
        }
        if (state.totalBytes > 0L && state.phase != DownloadPhase.COMPLETED) {
            builder.setProgress(1000, (state.progress * 1000f).toInt(), false)
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

        const val ACTION_CANCEL = "com.fasterdownloads.action.CANCEL"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_FILE_NAME = "extra_file_name"
        private const val EXTRA_WIFI_WEIGHT = "extra_wifi_weight"
        private const val EXTRA_CONNECTIONS = "extra_connections"
        private const val EXTRA_USE_MOBILE_DATA = "extra_use_mobile_data"

        fun start(context: Context, request: DownloadRequest) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, request.url)
                putExtra(EXTRA_FILE_NAME, request.fileName)
                putExtra(EXTRA_WIFI_WEIGHT, request.wifiWeight)
                putExtra(EXTRA_CONNECTIONS, request.connectionsPerNetwork)
                putExtra(EXTRA_USE_MOBILE_DATA, request.useMobileData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply { action = ACTION_CANCEL }
            runCatching { context.startService(intent) }
        }
    }
}
