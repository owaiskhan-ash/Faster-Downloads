package com.fasterdownloads.core

import android.content.Context
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
 *
 * Bonding: while the session runs, [BondingProxy] offers a loopback SOCKS5 proxy and the
 * session is pointed at it (`applySettings`), so peer and tracker connections leave over both
 * radios. It is applied late and can be switched off at runtime - see [disableBonding] - so a
 * problem with the proxy can never leave a torrent with no peers.
 */
object TorrentEngine {

    private const val IDLE_STOP_MS = 90_000L

    private val lock = Any()
    private var manager: SessionManager? = null
    private var users = 0
    private var idleThread: Thread? = null
    private var bonding: BondingProxy? = null

    /** True while peer connections are going through the bonding proxy. */
    @Volatile
    private var bonded = false

    /** Set once a torrent found no peers through the proxy: stays off for this session. */
    @Volatile
    private var bondingDisabled = false

    /**
     * Returns the running session, starting it if this is the first torrent.
     *
     * Throws [LinkageError] when the native library is missing: callers turn that into a
     * plain-language message instead of letting the app die.
     */
    fun acquire(context: Context): SessionManager {
        val created = synchronized(lock) {
            cancelIdleStop()
            val existing = manager
            if (existing != null) {
                users++
                return existing
            }
            val session = startSession()
            manager = session
            users++
            bonded = false
            bondingDisabled = false
            session
        }
        startBonding(context, created)
        return created
    }

    /** Starts the native session; falls back to plain defaults if settings are rejected. */
    private fun startSession(): SessionManager {
        val tuned = runCatching { SessionManager().also { it.start(sessionParams()) } }
        return tuned.getOrElse {
            SessionManager().also { it.start() }
        }
    }

    /**
     * Brings the bonding proxy up in the background and, once it is listening, points the
     * session at it. Until then torrents run on the default network, which is also what
     * happens when the phone has only one radio.
     */
    private fun startBonding(context: Context, session: SessionManager) {
        val proxy = BondingProxy(context.applicationContext)
        synchronized(lock) { bonding = proxy }
        proxy.startAsync { port ->
            val stillWanted = synchronized(lock) { manager === session && !bondingDisabled }
            if (stillWanted && applyProxySettings(session, port)) {
                synchronized(lock) { bonded = true }
            }
        }
    }

    /** Points libtorrent at the loopback SOCKS5 proxy: peer and tracker TCP go through it. */
    private fun applyProxySettings(session: SessionManager, port: Int): Boolean = runCatching {
        val pack = SettingsPack()
        pack.setInteger(
            settings_pack.int_types.proxy_type.swigValue(),
            settings_pack.proxy_type_t.socks5.swigValue(),
        )
        pack.setString(settings_pack.string_types.proxy_hostname.swigValue(), "127.0.0.1")
        pack.setInteger(settings_pack.int_types.proxy_port.swigValue(), port)
        pack.setBoolean(settings_pack.bool_types.proxy_peer_connections.swigValue(), true)
        pack.setBoolean(settings_pack.bool_types.proxy_tracker_connections.swigValue(), true)
        pack.setBoolean(settings_pack.bool_types.proxy_hostnames.swigValue(), true)
        session.applySettings(pack)
        true
    }.getOrDefault(false)

    /**
     * Turns bonding off for the rest of this session.
     *
     * Called when a torrent cannot connect to a single peer while bonded: the proxy is the
     * newest, least proven part of the path, so the download falls back to the device's default
     * network rather than sitting there. DHT and UDP trackers were never proxied, so discovery
     * keeps working either way.
     */
    fun disableBonding(): Boolean {
        synchronized(lock) {
            val session = manager ?: return false
            if (!bonded || bondingDisabled) return false
            bondingDisabled = true
            bonded = false
            val applied = runCatching {
                val pack = SettingsPack()
                pack.setInteger(
                    settings_pack.int_types.proxy_type.swigValue(),
                    settings_pack.proxy_type_t.none.swigValue(),
                )
                session.applySettings(pack)
                true
            }.getOrDefault(false)
            return applied
        }
    }

    /** True while peer connections are spread over both radios. */
    fun isBonded(): Boolean = bonded

    /** "Wi-Fi 12 conns · mobile 9 conns", or null when bonding is off. */
    fun bondingSummary(): String? = if (bonded) bonding?.describe() else null

    /** Called when a torrent job is done with the session. */
    fun release() {
        synchronized(lock) {
            users = (users - 1).coerceAtLeast(0)
            if (users == 0) scheduleIdleStop()
        }
    }

    /** Starts the session ahead of time so the first magnet does not pay for DHT bootstrap. */
    fun warmUp(context: Context) {
        runCatching { acquire(context) }
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
                var proxyToStop: BondingProxy? = null
                synchronized(lock) {
                    if (users == 0) {
                        toStop = manager
                        manager = null
                        proxyToStop = bonding
                        bonding = null
                        bonded = false
                    }
                    idleThread = null
                }
                runCatching { proxyToStop?.stop() }
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
