package com.fasterdownloads.core

import android.content.Context
import android.net.NetworkCapabilities
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * A local SOCKS5 proxy that spreads BitTorrent's peer connections over **both radios**.
 *
 * Why this exists: libtorrent creates its peer sockets inside native code, and Android has no
 * hook that lets an app pin a native socket to a radio (`Socket`/`FileDescriptor` binding is
 * Java-side only, and `SO_BINDTODEVICE` needs root). What libtorrent *can* do is send every
 * peer and tracker connection through a SOCKS5 proxy. So the app runs one on loopback: each
 * incoming CONNECT is opened on Wi-Fi or on mobile data - alternating between the two, weighted
 * by what each radio is actually delivering - and the bytes are piped through.
 *
 * The result is real bonding for a swarm: half the peers are reached over Wi-Fi, half over
 * cellular, and a torrent's aggregate speed becomes the sum of the two links. It is the same
 * idea as the HTTP engine's two-radio split, applied per connection instead of per byte range.
 *
 * Only TCP goes through a proxy (SOCKS5 CONNECT). DHT and UDP trackers keep using the default
 * network underneath, which is a useful safety net: peer discovery still works if the proxy is
 * ever the problem, and [TorrentEngine] turns bonding off for the session when a torrent sees
 * no peers at all through it.
 */
class BondingProxy(private val context: Context) {

    private class LinkState(val handle: LinkHandle) {
        val active = AtomicInteger(0)
        val bytes = AtomicLong(0L)

        /** Download rate through this link, in bytes per second, smoothed over ~2 s. */
        @Volatile
        var rateBps: Long = 0L
    }

    /** What one radio is doing right now - the numbers the download card shows. */
    data class LinkRate(
        val id: String,
        val label: String,
        val isMobile: Boolean,
        val speedBps: Long,
        val totalBytes: Long,
        val connections: Int,
    )

    private val lock = Any()
    private var server: ServerSocket? = null
    private var links: NetworkLinks? = null
    private var pool: ExecutorService? = null
    private val states = CopyOnWriteArrayList<LinkState>()

    /** Share of new connections handed to Wi-Fi; the rest go to mobile data. */
    @Volatile
    private var wifiShare = 0.5

    @Volatile
    private var running = false

    @Volatile
    private var startAttempted = false

    /** Port the proxy is listening on, or null when it is not running. */
    val port: Int?
        get() = synchronized(lock) { server?.localPort }

    /**
     * Brings the proxy up in the background and reports the port once it is listening.
     *
     * Async on purpose: asking ConnectivityManager for the cellular network can take seconds
     * (or time out when there is no SIM), and a torrent must not wait for that. The session
     * starts on the default network immediately; bonding is switched on when this fires.
     *
     * [onReady] is only called when **both** radios were acquired - with a single link there
     * is nothing to bond and the proxy is not used at all.
     */
    fun startAsync(onReady: (Int) -> Unit) {
        synchronized(lock) {
            if (running) {
                server?.let { onReady(it.localPort) }
                return
            }
            if (startAttempted) return
            startAttempted = true
        }
        val thread = Thread({
            val ready = runCatching { startNow() }.getOrNull()
            if (ready != null) runCatching { onReady(ready) }
        }, "fd-bond-start")
        thread.isDaemon = true
        thread.start()
    }

