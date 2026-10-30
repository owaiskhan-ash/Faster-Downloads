package com.fasterdownloads.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A single physical link (Wi-Fi or cellular) that sockets can be pinned to.
 *
 * Android lets an app hold *both* the Wi-Fi and the cellular network at the same
 * time via [ConnectivityManager.requestNetwork]; every [Network] object can then
 * open its own sockets, which is how we spread one file across two radios.
 */
class LinkHandle(
    val id: String,
    val label: String,
    val network: Network,
    val isMobile: Boolean,
)

/**
 * Requests (and later releases) [Network] handles for specific transports.
 * The callback is kept registered for the lifetime of the download so that the
 * platform does not tear the network down underneath us.
 */
class NetworkLinks(context: Context) {

    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val registered = mutableListOf<ConnectivityManager.NetworkCallback>()

    fun acquire(
        transport: Int,
        id: String,
        label: String,
        isMobile: Boolean,
        timeoutMs: Int = 8000,
    ): LinkHandle? {
        val request = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val holder = AtomicReference<Network?>(null)
        val latch = CountDownLatch(1)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                holder.compareAndSet(null, network)
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }

        val requested = try {
            connectivity.requestNetwork(request, callback, timeoutMs)
            true
        } catch (t: Throwable) {
            false
        }
        if (!requested) return null

        synchronized(registered) { registered.add(callback) }
        try {
            latch.await((timeoutMs + 2000).toLong(), TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }

        val network = holder.get() ?: return null
        return LinkHandle(id = id, label = label, network = network, isMobile = isMobile)
    }

    fun release() {
        val callbacks: List<ConnectivityManager.NetworkCallback>
        synchronized(registered) {
            callbacks = registered.toList()
            registered.clear()
        }
        callbacks.forEach { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    /** True when the device currently has a network of this transport at all. */
    fun hasTransport(transport: Int): Boolean {
        val networks = try {
            connectivity.allNetworks
        } catch (t: Throwable) {
            return false
        }
        for (network in networks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(transport) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return true
            }
        }
        return false
    }
}
