package com.victor.traillink

import com.victor.traillink.core.*
import org.junit.Assert.*
import org.junit.Test

class VoiceWireTest {
    private val net = NetCrypto.deriveNetKey("glacier-moose-42", iterations = 1000)
    private val vk = VoiceWire.deriveVoiceKey(net)
    private val g = NetCrypto.groupTopic(net)

    @Test fun keysDiffer() { assertFalse(vk.contentEquals(net)); assertEquals(32, vk.size) }

    @Test fun roundTripAndTamper() {
        val frames = (1..150).map { i -> ByteArray(60 + i % 7) { (it * i).toByte() } }
        val p = VoicePacket(VoiceHeader("0abc1234", "n:a1b2c3d4", "Victor", null, 1_791_000_000_000L, "opus", 16000, -1, true, 3000, byteArrayOf(1, 2, 3)), frames)
        val w = VoiceWire.seal(vk, g, p)
        val o = VoiceWire.open(vk, g, w)!!
        assertEquals(p.h, o.h); assertEquals(150, o.frames.size)
        frames.indices.forEach { assertArrayEquals(frames[it], o.frames[it]) }
        assertNull("message key can't open voice", VoiceWire.open(net, g, w))
        val t = w.copyOf(); t[40] = (t[40].toInt() xor 1).toByte(); assertNull(VoiceWire.open(vk, g, t))
    }

    @Test fun sixtySecondsFitsHiveMq() {
        // 20 kbps Opus, 20 ms frames => 50 B/frame, 3000 frames
        val frames = List(3000) { ByteArray(50) }
        val w = VoiceWire.seal(vk, g, VoicePacket(VoiceHeader("1", "n:x", "x", null, 0, "opus", 16000, -1, true, 60000, ByteArray(19)), frames))
        assertTrue("size ${w.size}", w.size < 200_000)
    }
}