    private fun startNow(): Int? {
        val linkSource = NetworkLinks(context)
        val wifi = linkSource.acquire(
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            id = "wifi",
            label = "Wi-Fi",
            isMobile = false,
        )
        val mobile = linkSource.acquire(
            transport = NetworkCapabilities.TRANSPORT_CELLULAR,
            id = "cell",
            label = "Mobile data",
            isMobile = true,
        )

        if (wifi == null || mobile == null) {
            // Nothing to bond. Release whatever came back and leave the session alone.
            linkSource.release()
            synchronized(lock) { startAttempted = false }
            return null
        }

        val socket = try {
            ServerSocket(0, 128, InetAddress.getByName(LOOPBACK))
        } catch (t: Throwable) {
            linkSource.release()
            synchronized(lock) { startAttempted = false }
            return null
        }

        synchronized(lock) {
            links = linkSource
            states.clear()
            states.add(LinkState(wifi))
            states.add(LinkState(mobile))
            server = socket
            pool = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "fd-bond-worker").apply { isDaemon = true }
            }
            running = true
        }

        Thread({ acceptLoop(socket) }, "fd-bond-accept").apply { isDaemon = true }.start()
        Thread({ sampleLoop() }, "fd-bond-sample").apply { isDaemon = true }.start()
        return socket.localPort
    }

    fun stop() {
        var socket: ServerSocket? = null
        var source: NetworkLinks? = null
        var executor: ExecutorService? = null
        synchronized(lock) {
            socket = server
            source = links
            executor = pool
            server = null
            links = null
            pool = null
            running = false
            startAttempted = false
            states.clear()
        }
        runCatching { socket?.close() }
        runCatching { executor?.shutdownNow() }
        runCatching { source?.release() }
    }

    /** One line for the download card: what the two radios are carrying right now. */
    fun describe(): String? {
        if (!running) return null
        val snapshot = states.toList()
        if (snapshot.isEmpty()) return null
        return snapshot.joinToString(" \u00b7 ") { state ->
            val connections = state.active.get()
            val label = if (state.handle.isMobile) "mobile" else "Wi-Fi"
            val speed = formatSpeed(state.rateBps)
            if (state.rateBps > 0L) {
                "$label $speed ($connections)"
            } else {
                "$label $connections conn" + (if (connections == 1) "" else "s")
            }
        }
    }

    // ------------------------------------------------------------- accept loop

    private fun acceptLoop(socket: ServerSocket) {
        while (running && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (!running) return
                continue
            }
            val executor = pool
            if (executor == null) {
                runCatching { client.close() }
                return
            }
            runCatching { executor.execute { handle(client) } }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        var state: LinkState? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (!Socks5.readGreeting(input)) {
                Socks5.writeReply(output, Socks5.REP_NOT_SUPPORTED)
                return
            }
            Socks5.writeReply(output, Socks5.REP_SUCCESS, methodOnly = true)

            val target = Socks5.readTarget(input)
            if (target == null) {
                Socks5.writeReply(output, Socks5.REP_NOT_SUPPORTED)
                return
            }

            val chosen = pickLink()
            if (chosen == null) {
                Socks5.writeReply(output, Socks5.REP_NETWORK_UNREACHABLE)
                return
            }
            state = chosen

            upstream = connect(chosen, target)
            if (upstream == null) {
                Socks5.writeReply(output, Socks5.REP_HOST_UNREACHABLE)
                return
            }
            upstream.tcpNoDelay = true
            client.soTimeout = 0
            Socks5.writeReply(output, Socks5.REP_SUCCESS)

            chosen.active.incrementAndGet()
            pipe(client, upstream, chosen)
        } catch (t: Throwable) {
            // A dead peer connection is normal in a swarm: nothing to report.
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    /** Opens the real connection on the chosen radio. Returns null if the peer is unreachable. */
    private fun connect(state: LinkState, target: SocksTarget): Socket? {
        val address = when (target) {
            is SocksTarget.Ip -> target.address
            is SocksTarget.Host -> {
                // Resolve through the chosen radio's DNS, not the default network's.
                runCatching { state.handle.network.getAllByName(target.host).firstOrNull() }.getOrNull()
            }
        } ?: return null

        val socket = runCatching {
            // Sockets from a Network's factory carry that network's binding: this is the step
            // that makes the connection leave through Wi-Fi or through mobile data.
            state.handle.network.socketFactory.createSocket()
        }.getOrNull() ?: return null

        val connected = runCatching {
            socket.connect(InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS)
            true
        }.getOrDefault(false)

        if (!connected) {
            runCatching { socket.close() }
            return null
        }
        return socket
    }

    /** Least-loaded link, weighted by measured throughput. */
    private fun pickLink(): LinkState? {
        val snapshot = states.toList()
        if (snapshot.isEmpty()) return null
        if (snapshot.size == 1) return snapshot.first()
        val share = wifiShare.coerceIn(MIN_SHARE, MAX_SHARE)
        return snapshot.minByOrNull { state ->
            val weight = if (state.handle.isMobile) 1.0 - share else share
            state.active.get() / max(weight, 0.01)
        }
    }

    /**
     * Samples each radio once a second: bytes moved since the last tick become a rate for the
     * UI, and the same numbers steer the connection split every few seconds so the faster
     * radio gets more of the new connections.
     */
    private fun sampleLoop() {
        var lastWifiBytes = 0L
        var lastMobileBytes = 0L
        var ticks = 0
        while (running) {
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                return
            }

            val snapshot = states.toList()
            if (snapshot.isEmpty()) continue
            val wifi = snapshot.firstOrNull { !it.handle.isMobile }
            val mobile = snapshot.firstOrNull { it.handle.isMobile }

            val wifiNow = wifi?.bytes?.get() ?: 0L
            val mobileNow = mobile?.bytes?.get() ?: 0L
            val wifiDelta = (wifiNow - lastWifiBytes).coerceAtLeast(0L)
            val mobileDelta = (mobileNow - lastMobileBytes).coerceAtLeast(0L)
            lastWifiBytes = wifiNow
            lastMobileBytes = mobileNow

            // Bytes per second for this tick, then smoothed: a single stalled tick must not
            // make the radio look dead.
            val perSecond = 1000.0 / SAMPLE_INTERVAL_MS.toDouble()
            wifi?.let { link -> link.rateBps = smooth(link.rateBps, wifiDelta * perSecond) }
            mobile?.let { link -> link.rateBps = smooth(link.rateBps, mobileDelta * perSecond) }

            ticks++
            if (ticks % WEIGHT_TICKS != 0) continue
            val total = wifiDelta + mobileDelta
            if (total < MIN_SAMPLE_BYTES) continue
            val observed = wifiDelta.toDouble() / total.toDouble()
            wifiShare = (wifiShare * 0.6 + observed * 0.4).coerceIn(MIN_SHARE, MAX_SHARE)
        }
    }

    private fun smooth(previous: Long, sample: Double): Long =
        (previous * SMOOTHING + sample * (1.0 - SMOOTHING)).toLong().coerceAtLeast(0L)

    /** Per-radio rates for the download card, or an empty list when bonding is off. */
    fun rates(): List<LinkRate> {
        if (!running) return emptyList()
        return states.toList().map { link ->
            LinkRate(
                id = link.handle.id,
                label = link.handle.label,
                isMobile = link.handle.isMobile,
                speedBps = link.rateBps,
                totalBytes = link.bytes.get(),
                connections = link.active.get(),
            )
        }
    }

    /** Pumps bytes both ways until either side closes. */
    private fun pipe(client: Socket, upstream: Socket, state: LinkState) {
        val upstreamThread = Thread({
            runCatching { copy(client.getInputStream(), upstream.getOutputStream(), state) }
            runCatching { client.close() }
            runCatching { upstream.close() }
        }, "fd-bond-up")
        upstreamThread.isDaemon = true
        upstreamThread.start()

        try {
            copy(upstream.getInputStream(), client.getOutputStream(), state)
        } finally {
            state.active.decrementAndGet()
            runCatching { client.close() }
            runCatching { upstream.close() }
            runCatching { upstreamThread.join(1_000L) }
        }
    }

    private fun copy(input: InputStream, output: OutputStream, state: LinkState) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
            output.flush()
            state.bytes.addAndGet(read.toLong())
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val CONNECT_TIMEOUT_MS = 12_000
        const val SAMPLE_INTERVAL_MS = 1_000L

        /** Connection weights are re-measured every fourth sample, i.e. every ~4 s. */
        const val WEIGHT_TICKS = 4
        const val SMOOTHING = 0.5
        const val BUFFER_SIZE = 16 * 1024
        const val MIN_SAMPLE_BYTES = 64L * 1024L
        const val MIN_SHARE = 0.2
        const val MAX_SHARE = 0.8
    }
}

