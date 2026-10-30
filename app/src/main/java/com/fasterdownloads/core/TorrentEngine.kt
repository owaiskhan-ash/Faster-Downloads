package com.fasterdownloads.core

import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.swig.settings_pack

/**
 * One libtorrent session, shared by every magnet in the app.
 *
 * Why shared: bootstrapping DHT takes the better part of a minute. A session per download
 * paid that cost every single time, which is exactly the "looking for peers to fetch the
 * metadata from" wait. With one warm session the routing table, the tracker cache and the
 * peer connections stay up between downloads, so the next magnet finds peers in seconds.
 *
 * The session starts on first use and stops itself after a minute and a half with no
 * torrents attached, so it is not left doing DHT lookups in the background forever.
 */
object TorrentEngine {

    private const val IDLE_STOP_MS = 90_000L

    private val lock = Any()
    private var manager: SessionManager? = null
    private var users = 0
    private var idleThread: Thread? = null

    /**
     * Returns the running session, starting it if this is the first torrent.
     *
     * Throws [LinkageError] when the native library is missing: callers turn that into a
     * plain-language message instead of letting the app die.
     */
    fun acquire(): SessionManager {
        synchronized(lock) {
            cancelIdleStop()
            val existing = manager
            if (existing != null) {
                users++
                return existing
            }
            val created = SessionManager()
            created.start(sessionParams())
            manager = created
            users++
            return created
        }
    }

    /** Called when a torrent job is done with the session. */
    fun release() {
        synchronized(lock) {
            users = (users - 1).coerceAtLeast(0)
            if (users == 0) scheduleIdleStop()
        }
    }

    /** Starts the session ahead of time so the first magnet does not pay for DHT bootstrap. */
    fun warmUp() {
        runCatching { acquire() }
        runCatching { release() }
    }

    fun dhtNodes(): Long = synchronized(lock) {
        manager?.let { session -> runCatching { session.dhtNodes() }.getOrDefault(0L) } ?: 0L
    }

    fun isRunning(): Boolean = synchronized(lock) { manager != null }

    private fun scheduleIdleStop() {
        if (idleThread != null || manager == null) return
        val thread = Thread({
            var slept = true
            try {
                Thread.sleep(IDLE_STOP_MS)
            } catch (interrupted: InterruptedException) {
                slept = false
            }
            if (slept) {
                var toStop: SessionManager? = null
                synchronized(lock) {
                    if (users == 0) {
                        toStop = manager
                        manager = null
                    }
                    idleThread = null
                }
                // Blocks while the native session winds down - fine on this thread.
                runCatching { toStop?.stop() }
            }
        }, "fd-torrent-idle")
        thread.isDaemon = true
        idleThread = thread
        thread.start()
    }

    private fun cancelIdleStop() {
        idleThread?.interrupt()
        idleThread = null
    }

    /**
     * Settings chosen for one job: find peers fast.
     *
     * * every tracker in the magnet is announced to, in every tier, instead of one per tier
     * * UDP trackers are preferred (they answer in one round trip)
     * * metadata is allowed to be large enough for real multi-file torrents
     * * enough connection slots for several downloads at once
     */
    private fun sessionParams(): SessionParams {
        val settings = SettingsPack()
        runCatching { settings.setEnableDht(true) }
        runCatching { settings.setMaxMetadataSize(4 * 1024 * 1024) }
        runCatching {
            settings.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        }
        runCatching {
            settings.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        }
        runCatching {
            settings.setBoolean(settings_pack.bool_types.prefer_udp_trackers.swigValue(), true)
        }
        runCatching {
            settings.setInteger(settings_pack.int_types.active_limit.swigValue(), 20)
        }
        runCatching {
            settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 10)
        }
        runCatching {
            settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 400)
        }
        return SessionParams(settings)
    }
}
