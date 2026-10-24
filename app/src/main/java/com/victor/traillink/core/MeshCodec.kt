package com.victor.traillink.core

import com.google.protobuf.ByteString
import org.meshtastic.proto.AdminProtos.AdminMessage
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.ConfigProtos.Config
import org.meshtastic.proto.ModuleConfigProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.MeshProtos.FromRadio
import org.meshtastic.proto.MeshProtos.MeshPacket
import org.meshtastic.proto.MeshProtos.ToRadio
import org.meshtastic.proto.Portnums.PortNum
import kotlin.math.roundToInt
import kotlin.random.Random

/** A decoded position, independent of protobuf types. */
data class Fix(
    val lat: Double, val lon: Double, val altM: Int? = null, val timeSec: Long = 0,
    val sats: Int = 0, val speedMps: Double? = null, val headingDeg: Double? = null,
)

/** Pure encode/decode helpers for the Meshtastic client API (no Android dependencies, unit-testable). */
object MeshCodec {
    const val BROADCAST = 0xFFFFFFFFL

    fun wantConfig(id: Int): ByteArray = ToRadio.newBuilder().setWantConfigId(id).build().toByteArray()
    fun disconnect(): ByteArray = ToRadio.newBuilder().setDisconnect(true).build().toByteArray()

    fun parseFromRadio(bytes: ByteArray): FromRadio = FromRadio.parseFrom(bytes)

    // ---- Position --------------------------------------------------------------------------
    fun positionProto(f: Fix): MeshProtos.Position {
        val b = MeshProtos.Position.newBuilder()
            .setLatitudeI((f.lat * 1e7).roundToInt())
            .setLongitudeI((f.lon * 1e7).roundToInt())
            .setTime(f.timeSec.toInt())
            .setSatsInView(f.sats)
            .setLocationSource(MeshProtos.Position.LocSource.LOC_INTERNAL)
        f.altM?.let { b.setAltitude(it) }
        f.speedMps?.let { b.setGroundSpeed(it.roundToInt()) }
        f.headingDeg?.let { b.setGroundTrack((it * 1e5).roundToInt()) } // ground_track is 1e-5 degrees
        return b.build()
    }

    fun fixFrom(p: MeshProtos.Position): Fix? {
        if (!p.hasLatitudeI() || !p.hasLongitudeI()) return null
        if (p.latitudeI == 0 && p.longitudeI == 0) return null
        val lat = p.latitudeI / 1e7; val lon = p.longitudeI / 1e7
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Fix(
            lat, lon,
            altM = if (p.hasAltitude()) p.altitude else null,
            timeSec = (p.time.toLong() and 0xFFFFFFFFL),
            sats = p.satsInView,
            speedMps = if (p.hasGroundSpeed()) p.groundSpeed.toDouble() else null,
            headingDeg = if (p.hasGroundTrack()) p.groundTrack / 1e5 else null,
        )
    }

    /** A full POSITION_APP MeshPacket (as it would arrive from the radio over BLE). */
    fun positionPacket(from: Long, fix: Fix, snr: Float = 0f, rssi: Int = 0, to: Long = BROADCAST): MeshPacket =
        MeshPacket.newBuilder()
            .setFrom(from.toInt()).setTo(to.toInt()).setId(Random.nextInt())
            .setRxSnr(snr).setRxRssi(rssi)
            .setDecoded(MeshProtos.Data.newBuilder().setPortnum(PortNum.POSITION_APP)
                .setPayload(positionProto(fix).toByteString()))
            .build()

    // ---- Admin (radio setup) ---------------------------------------------------------------
    fun adminPacket(myNode: Long, admin: AdminMessage): ByteArray {
        val pkt = MeshPacket.newBuilder()
            .setTo(myNode.toInt())
            .setId(Random.nextInt() and 0x7fffffff)
            .setWantAck(true)
            .setChannel(0)
            .setPriority(MeshPacket.Priority.RELIABLE)
            .setDecoded(MeshProtos.Data.newBuilder()
                .setPortnum(PortNum.ADMIN_APP)
                .setPayload(admin.toByteString())
                .setWantResponse(true))
        return ToRadio.newBuilder().setPacket(pkt).build().toByteArray()
    }

    fun admin(passkey: ByteString?, block: AdminMessage.Builder.() -> Unit): AdminMessage {
        val b = AdminMessage.newBuilder()
        if (passkey != null && !passkey.isEmpty) b.sessionPasskey = passkey
        b.block()
        return b.build()
    }

    /** Parses "2.8.1.8e6a88d" style versions; returns e.g. 20801. */
    fun versionCode(v: String?): Int {
        if (v.isNullOrBlank()) return 0
        val parts = v.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return 0
        return parts.getOrElse(0) { 0 } * 10000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
    }
    fun supportsAead(firmware: String?) = versionCode(firmware) >= 20801

    enum class SetupRole { FIELD, HOME, RELAY }

    data class SetupPlan(
        val role: SetupRole,
        val passcode: String,
        val longName: String,
        val shortName: String,
        val movingIntervalSec: Int = 60,
        val stillIntervalSec: Int = 600,
        val minDistanceM: Int = 50,
        val useAead: Boolean = false,
        val relayStoresKey: Boolean = false,
    )

    /** Shown on the radio's canned-message menu (separator "|"). Matches the app's quick replies. */
    const val CANNED_MESSAGES = "OK|On my way|Need help|Where are you?|Heading back|Stopping here|All good"

