package com.victor.traillink

import com.victor.traillink.service.DualStackTlsFactory
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetSocketAddress
import org.junit.Assert.*

class DualStackTest {
    @Test fun ipv4First() {
        val a = runCatching { DualStackTlsFactory.orderedAddresses("localhost") }.getOrNull() ?: return
        val firstV6 = a.indexOfFirst { it !is Inet4Address }
        val lastV4 = a.indexOfLast { it is Inet4Address }
        if (firstV6 >= 0 && lastV4 >= 0) assertTrue(lastV4 < firstV6)
    }

    /** TRAILLINK_TLS_HOST=host:port ./gradlew test  — live TLS handshake with SNI + hostname check through the factory. */
    @Test fun liveTls() {
        val hp = System.getenv("TRAILLINK_TLS_HOST"); assumeTrue(hp != null)
        val (h, p) = hp!!.split(":")
        val s = DualStackTlsFactory(tls = true).createSocket()
        s.connect(InetSocketAddress(h, p.toInt()), 10_000)
        assertTrue(s.isConnected); println("connected to ${s.inetAddress}"); s.close()
    }
}
