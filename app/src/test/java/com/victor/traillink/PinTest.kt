package com.victor.traillink

import com.victor.traillink.core.*
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.MeshProtos

class PinTest {
    private val now = 1_791_000_000_000L
    private val pin = PinMsg("0a1b2c3d", "Meet at the creek", PinKind.CAMP.name, PIN_COLORS[2], "Bring water filter",
        61.2181234, -149.9003456, 120, "n:a1b2c3d4", "Victor", now, now, now + 6 * 3_600_000L)

    @Test fun waypointRoundTripThroughProtobuf() {
        val bytes = WaypointCodec.encode(pin, lockedTo = 0x12345678).toByteArray()
        val w = MeshProtos.Waypoint.parseFrom(bytes)
        assertEquals(0x0a1b2c3d, w.id); assertEquals(612181234, w.latitudeI); assertEquals(-1499003456, w.longitudeI)
        assertEquals("⛺".codePointAt(0), w.icon); assertEquals(0x12345678, w.lockedTo)
        val d = WaypointCodec.decode(w, 0x12345678L, "Victor radio", now)
        assertEquals(pin.id, d.id); assertEquals(pin.name, d.name); assertEquals(PinKind.CAMP.name, d.kind); assertEquals(pin.note, d.note)
        assertEquals(pin.lat, d.lat, 1e-7); assertEquals(pin.lon, d.lon, 1e-7)
        assertEquals(pin.expires / 1000 * 1000, d.expires); assertFalse(d.deleted); assertEquals("l:305419896", d.by)
    }

    @Test fun deleteAndLimits() {
        val w = WaypointCodec.encode(pin.copy(deleted = true, name = "x".repeat(50), note = "é".repeat(80)))
        assertEquals(1, w.expire)
        assertTrue(w.name.toByteArray().size <= 30); assertTrue(w.description.toByteArray().size <= 100)
        assertTrue(WaypointCodec.decode(w, 1, "a", now).deleted)
        assertEquals(0, WaypointCodec.encode(pin.copy(expires = 0)).expire)
    }

    @Test fun stockAppIconKept() {
        val w = MeshProtos.Waypoint.newBuilder().setId(7).setLatitudeI(1).setLongitudeI(2).setIcon("🍕".codePointAt(0)).setName("Pizza").build()
        val d = WaypointCodec.decode(w, 9, "n", now)
        assertEquals(PinKind.OTHER.name, d.kind); assertEquals("🍕", d.emoji)
        assertEquals("🍕".codePointAt(0), WaypointCodec.encode(d).icon)
    }

    @Test fun internetSealOpen() {
        val k = NetCrypto.deriveNetKey("glacier-moose-42", iterations = 1000)
        val w = PinMsg.seal(k, pin)
        assertEquals(pin, PinMsg.open(k, w))
        assertNull(PinMsg.open(NetCrypto.deriveNetKey("nope", iterations = 1000), w))
    }

    @Test fun bearingToPin() {
        assertEquals(0.0, Geo.bearingDeg(61.0, -149.0, 61.01, -149.0), 0.01)
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 0.01), 0.01)
        assertEquals(225.0, Geo.bearingDeg(0.0, 0.0, -0.01, -0.01), 0.1)
        assertEquals("NE", Geo.cardinal(Geo.bearingDeg(61.0, -149.0, 61.005, -148.99)))
    }
}
