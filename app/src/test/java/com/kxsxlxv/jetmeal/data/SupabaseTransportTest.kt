package com.kxsxlxv.jetmeal.data

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class SupabaseTransportTest {
    private val v4a = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 7))
    private val v4b = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 9))
    private val v6a = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })
    private val v6b = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 2 })

    @Test fun prefersIpv4WithoutDiscardingIpv6Fallback() {
        val input = listOf(v6a, v4a, v6b, v4b)
        assertEquals(listOf(v4a, v4b, v6a, v6b), ipv4First(input))
    }

    @Test fun supportsIpv6OnlyAndIpv4OnlyNetworks() {
        assertEquals(listOf(v6b, v6a), ipv4First(listOf(v6b, v6a)))
        assertEquals(listOf(v4b, v4a), ipv4First(listOf(v4b, v4a)))
        assertTrue(ipv4First(emptyList()).isEmpty())
    }

    @Test fun logsOnlyAddressFamilyNotAddress() {
        assertTrue(v4a is Inet4Address)
        assertTrue(v6a is Inet6Address)
        assertEquals(SocketFamily.V4, socketFamily(v4a))
        assertEquals(SocketFamily.V6, socketFamily(v6a))
        assertEquals(SocketFamily.Unknown, socketFamily(null))
        val trace = NetworkTrace(NetworkRoute.Diary, NetworkPhase.Body, 25000,
            cause = NetworkCause.Timeout, protocol = NetworkProtocol.H1,
            family = socketFamily(v4a), bodyStarted = true, bodyCompleted = false)
        val log = trace.safeLine()
        assertTrue(log.contains("family=V4"))
        assertTrue(log.contains("bodyComplete=false"))
        assertFalse(log.contains("192.0.2.7"))
    }
}
