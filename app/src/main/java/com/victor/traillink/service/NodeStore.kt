package com.victor.traillink.service

import com.victor.traillink.core.Fix
import com.victor.traillink.core.Geo
import com.victor.traillink.core.MeshCodec
import com.victor.traillink.data.NodeEntity
import com.victor.traillink.data.TrackPoint
import com.victor.traillink.data.TrailDao
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums.PortNum
import org.meshtastic.proto.TelemetryProtos

/** Turns radio data (NodeInfo / MeshPackets) or simulated data into rows in the Room database. */
class NodeStore(private val dao: TrailDao) {

    fun u32(i: Int): Long = i.toLong() and 0xFFFFFFFFL

    suspend fun onNodeInfo(n: MeshProtos.NodeInfo, myNode: Long) {
        val num = u32(n.num)
        // removed on this phone: the radio's stored node list doesn't bring them back, only being heard after the removal
        if (num != myNode && com.victor.traillink.data.RemovedPartners.hides(num, u32(n.lastHeard) * 1000)) return
        val old = dao.node(num)
        val fix = if (n.hasPosition()) MeshCodec.fixFrom(n.position) else null
        val heard = u32(n.lastHeard) * 1000
        val bat = if (n.hasDeviceMetrics() && n.deviceMetrics.hasBatteryLevel()) n.deviceMetrics.batteryLevel else null
        val e = (old ?: NodeEntity(num, defaultLong(num), defaultShort(num))).copy(
            longName = n.user.longName.ifBlank { old?.longName ?: defaultLong(num) },
            shortName = n.user.shortName.ifBlank { old?.shortName ?: defaultShort(num) },
            lastHeard = maxOf(old?.lastHeard ?: 0, heard),
            snr = if (n.snr != 0f) n.snr else old?.snr,
            battery = bat ?: old?.battery,
            hops = if (n.hasHopsAway()) n.hopsAway else old?.hops,
            isSelf = num == myNode,
        ).let { if (fix != null) withFix(it, fix, if (fix.timeSec > 0) fix.timeSec * 1000 else heard).let { w -> if (w.posTime != it.posTime) w.copy(posSource = "lora") else w } else it }
        dao.upsert(e)
        if (fix != null && num != 0L) addPoint(e, fix, if (fix.timeSec > 0) fix.timeSec * 1000 else heard)
    }

    /** Returns a short human description of what was received (for the log), or null. */
    suspend fun onPacket(p: MeshProtos.MeshPacket, myNode: Long, now: Long = System.currentTimeMillis()): String? {
        if (!p.hasDecoded()) return null
        val from = u32(p.from)
        if (from == 0L) return null
        if (from != myNode && !com.victor.traillink.data.RemovedPartners.active(from, now)) return null   // blocked
        val old = dao.node(from)
        var e = (old ?: NodeEntity(from, defaultLong(from), defaultShort(from))).copy(
            lastHeard = now,
            isSelf = from == myNode,
            snr = if (from != myNode && p.rxSnr != 0f) p.rxSnr else old?.snr,
            rssi = if (from != myNode && p.rxRssi != 0) p.rxRssi else old?.rssi,
            hops = if (p.hopStart > 0) p.hopStart - p.hopLimit else old?.hops,
        )
        val d = p.decoded
        var desc: String? = null
        when (d.portnum) {
            PortNum.POSITION_APP -> {
                val fix = MeshCodec.fixFrom(MeshProtos.Position.parseFrom(d.payload))
                if (fix != null) {
                    e = withFix(e, fix, now).let { if (it.posTime == now) it.copy(posSource = "lora") else it }
                    dao.upsert(e)
                    addPoint(e, fix, now)
                    desc = "position from ${e.longName}"
                    return desc
                }
            }
            PortNum.NODEINFO_APP -> {
                val u = MeshProtos.User.parseFrom(d.payload)
                e = e.copy(longName = u.longName.ifBlank { e.longName }, shortName = u.shortName.ifBlank { e.shortName })
                desc = "node info ${e.longName}"
            }
            PortNum.TELEMETRY_APP -> {
                val t = TelemetryProtos.Telemetry.parseFrom(d.payload)
                if (t.hasDeviceMetrics()) {
                    val m = t.deviceMetrics
                    e = e.copy(battery = if (m.hasBatteryLevel()) m.batteryLevel else e.battery,
                        voltage = if (m.hasVoltage()) m.voltage else e.voltage)
                    desc = "telemetry ${e.longName} ${e.battery ?: "?"}%"
                }
            }
            else -> {}
        }
        dao.upsert(e)
        return desc
    }