    /** Builds the ordered list of admin messages that configure a radio for Sovereign Comms. */
    fun setupMessages(
        plan: SetupPlan, psk: ByteArray, channelName: String,
        current: Map<Int, Config>, passkey: ByteString?,
    ): List<Pair<String, AdminMessage>> {
        val out = mutableListOf<Pair<String, AdminMessage>>()
        out += "Begin edit" to admin(passkey) { beginEditSettings = true }

        out += "Owner name" to admin(passkey) {
            setOwner = MeshProtos.User.newBuilder()
                .setLongName(plan.longName.take(24)).setShortName(plan.shortName.take(4)).build()
        }

        // Relay without the key: same channel *name* (=> same frequency slot) but a random key,
        // so it rebroadcasts the group's packets without being able to read them.
        val channelKey = if (plan.role == SetupRole.RELAY && !plan.relayStoresKey) Random.nextBytes(32) else psk
        val settings = ChannelProtos.ChannelSettings.newBuilder()
            .setName(channelName)
            .setPsk(ByteString.copyFrom(channelKey))
            .setModuleSettings(ChannelProtos.ModuleSettings.newBuilder().setPositionPrecision(32))
            .setUseAead(plan.useAead)
        out += "Private channel $channelName" to admin(passkey) {
            setChannel = ChannelProtos.Channel.newBuilder().setIndex(0)
                .setRole(ChannelProtos.Channel.Role.PRIMARY).setSettings(settings).build()
        }

        val dev = (current[Config.DEVICE_FIELD_NUMBER]?.device ?: Config.DeviceConfig.getDefaultInstance()).toBuilder()
        dev.role = when (plan.role) {
            SetupRole.FIELD -> Config.DeviceConfig.Role.TRACKER
            SetupRole.HOME -> Config.DeviceConfig.Role.CLIENT
            SetupRole.RELAY -> Config.DeviceConfig.Role.ROUTER
        }
        dev.rebroadcastMode = Config.DeviceConfig.RebroadcastMode.ALL
        dev.nodeInfoBroadcastSecs = if (plan.role == SetupRole.RELAY) 10800 else 1800
        out += "Role ${dev.role.name}" to admin(passkey) { setConfig = Config.newBuilder().setDevice(dev).build() }

        val pos = (current[Config.POSITION_FIELD_NUMBER]?.position ?: Config.PositionConfig.getDefaultInstance()).toBuilder()
        pos.fixedPosition = false
        pos.positionFlags = 0x0001 or 0x0002 or 0x0020 or 0x0080 or 0x0100 or 0x0200 // alt, msl, sats, timestamp, heading, speed
        when (plan.role) {
            SetupRole.FIELD -> {
                pos.gpsMode = Config.PositionConfig.GpsMode.ENABLED
                pos.gpsUpdateInterval = plan.movingIntervalSec.coerceIn(15, 600) / 2
                pos.positionBroadcastSmartEnabled = true
                pos.broadcastSmartMinimumIntervalSecs = plan.movingIntervalSec.coerceIn(30, 3600)
                pos.broadcastSmartMinimumDistance = plan.minDistanceM.coerceIn(10, 1000)
                pos.positionBroadcastSecs = plan.stillIntervalSec.coerceIn(60, 86_400)
            }
            SetupRole.HOME -> {
                pos.gpsMode = Config.PositionConfig.GpsMode.ENABLED
                pos.gpsUpdateInterval = 120
                pos.positionBroadcastSmartEnabled = true
                pos.broadcastSmartMinimumIntervalSecs = 300
                pos.broadcastSmartMinimumDistance = 100
                pos.positionBroadcastSecs = 900
            }
            SetupRole.RELAY -> {
                pos.gpsMode = if (plan.relayStoresKey) Config.PositionConfig.GpsMode.ENABLED else Config.PositionConfig.GpsMode.DISABLED
                pos.gpsUpdateInterval = 3600
                pos.positionBroadcastSmartEnabled = false
                pos.positionBroadcastSecs = 3600
            }
        }
        out += "GPS / position" to admin(passkey) { setConfig = Config.newBuilder().setPosition(pos).build() }

        val lora = (current[Config.LORA_FIELD_NUMBER]?.lora ?: Config.LoRaConfig.getDefaultInstance()).toBuilder()
        lora.region = Config.LoRaConfig.RegionCode.US
        lora.usePreset = true
        lora.modemPreset = Config.LoRaConfig.ModemPreset.LONG_FAST
        lora.txEnabled = true
        lora.hopLimit = 3
        lora.channelNum = 0           // slot hashed from the channel name
        lora.txPower = 0              // 0 = firmware default (max legal for the board)
        out += "LoRa: US / LongFast" to admin(passkey) { setConfig = Config.newBuilder().setLora(lora).build() }

        if (plan.role != SetupRole.RELAY) {
            // Messages on the radio's own screen: incoming text wakes the display; keep it on long enough to read.
            val disp = (current[Config.DISPLAY_FIELD_NUMBER]?.display ?: Config.DisplayConfig.getDefaultInstance()).toBuilder()
            if (disp.screenOnSecs in 1..59) disp.screenOnSecs = 60
            if (disp.screenOnSecs == 0) disp.screenOnSecs = 60
            out += "Screen: show messages" to admin(passkey) { setConfig = Config.newBuilder().setDisplay(disp).build() }
            // Canned messages: pick and send with the radio's button, no phone needed.
            out += "Canned messages" to admin(passkey) {
                setModuleConfig = ModuleConfigProtos.ModuleConfig.newBuilder().setCannedMessage(
                    ModuleConfigProtos.ModuleConfig.CannedMessageConfig.newBuilder()
                        .setEnabled(true).setAllowInputSource("_any").setSendBell(false)).build()
            }
            out += "Canned message list" to admin(passkey) { setCannedMessageModuleMessages = CANNED_MESSAGES }
        }

        out += "Save & reboot" to admin(passkey) { commitEditSettings = true }
        return out
    }
}
