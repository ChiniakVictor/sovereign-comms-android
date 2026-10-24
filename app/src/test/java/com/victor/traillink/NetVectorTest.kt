package com.victor.traillink

import com.victor.traillink.core.NetCrypto
import com.victor.traillink.core.NetPosition
import org.junit.Test
import java.io.File

/** Writes a sealed message for the cross-implementation / live broker loopback check (tools/mqtt_loopback.py). */
class NetVectorTest {
    @Test fun writeVector() {
        val key = NetCrypto.deriveNetKey("loopback-test-passcode")
        val p = NetPosition("kotlin01", "Kotlin", "KT", 61.2181, -149.9003, alt = 30, acc = 5f, battery = 77, ts = System.currentTimeMillis(), seq = 1)
        val hex = NetCrypto.seal(key, p).joinToString("") { "%02x".format(it) }
        File(System.getProperty("java.io.tmpdir"), "traillink-vector.txt").writeText(NetCrypto.groupTopic(key) + "\n" + hex + "\n")
    }
}
