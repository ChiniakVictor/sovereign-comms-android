package com.victor.traillink.service

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.victor.traillink.R
import com.victor.traillink.core.*
import com.victor.traillink.data.PinEntity
import com.victor.traillink.data.Prefs
import com.victor.traillink.data.TrailDb
import com.victor.traillink.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums.PortNum

/** Shared pins: LoRa WAYPOINT_APP + internet retained per-pin topic, dedupe by id/updated, gateway bridging. */
class PinHub(private val ctx: Context, private val scope: CoroutineScope, private val host: Messenger.Host) {
    private val dao = TrailDb.get(ctx).pins()
    private val nodes = TrailDb.get(ctx).dao()
    private val myId get() = Prefs.settings.value.deviceId

    companion object {
        fun toMsg(p: PinEntity, myKey: String) = PinMsg(p.id, p.name, p.kind, p.color, p.note, p.lat, p.lon, p.alt,
            if (p.mine) myKey else p.byKey, p.byName, p.created, p.updated, p.expires, p.deleted, p.emoji)
    }

    /** Called after the UI saved/edited/deleted one of my pins. */
    suspend fun publish(id: String) {
        val p = dao.get(id) ?: return
        if (!p.mine || !p.shared) return
        val m = toMsg(p, "n:$myId")
        val s = Prefs.settings.value
        val vias = mutableListOf<String>()
        if (s.internetAllowed && netPublish(m)) vias += "net"
        if (s.radioAllowed && host.radioConnected && loraSend(m)) vias += "lora"
        LinkRepo.log("Pin '${p.name}' ${if (p.deleted) "deleted" else "shared"}: ${vias.ifEmpty { listOf("queued") }.joinToString("+")}")
    }

    /** Internet came online: (re)publish my pins; clear expired ones from the broker. */
    suspend fun publishAllMine() {
        val now = System.currentTimeMillis()
        for (p in dao.mineShared()) {
            if (p.expires in 1 until now) host.net?.publishRaw(PinMsg.topic(myId, p.id), ByteArray(0), 1, true)
            else netPublish(toMsg(p, "n:$myId"))
        }
    }

    private fun netPublish(m: PinMsg): Boolean {
        val key = host.netKey ?: return false
        val n = host.net ?: return false
        if (!n.online) return false
        val owner = if (m.by.startsWith("n:")) m.by.removePrefix("n:") else myId   // gateway-bridged radio pins live under our id
        return n.publishRaw(PinMsg.topic(owner, m.id), PinMsg.seal(key, m), 1, true)
    }

    private suspend fun loraSend(m: PinMsg): Boolean {
        val w = WaypointCodec.encode(m, lockedTo = if (m.by.startsWith("n:") && m.by == "n:$myId") host.myRadioNum else 0)
        val pkt = MeshProtos.MeshPacket.newBuilder().setTo(MeshCodec.BROADCAST.toInt()).setChannel(0).setWantAck(false)
            .setId(kotlin.random.Random.nextInt() and 0x7fffffff or 0x10000)
            .setDecoded(MeshProtos.Data.newBuilder().setPortnum(PortNum.WAYPOINT_APP).setPayload(w.toByteString())).build()
        return host.sendToRadio(MeshProtos.ToRadio.newBuilder().setPacket(pkt).build().toByteArray())
    }

    // ------------------------------------------------------------------ receiving
    suspend fun onNet(bytes: ByteArray) {
        val key = host.netKey ?: return
        val m = PinMsg.open(key, bytes) ?: return
        if (m.by == "n:$myId") return
        if (accept(m, "net") && Prefs.settings.value.gateway && host.radioConnected && !m.by.startsWith("l:")) loraSend(m)
    }

    suspend fun onLora(p: MeshProtos.MeshPacket) {
        val from = p.from.toLong() and 0xFFFFFFFFL
        if (from == host.myRadioNum) return
        val w = runCatching { MeshProtos.Waypoint.parseFrom(p.decoded.payload) }.getOrNull() ?: return
        val name = nodes.node(from)?.longName ?: NodeStore.defaultLong(from)
        val m = WaypointCodec.decode(w, from, name, System.currentTimeMillis())
        val old = dao.get(m.id)
        if (old?.mine == true) return
        // LoRa carries no timestamps: keep the original creation time and treat a re-send as an update
        val mm = if (old != null) m.copy(created = old.created, color = old.color, alt = old.alt) else m
        if (accept(mm, "lora", force = true) && Prefs.settings.value.gateway && host.net?.online == true) netPublish(mm)
    }

    /** Stores the pin if new or newer; returns true when something changed. */
    private suspend fun accept(m: PinMsg, via: String, force: Boolean = false): Boolean {
        // removed/blocked partner (2.1): ignore their old retained pins; a pin made/edited after the removal is new activity
        val rk = com.victor.traillink.data.RemovedPartners
        if (rk.hidesKey(m.by, if (via == "lora") System.currentTimeMillis() else m.updated)) return false
        val old = dao.get(m.id)
        if (old != null && !force && m.updated <= old.updated) return false
        if (old != null && old.mine) return false
        if (old != null && force && old.deleted == m.deleted && old.name == m.name && old.note == m.note && old.lat == m.lat && old.lon == m.lon && old.expires == m.expires) return false
        dao.put(PinEntity(m.id, m.name, m.kind, m.color, m.note, m.lat, m.lon, m.alt, m.by, m.byName, m.created, m.updated, m.expires,
            shared = true, mine = false, hidden = old?.hidden ?: false, deleted = m.deleted, emoji = m.emoji))
        val now = System.currentTimeMillis()
        if (old == null && !m.deleted && (m.expires == 0L || m.expires > now) && now - m.created < 24 * 3_600_000L) notifyNew(m)
        LinkRepo.log("Pin '${m.name}' from ${m.byName} ($via)${if (m.deleted) " deleted" else ""}")
        return true
    }

    private fun notifyNew(m: PinMsg) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val kind = PinKind.of(m.kind)
        val where = PhoneLocation.last?.let { (la, lo) ->
            " " + Geo.formatDistance(Geo.distanceM(la, lo, m.lat, m.lon), Prefs.settings.value.metric) + " " + Geo.cardinal(Geo.bearingDeg(la, lo, m.lat, m.lon))
        } ?: ""
        val open = PendingIntent.getActivity(ctx, 5000 + (m.id.hashCode() and 0xFFF),
            Intent(ctx, MainActivity::class.java).putExtra("tab", "map").putExtra("pin", m.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        NotificationManagerCompat.from(ctx).notify(5000 + (m.id.hashCode() and 0xFFF), NotificationCompat.Builder(ctx, LinkService.CH_MSG)
            .setSmallIcon(R.drawable.ic_stat_trail)
            .setContentTitle("${m.emoji ?: kind.emoji} ${m.byName} dropped ${if (kind == PinKind.OTHER) m.name else kind.label}$where")
            .setContentText(m.name + if (m.note.isNotBlank()) " — ${m.note}" else "")
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build())
    }
}
