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

    /** Bonding retries: how many times, and how far apart. */
    private const val BONDING_RETRIES = 6
    private const val BONDING_RETRY_INTERVAL_MS = 20_000L

    /** The proxy must have failed at least this many connections, with zero successes, to be
     *  considered broken. */
    private const val MIN_PROXY_ATTEMPTS = 3

    private val lock = Any()
    private var manager: SessionManager? = null
    private var users = 0
    private var idleThread: Thread? = null
    private var bonding: BondingProxy? = null

    /** True while peer connections are going through the bonding proxy. */
    @Volatile
    private var bonded = false

    /** Set once the proxy itself failed to open a single connection: off for this session. */
    @Volatile
    private var bondingDisabled = false

    private var retryThread: Thread? = null

    /** The session refused the proxy setting (verification after applySettings failed). */
    @Volatile
    private var bondingApplyFailed = false

    /**
     * Returns the running session, starting it if this is the first torrent.
     *
     * Throws [LinkageError] when the native library is missing: callers turn that into a
     * plain-language message instead of letting the app die.
     */
    fun acquire(context: Context): SessionManager {
        val session: SessionManager
        val tryBonding: Boolean
        synchronized(lock) {
            cancelIdleStop()
            val existing = manager
            if (existing != null) {
                users++
                session = existing
                tryBonding = !bonded && !bondingDisabled
            } else {
                session = startSession()
                manager = session
                users++
                bonded = false
                bondingDisabled = false
                bondingApplyFailed = false
                bonding = BondingProxy(context.applicationContext)
                tryBonding = true
            }
        }
        // Bonding is attempted for *every* torrent, not just the first one of a session: the
        // first attempt often happens before mobile data is reachable (the app warms the
        // session up at launch), and before this it was never retried - which is exactly the
        // "magnet links are not bonded" case.
        if (tryBonding) startBonding(context, session)
        return session
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
        val proxy = synchronized(lock) {
            bonding ?: BondingProxy(context.applicationContext).also { bonding = it }
        }

        proxy.startAsync { port ->
            val stillWanted = synchronized(lock) { manager === session && !bondingDisabled }
            if (!stillWanted) return@startAsync
            if (applyProxySettings(session, port)) {
                synchronized(lock) { bonded = true }
            } else {
                bondingApplyFailed = true
            }
        }

        scheduleBondingRetry(context, session)
    }

    /**
     * Keeps trying for a couple of minutes when the radios are not both up yet.
     *
     * Mobile data commonly appears a few seconds after launch (or after the cell radio wakes
     * up), and one failed attempt used to be the end of it for the whole session. The proxy has
     * its own cooldown, so this cannot hammer ConnectivityManager.
     */
    private fun scheduleBondingRetry(context: Context, session: SessionManager) {
        synchronized(lock) {
            if (retryThread != null) return
        }
        val thread = Thread({
            try {
                repeat(BONDING_RETRIES) {
                    try {
                        Thread.sleep(BONDING_RETRY_INTERVAL_MS)
                    } catch (interrupted: InterruptedException) {
                        return@Thread
                    }
                    val waiting = synchronized(lock) { manager === session && !bonded && !bondingDisabled }
                    if (!waiting) return@Thread
                    val proxy = synchronized(lock) { bonding } ?: return@Thread
                    proxy.startAsync { port ->
                        val stillWanted = synchronized(lock) { manager === session && !bondingDisabled }
                        if (stillWanted && applyProxySettings(session, port)) {
                            synchronized(lock) { bonded = true }
                        }
                    }
                }
            } finally {
                // Always release the slot, otherwise an early exit would block every later retry.
                synchronized(lock) {
                    if (retryThread === Thread.currentThread()) retryThread = null
                }
            }
        }, "fd-bond-retry")
        thread.isDaemon = true
        synchronized(lock) { retryThread = thread }
        thread.start()
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

        // Read it back: applySettings is asynchronous, and reporting "bonded" without checking
        // would be another way of showing a state that is not real.
        val applied = runCatching {
            val live = session.settings()
            live != null &&
                live.getInteger(settings_pack.int_types.proxy_type.swigValue()) ==
                settings_pack.proxy_type_t.socks5.swigValue() &&
                live.getInteger(settings_pack.int_types.proxy_port.swigValue()) == port
        }.getOrDefault(true)
        applied
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

    /** Where bonding stands right now, for the download card. */
    fun bondingState(): BondingState = synchronized(lock) {
        BondingStatus.decide(
            bonded = bonded,
            disabled = bondingDisabled,
            proxyExists = bonding != null,
            starting = bonding?.isStarting() == true,
            running = bonding?.isRunning() == true,
            unavailableReason = bonding?.unavailableReason,
        )
    }

    /** Human sentence for the card, e.g. "bonding: unavailable - mobile data is not reachable". */
    fun bondingNote(): String? = synchronized(lock) {
        val proxy = bonding
        if (!bonded && bondingApplyFailed) {
            "bonding: unavailable - the torrent engine did not accept the proxy setting"
        } else {
            BondingStatus.note(
                state = bondingState(),
                rates = if (bonded) proxy?.describe() else null,
                reason = proxy?.unavailableReason,
            )
        }
    }

    /**
     * True only when the *proxy* is the thing that is failing.
     *
     * This is what gates the fallback to the default network: a swarm that has not produced a
     * peer yet (which is normal for the first half minute) must not be read as "bonding is
     * broken" - that mistake turned bonding off for healthy sessions.
     */
    fun bondingIsFailing(): Boolean {
        synchronized(lock) {
            val stats = bonding?.stats() ?: return false
            return stats.attempts >= MIN_PROXY_ATTEMPTS && stats.successes == 0
        }
    }

    /** Per-radio throughput while bonded; empty when the torrent is on one link. */
    fun bondingRates(): List<BondingProxy.LinkRate> =
        if (bonded) bonding?.rates().orEmpty() else emptyList()

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
                        bondingApplyFailed = false
                        retryThread?.interrupt()
                        retryThread = null
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