/** Where a SOCKS5 client wants us to connect. */
internal sealed class SocksTarget {
    abstract val port: Int

    data class Ip(val address: InetAddress, override val port: Int) : SocksTarget()

    data class Host(val host: String, override val port: Int) : SocksTarget()
}

/** The 20 lines of SOCKS5 (RFC 1928) this proxy needs, as pure stream functions. */
internal object Socks5 {

    const val VERSION = 5
    const val CMD_CONNECT = 1
    const val ATYP_IPV4 = 1
    const val ATYP_DOMAIN = 3
    const val ATYP_IPV6 = 4

    const val REP_SUCCESS = 0
    const val REP_NETWORK_UNREACHABLE = 3
    const val REP_HOST_UNREACHABLE = 4
    const val REP_NOT_SUPPORTED = 7

    /** Reads `VER, NMETHODS, METHODS...`; true when "no authentication" is acceptable. */
    fun readGreeting(input: InputStream): Boolean {
        val version = input.read()
        if (version != VERSION) return false
        val count = input.read()
        if (count <= 0) return false
        val methods = ByteArray(count)
        if (!readFully(input, methods)) return false
        return methods.any { method -> (method.toInt() and 0xFF) == 0x00 }
    }

    /**
     * Reads a CONNECT request and returns its target, or null when the request is malformed or
     * asks for something other than CONNECT (BIND/UDP ASSOCIATE - which BitTorrent never uses
     * through this path).
     */
    fun readTarget(input: InputStream): SocksTarget? {
        val header = ByteArray(4)
        if (!readFully(input, header)) return null
        if ((header[0].toInt() and 0xFF) != VERSION) return null
        if ((header[1].toInt() and 0xFF) != CMD_CONNECT) return null

        // RFC 1928 order: VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT. The address comes before
        // the port, and its length depends on ATYP.
        val address: Any = when (header[3].toInt() and 0xFF) {
            ATYP_IPV4 -> {
                val raw = ByteArray(4)
                if (!readFully(input, raw)) return null
                InetAddress.getByAddress(raw)
            }

            ATYP_IPV6 -> {
                val raw = ByteArray(16)
                if (!readFully(input, raw)) return null
                InetAddress.getByAddress(raw)
            }

            ATYP_DOMAIN -> {
                val length = input.read()
                if (length <= 0) return null
                val raw = ByteArray(length)
                if (!readFully(input, raw)) return null
                String(raw, Charsets.UTF_8)
            }

            else -> return null
        }

        val high = input.read()
        val low = input.read()
        if (high < 0 || low < 0) return null
        val port = ((high and 0xFF) shl 8) or (low and 0xFF)

        return when (address) {
            is InetAddress -> SocksTarget.Ip(address, port)
            else -> SocksTarget.Host(address as String, port)
        }
    }

    /**
     * Writes a reply. [methodOnly] writes the method-selection message (`VER, METHOD`) that
     * answers the greeting; otherwise the full CONNECT reply with `BND = 0.0.0.0:0`.
     */
    fun writeReply(output: OutputStream, code: Int, methodOnly: Boolean = false) {
        if (methodOnly) {
            // Only "no authentication required" is ever offered.
            output.write(byteArrayOf(VERSION.toByte(), 0x00))
        } else {
            output.write(
                byteArrayOf(
                    VERSION.toByte(),
                    code.toByte(),
                    0x00,
                    ATYP_IPV4.toByte(),
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                ),
            )
        }
        output.flush()
    }

    fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = try {
                input.read(buffer, offset, buffer.size - offset)
            } catch (t: IOException) {
                return false
            }
            if (read < 0) return false
            offset += read
        }
        return true
    }
}
