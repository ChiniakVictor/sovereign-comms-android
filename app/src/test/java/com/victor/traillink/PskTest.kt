package com.victor.traillink

import com.victor.traillink.core.Psk
import org.junit.Assert.*
import org.junit.Test

class PskTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun pbkdf2MatchesRfc7914Vector() {
        // RFC 7914 §11: PBKDF2-HMAC-SHA256 (P="passwd", S="salt", c=1, dkLen=64)
        val out = Psk.pbkdf2Sha256("passwd".toCharArray(), "salt".toByteArray(), 1, 64)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783", hex(out))
    }

    @Test fun channelNameAndPskAreStable() {
        // Expected values computed independently with Python hashlib.
        assertEquals("TL1c500d", Psk.channelName("glacier-moose-42"))
        val k = Psk.derive("glacier-moose-42")
        assertEquals(32, k.size)
        assertEquals("506365499771dbec057d205828b5065710a3bf8248f4a85e650ab6089cc63ab9", hex(k))
    }

    @Test fun whitespaceIsTrimmedAndDifferentPasscodesDiffer() {
        assertArrayEquals(Psk.derive("glacier-moose-42"), Psk.derive("  glacier-moose-42 "))
        assertFalse(Psk.derive("glacier-moose-42").contentEquals(Psk.derive("glacier-moose-43")))
        assertNotEquals(Psk.channelName("a-passcode-1"), Psk.channelName("a-passcode-2"))
        assertTrue(Psk.channelName("x").length <= 11) // Meshtastic channel name limit
    }
}
