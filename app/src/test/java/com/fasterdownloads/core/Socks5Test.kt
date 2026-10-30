package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class Socks5Test {

    private fun stream(vararg bytes: Int) = ByteArrayInputStream(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun acceptsAGreetingThatOffersNoAuthentication() {
        // VER=5, NMETHODS=1, METHOD=0 (no auth)
        assertTrue(Socks5.readGreeting(stream(5, 1, 0)))
    }

    @Test
    fun acceptsNoAuthEvenWhenItIsNotTheFirstMethodOffered() {
        // VER=5, NMETHODS=2, METHODS=2 (user/pass), 0 (no auth)
        assertTrue(Socks5.readGreeting(stream(5, 2, 2, 0)))
    }

    @Test
    fun rejectsAnythingElse() {
        assertFalse(Socks5.readGreeting(stream(4, 1, 0)))
        assertFalse(Socks5.readGreeting(stream(5, 1, 2)))
        assertFalse(Socks5.readGreeting(stream(5, 0)))
    }

    @Test
    fun parsesAnIpv4ConnectRequest() {
        // VER=5 CMD=1 RSV=0 ATYP=1, 93.184.216.34, port 80
        val input = stream(5, 1, 0, 1, 93, 184, 216, 34, 0, 80)
        val target = Socks5.readTarget(input) as SocksTarget.Ip
        assertEquals(InetAddress.getByName("93.184.216.34"), target.address)
        assertEquals(80, target.port)
    }

    @Test
    fun parsesADomainConnectRequest() {
        // VER=5 CMD=1 RSV=0 ATYP=3, len=11 "tracker.io", port 6969
        val name = "tracker.io"
        val bytes = mutableListOf(5, 1, 0, 3, name.length, 0, 27)
        name.toByteArray().forEach { bytes.add(it.toInt() and 0xFF) }
        val target = Socks5.readTarget(ByteArrayInputStream(ByteArray(bytes.size) { bytes[it].toByte() }))
            as SocksTarget.Host
        assertEquals("tracker.io", target.host)
        assertEquals(6969, target.port)
    }

    @Test
    fun parsesAnIpv6ConnectRequest() {
        val bytes = mutableListOf(5, 1, 0, 4)
        repeat(16) { bytes.add(if (it == 15) 1 else 0) }
        bytes.add(0)
        bytes.add(443)
        val target = Socks5.readTarget(ByteArrayInputStream(ByteArray(bytes.size) { bytes[it].toByte() }))
            as SocksTarget.Ip
        assertEquals(443, target.port)
        assertEquals(InetAddress.getByName("::1"), target.address)
    }

    @Test
    fun refusesCommandsOtherThanConnect() {
        // CMD=3 is UDP ASSOCIATE, which BitTorrent never uses through this proxy.
        assertNull(Socks5.readTarget(stream(5, 3, 0, 1, 1, 2, 3, 4, 0, 80)))
        // BIND
        assertNull(Socks5.readTarget(stream(5, 2, 0, 1, 1, 2, 3, 4, 0, 80)))
    }

    @Test
    fun refusesATruncatedRequest() {
        assertNull(Socks5.readTarget(stream(5, 1, 0, 1, 1, 2)))
    }

    @Test
    fun writesTheMethodSelectionAndTheConnectReply() {
        val methods = ByteArrayOutputStream()
        Socks5.writeReply(methods, Socks5.REP_SUCCESS, methodOnly = true)
        assertArrayEqualsBytes(byteArrayOf(5, 0), methods.toByteArray())

        val reply = ByteArrayOutputStream()
        Socks5.writeReply(reply, Socks5.REP_SUCCESS)
        assertArrayEqualsBytes(
            byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0),
            reply.toByteArray(),
        )
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
