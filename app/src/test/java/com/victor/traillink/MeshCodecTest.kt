package com.victor.traillink

import com.victor.traillink.core.Fix
import com.victor.traillink.core.MeshCodec
import com.victor.traillink.core.Psk
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.ConfigProtos.Config
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums.PortNum

class MeshCodecTest {
    @Test fun positionRoundTripThroughFromRadio() {
        val fix = Fix(61.0868123, -149.6607456, altM = 1070, timeSec = 1_791_000_000L, sats = 11, speedMps = 2.0, headingDeg = 135.5)
        val pkt = MeshCodec.positionPacket(0xA1B2C3D4L, fix, snr = 6.25f, rssi = -97)
        val wire = MeshProtos.FromRadio.newBuilder().setId(7).setPacket(pkt).build().toByteArray()

        val fr = MeshCodec.parseFromRadio(wire)
        assertEquals(MeshProtos.FromRadio.PayloadVariantCase.PACKET, fr.payloadVariantCase)
        assertEquals(0xA1B2C3D4L, fr.packet.from.toLong() and 0xFFFFFFFFL)
        assertEquals(PortNum.POSITION_APP, fr.packet.decoded.portnum)
        assertEquals(6.25f, fr.packet.rxSnr, 0.001f)
        assertEquals(-97, fr.packet.rxRssi)
        val back = MeshCodec.fixFrom(MeshProtos.Position.parseFrom(fr.packet.decoded.payload))!!
        assertEquals(fix.lat, back.lat, 1e-7)
        assertEquals(fix.lon, back.lon, 1e-7)
        assertEquals(1070, back.altM)
        assertEquals(1_791_000_000L, back.timeSec)
        assertEquals(11, back.sats)
        assertEquals(135.5, back.headingDeg!!, 1e-4)
        assertTrue("position payload should fit easily in a LoRa packet", fr.packet.decoded.payload.size() < 60)
    }

    @Test fun knownWireBytesDecode() {
        // Position{latitude_i=610868123 (sfixed32, field 1), longitude_i=-1496607456 (field 2), altitude=1070 (field 3)}
        val lat = 610868123; val lon = -1496607456
        fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        val bytes = byteArrayOf(0x0d) + le(lat) + byteArrayOf(0x15) + le(lon) + byteArrayOf(0x18, 0xae.toByte(), 0x08)
        val f = MeshCodec.fixFrom(MeshProtos.Position.parseFrom(bytes))!!
        assertEquals(61.0868123, f.lat, 1e-7); assertEquals(-149.6607456, f.lon, 1e-7); assertEquals(1070, f.altM)
    }

    @Test fun nullIslandAndMissingAreIgnored() {
        assertNull(MeshCodec.fixFrom(MeshProtos.Position.getDefaultInstance()))
        assertNull(MeshCodec.fixFrom(MeshProtos.Position.newBuilder().setLatitudeI(0).setLongitudeI(0).build()))
    }

    @Test fun wantConfigEncodes() {
        val tr = MeshProtos.ToRadio.parseFrom(MeshCodec.wantConfig(12345))
        assertEquals(12345, tr.wantConfigId)
    }

    @Test fun setupMessagesForFieldRadio() {
        val plan = MeshCodec.SetupPlan(MeshCodec.SetupRole.FIELD, "glacier-moose-42", "Victor field", "VIC", useAead = true)
        val name = Psk.channelName(plan.passcode); val psk = Psk.derive(plan.passcode)
        val msgs = MeshCodec.setupMessages(plan, psk, name, emptyMap(), null).map { it.second }
        assertTrue(msgs.first().beginEditSettings); assertTrue(msgs.last().commitEditSettings)
        val ch = msgs.first { it.hasSetChannel() }.setChannel
        assertEquals(0, ch.index); assertEquals(name, ch.settings.name)
        assertArrayEquals(psk, ch.settings.psk.toByteArray()); assertEquals(32, ch.settings.moduleSettings.positionPrecision)
        assertTrue(ch.settings.useAead)
        val cfgs = msgs.filter { it.hasSetConfig() }.map { it.setConfig }
        assertEquals(Config.DeviceConfig.Role.TRACKER, cfgs.first { it.hasDevice() }.device.role)
        val lora = cfgs.first { it.hasLora() }.lora
        assertEquals(Config.LoRaConfig.RegionCode.US, lora.region); assertEquals(Config.LoRaConfig.ModemPreset.LONG_FAST, lora.modemPreset)
        val pos = cfgs.first { it.hasPosition() }.position
        assertEquals(60, pos.broadcastSmartMinimumIntervalSecs); assertEquals(600, pos.positionBroadcastSecs)
        // the admin packet addresses our own node on ADMIN_APP
        val tr = MeshProtos.ToRadio.parseFrom(MeshCodec.adminPacket(0x1234L, msgs[1]))
        assertEquals(0x1234, tr.packet.to); assertEquals(PortNum.ADMIN_APP, tr.packet.decoded.portnum)
        assertEquals("Victor field", AdminProtos.AdminMessage.parseFrom(tr.packet.decoded.payload).setOwner.longName)
    }

    @Test fun relayWithoutKeyGetsSameNameButDifferentKey() {
        val plan = MeshCodec.SetupPlan(MeshCodec.SetupRole.RELAY, "glacier-moose-42", "Ridge relay", "RLY")
        val name = Psk.channelName(plan.passcode); val psk = Psk.derive(plan.passcode)
        val msgs = MeshCodec.setupMessages(plan, psk, name, emptyMap(), null).map { it.second }
        val ch = msgs.first { it.hasSetChannel() }.setChannel.settings
        assertEquals(name, ch.name); assertFalse(psk.contentEquals(ch.psk.toByteArray()))
        assertEquals(Config.DeviceConfig.Role.ROUTER, msgs.first { it.hasSetConfig() && it.setConfig.hasDevice() }.setConfig.device.role)
    }

    @Test fun versionParsing() {
        assertTrue(MeshCodec.supportsAead("2.8.1.8e6a88d")); assertFalse(MeshCodec.supportsAead("2.7.26.54e0d8d"))
        assertFalse(MeshCodec.supportsAead(null)); assertEquals(20726, MeshCodec.versionCode("v2.7.26"))
    }
}
