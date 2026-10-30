package com.fasterdownloads.core

/** Where the two-radio bonding of torrent traffic stands. */
enum class BondingState {
    /** No torrent session yet. */
    OFF,

    /** The proxy is coming up, or its settings are being applied to the session. */
    STARTING,

    /** Peer connections are being spread over Wi-Fi and mobile data. */
    ACTIVE,

    /** Only one radio is reachable, so there is nothing to bond. */
    UNAVAILABLE,

    /** Turned off after the proxy itself failed to open any peer connection. */
    DISABLED,
}

/**
 * The decision of which [BondingState] applies, kept pure so it can be unit-tested without an
 * Android device - the states used to be implicit, which is why "not bonded" was impossible to
 * tell apart from "never tried".
 */
object BondingStatus {

    fun decide(
        bonded: Boolean,
        disabled: Boolean,
        proxyExists: Boolean,
        starting: Boolean,
        running: Boolean,
        unavailableReason: String?,
    ): BondingState = when {
        disabled -> BondingState.DISABLED
        bonded -> BondingState.ACTIVE
        !proxyExists -> BondingState.OFF
        running -> BondingState.STARTING
        starting -> BondingState.STARTING
        unavailableReason != null -> BondingState.UNAVAILABLE
        else -> BondingState.STARTING
    }

    /** One line for the download card, or null when there is nothing worth saying. */
    fun note(state: BondingState, rates: String?, reason: String?): String? = when (state) {
        BondingState.ACTIVE -> rates?.let { "bonded: $it" }
        BondingState.STARTING -> "bonding: bringing up the second radio"
        BondingState.UNAVAILABLE ->
            "bonding: unavailable" + (reason?.let { " - $it" } ?: "")
        BondingState.DISABLED -> "bonding: off - the proxy opened no peers, using the default network"
        BondingState.OFF -> null
    }
}
