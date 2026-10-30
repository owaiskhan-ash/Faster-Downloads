package com.fasterdownloads.core

import android.content.Context
import android.os.SystemClock
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

    /**
     * How long to wait for libtorrent to take the proxy setting.
     *
     * `session::apply_settings` posts the pack to the session's network thread and returns
     * immediately, so reading the settings back in the same breath always shows the *old*
     * values. That race is why 0.9.1 reported "the torrent engine did not accept the proxy
     * setting" and never bonded: the setting landed a millisecond later, unobserved.
     */
    private const val VERIFY_TIMEOUT_MS = 4_000L
    private const val VERIFY_POLL_MS = 100L

    /** Where [BondingProxy] listens; libtorrent is pointed here. */
    private const val LOOPBACK = "127.0.0.1"

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

    /** A settings application + verification is running on its own thread. */
    @Volatile
    private var bondingApplying = false

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
                bondingApplying = false
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

        proxy.startAsync { port -> bondSession(session, port) }

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
                    proxy.startAsync { port -> bondSession(session, port) }
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

    /**
     * Applies the proxy settings to the live session on a background thread.
     *
     * Called from [BondingProxy.startAsync]'s callback, which may run on the caller's thread
     * (the main thread during app start-up), so nothing here may block it.
     */
    private fun bondSession(session: SessionManager, port: Int) {
        synchronized(lock) {
            if (manager !== session || bondingDisabled || bonded) return
            if (bondingApplying) return
            bondingApplying = true
        }
        val thread = Thread({
            try {
                val applied = applyProxySettings(session, port)
                synchronized(lock) {
                    if (manager !== session || bondingDisabled) return@Thread
                    if (applied) {
                        bonded = true
                        bondingApplyFailed = false
                    } else {
                        bonded = false
                        bondingApplyFailed = true
                    }
                }
            } finally {
                bondingApplying = false
            }
        }, "fd-bond-apply")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * The settings that make libtorrent send its peer and tracker connections through the
     * loopback SOCKS5 proxy.
     *
     * The uTP flags are the important part. uTP is UDP, and a SOCKS5 proxy can only carry TCP:
     * libtorrent does not route uTP through a proxy, and when it decides to use uTP for a peer
     * (any peer that advertises uTP support - most modern clients do) it opens that connection
     * straight out of the default network. With uTP left on, a "bonded" magnet therefore moved
     * all of its data over one radio and the proxy saw nearly nothing. Turning uTP off while
     * bonded forces every outgoing peer connection to be TCP, i.e. through the proxy, i.e.
     * split over both radios.
     */
    private fun proxyPack(port: Int): SettingsPack = SettingsPack().also { pack ->
        pack.setInteger(
            settings_pack.int_types.proxy_type.swigValue(),
            settings_pack.proxy_type_t.socks5.swigValue(),
        )
        pack.setString(settings_pack.string_types.proxy_hostname.swigValue(), LOOPBACK)
        pack.setInteger(settings_pack.int_types.proxy_port.swigValue(), port)
        pack.setBoolean(settings_pack.bool_types.proxy_peer_connections.swigValue(), true)
        pack.setBoolean(settings_pack.bool_types.proxy_tracker_connections.swigValue(), true)
        pack.setBoolean(settings_pack.bool_types.proxy_hostnames.swigValue(), true)
        // TCP only while bonded - see the comment above.
        pack.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), false)
        pack.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), false)
        pack.setBoolean(settings_pack.bool_types.enable_outgoing_tcp.swigValue(), true)
    }

    /** Applies [proxyPack] and waits (bounded) until the session reports it, then verifies. */
    private fun applyProxySettings(session: SessionManager, port: Int): Boolean {
        val sent = runCatching { session.applySettings(proxyPack(port)) }.isSuccess
        if (!sent) return false

        val deadline = SystemClock.elapsedRealtime() + VERIFY_TIMEOUT_MS
        while (true) {
            if (proxyIsLive(session, port)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            runCatching { Thread.sleep(VERIFY_POLL_MS) }
        }
    }

    /** True when the session's live settings really describe our proxy. */
    private fun proxyIsLive(session: SessionManager, port: Int): Boolean = runCatching {
        val live = session.settings() ?: return@runCatching false
        live.getInteger(settings_pack.int_types.proxy_type.swigValue()) ==
            settings_pack.proxy_type_t.socks5.swigValue() &&
            live.getInteger(settings_pack.int_types.proxy_port.swigValue()) == port &&
            !live.getBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue())
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
                // uTP was only off because a SOCKS5 proxy cannot carry it; the plain
                // default network can, so hand it back.
                pack.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
                pack.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
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
            "bonding: unavailable - the torrent engine did not take the proxy setting"
        } else {
            val note = BondingStatus.note(
                state = bondingState(),
                rates = if (bonded) proxy?.describe() else null,
                reason = proxy?.unavailableReason,
            )
            // Bonded but idle: say so instead of showing two silent zeroes.
            if (bonded && note != null && proxy != null && proxy.stats().attempts == 0) {
                "$note \u00b7 no peer through the proxy yet"
            } else {
                note
            }
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
