package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BondingStatusTest {

    @Test
    fun activeWhenPeerConnectionsAreSplit() {
        assertEquals(
            BondingState.ACTIVE,
            BondingStatus.decide(
                bonded = true,
                disabled = false,
                proxyExists = true,
                starting = false,
                running = true,
                unavailableReason = null,
            ),
        )
    }

    @Test
    fun disabledWinsOverEverythingElse() {
        // Once the proxy has been written off it stays off until the session restarts,
        // even though the proxy object is still there.
        assertEquals(
            BondingState.DISABLED,
            BondingStatus.decide(
                bonded = true,
                disabled = true,
                proxyExists = true,
                starting = false,
                running = true,
                unavailableReason = null,
            ),
        )
    }

    @Test
    fun startingCoversBothTheNetworkRequestAndTheApply() {
        // Asking ConnectivityManager for the cellular network...
        assertEquals(
            BondingState.STARTING,
            BondingStatus.decide(false, false, true, starting = true, running = false, unavailableReason = null),
        )
        // ...and the proxy is up but the session has not been pointed at it yet.
        assertEquals(
            BondingState.STARTING,
            BondingStatus.decide(false, false, true, starting = false, running = true, unavailableReason = null),
        )
    }

    @Test
    fun unavailableCarriesTheReason() {
        val state = BondingStatus.decide(
            bonded = false,
            disabled = false,
            proxyExists = true,
            starting = false,
            running = false,
            unavailableReason = "mobile data is not reachable right now",
        )
        assertEquals(BondingState.UNAVAILABLE, state)
        assertEquals(
            "bonding: unavailable - mobile data is not reachable right now",
            BondingStatus.note(state, rates = null, reason = "mobile data is not reachable right now"),
        )
    }

    @Test
    fun offBeforeTheFirstTorrentEverRan() {
        assertEquals(
            BondingState.OFF,
            BondingStatus.decide(false, false, proxyExists = false, starting = false, running = false, unavailableReason = null),
        )
        assertNull(BondingStatus.note(BondingState.OFF, rates = null, reason = null))
    }

    @Test
    fun theActiveNoteCarriesThePerRadioNumbers() {
        val note = BondingStatus.note(
            BondingState.ACTIVE,
            rates = "Wi-Fi 4.2 MB/s (12) \u00b7 mobile 2.6 MB/s (9)",
            reason = null,
        )
        assertTrue(note!!.startsWith("bonded: Wi-Fi 4.2 MB/s"))
        assertTrue(note.contains("\u00b7 mobile 2.6 MB/s"))
    }
}