    /** Internet position (own phone GPS of a partner, or a radio position forwarded by a gateway). Returns true if it updated the map. */
    suspend fun onNet(p: com.victor.traillink.core.NetPosition): Boolean {
        val forwarded = p.src == com.victor.traillink.core.NetPosition.SRC_LORA && p.node != null
        val num = if (forwarded) p.node!! else netNum(p.id)
        // retained positions from before a removal don't bring a partner back; a newer one does
        if (com.victor.traillink.data.RemovedPartners.hides(num, p.ts)) return false
        com.victor.traillink.data.RemovedPartners.active(num, p.ts)
        val old = dao.node(num)
        val newer = p.ts > (old?.posTime ?: 0)
        var e = (old ?: NodeEntity(num, if (forwarded) defaultLong(num) else p.name.ifBlank { "Phone ${p.id}" },
            if (forwarded) defaultShort(num) else p.short.ifBlank { p.id.take(4) })).copy(
            lastHeard = maxOf(old?.lastHeard ?: 0, p.ts),
            netId = if (forwarded) old?.netId else p.id,
            battery = if (newer) p.battery ?: old?.battery else old?.battery,
        )
        if (!forwarded) e = e.copy(longName = p.name.ifBlank { e.longName }, shortName = p.short.ifBlank { e.shortName })
        if (newer) {
            e = e.copy(lat = p.lat, lon = p.lon, altM = p.alt ?: e.altM, posTime = p.ts, posSource = if (forwarded) "gw" else "net", accM = p.acc,
                snr = if (forwarded) p.snr ?: e.snr else e.snr, rssi = if (forwarded) p.rssi ?: e.rssi else e.rssi)
        }
        dao.upsert(e)
        // coarse fixes (cell/Wi-Fi, > 100 m) update the dot but are not recorded in the breadcrumb trail
        if (newer && (p.acc == null || p.acc <= MAX_TRAIL_ACC_M)) addPoint(e, Fix(p.lat, p.lon, p.alt), p.ts)
        return newer
    }

    private fun withFix(e: NodeEntity, f: Fix, t: Long) =
        if (t >= e.posTime) e.copy(lat = f.lat, lon = f.lon, altM = f.altM ?: e.altM, posTime = t) else e

    private suspend fun addPoint(e: NodeEntity, f: Fix, t: Long) {
        val last = dao.lastPoint(e.num)
        if (last != null) {
            if (t <= last.time) return
            val moved = Geo.distanceM(last.lat, last.lon, f.lat, f.lon)
            if (moved < 3 && t - last.time < 60_000) return
            // implausible jump (> ~300 km/h): drop it from the trail
            if (moved > 200 && moved / ((t - last.time).coerceAtLeast(1) / 1000.0) > MAX_SPEED_MPS) return
        }
        dao.insert(TrackPoint(nodeNum = e.num, time = t, lat = f.lat, lon = f.lon, altM = f.altM,
            snr = e.snr, rssi = e.rssi, battery = e.battery))
    }

    companion object {
        fun defaultLong(num: Long) = "Node !%08x".format(num)
        const val MAX_TRAIL_ACC_M = 100f
        const val MAX_SPEED_MPS = 83.0   // 300 km/h
        const val MAX_GAP_MS = 10 * 60_000L
        fun defaultShort(num: Long) = "%04x".format(num and 0xFFFF)
        /** Internet-only phones get node numbers above the 32-bit Meshtastic range. */
        fun netNum(id: String): Long = 0x1_0000_0000L or (id.take(8).toLongOrNull(16) ?: (id.hashCode().toLong() and 0xFFFFFFFFL))
    }
}
