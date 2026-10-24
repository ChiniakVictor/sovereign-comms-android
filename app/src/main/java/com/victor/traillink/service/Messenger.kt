package com.victor.traillink.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.google.protobuf.ByteString
import com.victor.traillink.R
import com.victor.traillink.core.*
import com.victor.traillink.data.MessageEntity
import com.victor.traillink.data.Prefs
import com.victor.traillink.data.RemovedPartners
import com.victor.traillink.data.TrailDb
import com.victor.traillink.ui.MainActivity
import kotlinx.coroutines.*
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums.PortNum

/** Group chat + direct messages over the internet (retained encrypted outbox) and LoRa (TEXT_MESSAGE_APP). */
class Messenger(private val ctx: Context, private val scope: CoroutineScope, private val host: Host) {
    interface Host {
        val net: NetLink?
        val netKey: ByteArray?
        val radioConnected: Boolean
        val myRadioNum: Long
        suspend fun sendToRadio(bytes: ByteArray): Boolean
    }

    private val dao = TrailDb.get(ctx).msgs()
    private val nodes = TrailDb.get(ctx).dao()
    private val dedupe = MsgDedupe()
    private val receipts = ArrayList<Receipt>()
    private var publishJob: Job? = null
    private val myId get() = Prefs.settings.value.deviceId
    private val myKey get() = "n:$myId"

    // ------------------------------------------------------------------ sending
    suspend fun send(convo: String, textIn: String, sos: Boolean = false) {
        val s = Prefs.settings.value
        val text = ChatMsg.clip(textIn.trim()).ifBlank { return }
        val id = ChatMsg.newId()
        val now = System.currentTimeMillis()
        val pos = PhoneLocation.last
        val to = if (convo == "group") null else convo
        val m = MessageEntity(id, convo, "me", s.myName.ifBlank { "Me" }, to, text, now, mine = true, status = 0, sos = sos,
            lat = if (sos) pos?.first else null, lon = if (sos) pos?.second else null)
        dao.insert(m); dedupe.markSeen(id)
        val vias = mutableListOf<String>()
        // internet: group, or a peer we know by internet id (or a radio a gateway may hear)
        if (s.internetAllowed && host.net != null) { if (publishOutbox()) vias += "net" }
        // LoRa: group broadcast, or DM to a radio node
        if (s.radioAllowed && host.radioConnected && (to == null || to.startsWith("l:"))) {
            val loraText = if (sos) ChatMsg.clip("${ChatMsg.SOS_PREFIX} ${s.myName}: $text" + (m.lat?.let { " %.5f,%.5f".format(it, m.lon) } ?: "")) else text
            if (sendLora(id, to?.removePrefix("l:")?.toLongOrNull(), loraText)) vias += "lora"
        }
        dao.update(m.copy(status = if (vias.isEmpty()) (if (host.net != null) 0 else -1) else 1, via = vias.joinToString("+")))
        LinkRepo.log("Message ${if (sos) "SOS " else ""}to ${to ?: "group"}: ${vias.ifEmpty { listOf("queued") }.joinToString("+")}")
    }

    private suspend fun sendLora(id: String, toNode: Long?, text: String): Boolean {
        val pkt = MeshProtos.MeshPacket.newBuilder()
            .setTo((toNode ?: MeshCodec.BROADCAST).toInt()).setId(id.toLong(16).toInt()).setChannel(0)
            .setWantAck(true)
            .setDecoded(MeshProtos.Data.newBuilder().setPortnum(PortNum.TEXT_MESSAGE_APP).setPayload(ByteString.copyFromUtf8(text)))
            .build()
        return host.sendToRadio(MeshProtos.ToRadio.newBuilder().setPacket(pkt).build().toByteArray())
    }

    /** Rebuilds and publishes this phone's retained outbox (own messages + bridged LoRa messages + receipts, last 24 h). */
    suspend fun publishOutbox(): Boolean {
        val net = host.net ?: return false
        val key = host.netKey ?: return false
        if (!net.online) return false
        val now = System.currentTimeMillis()
        val s = Prefs.settings.value
        val list = dao.outboxSince(now - 24 * 3_600_000L).map { m ->
            ChatMsg(m.id, if (m.mine) myKey else m.fromKey, if (m.mine) s.myName else m.fromName, m.toKey, m.text, m.time, m.sos, m.lat, m.lon)
        }
        val ob = synchronized(receipts) { Outbox(myKey, list, receipts.toList(), now).pruned(now) }
        val ok = net.publishRaw("o/$myId", Outbox.seal(key, ob), 1, true)
        if (ok) dao.outboxSince(now - 24 * 3_600_000L).filter { it.mine && it.status == 0 }.forEach { dao.raiseStatus(it.id, 1) }
        return ok
    }

    private fun schedulePublish() {
        publishJob?.cancel()
        publishJob = scope.launch(Dispatchers.IO) { delay(1500); publishOutbox() }
    }

    private fun addReceipt(ref: String, kind: String) {
        synchronized(receipts) { receipts += Receipt(ref, myKey, kind, System.currentTimeMillis()) }
        schedulePublish()
    }

    // ------------------------------------------------------------------ receiving
    suspend fun onOutbox(bytes: ByteArray) {
        val key = host.netKey ?: return
        val ob = Outbox.open(key, bytes) ?: return
        if (ob.from == myKey) return
        val myRadio = host.myRadioNum.takeIf { it != 0L }?.let { "l:$it" }
        var gotNew = false
        for (m in ob.msgs) {
            if (m.from == myKey) continue
            if (m.to != null && m.to != myKey && m.to != myRadio) {
                // gateway: an internet DM addressed to a radio we can reach over LoRa
                val s = Prefs.settings.value
                if (m.to.startsWith("l:") && s.gateway && host.radioConnected && dedupe.firstTime(m.id, m.ts))
                    sendLora(m.id, m.to.removePrefix("l:").toLongOrNull(), ChatMsg.clip("${m.fromName}: ${m.text}"))
                continue
            }
            if (dao.get(m.id) != null) continue
            if (RemovedPartners.hidesKey(m.from, m.ts)) continue          // removed/blocked on this phone (2.1)
            RemovedPartners.active(RemovedPartners.numForKey(m.from), m.ts)
            if (!dedupe.firstTime(m.id, m.ts)) continue
            val convo = if (m.to == null) "group" else m.from
            dao.insert(MessageEntity(m.id, convo, m.from, m.fromName, m.to, m.text, m.ts, mine = false, sos = m.sos, lat = m.lat, lon = m.lon,
                via = "net", read = false))
            gotNew = true
            notifyIncoming(convo, m.fromName, m.text, m.sos, m.id)
            if (m.from.startsWith("n:")) addReceipt(m.id, "d")
            LinkRepo.log("Message from ${m.fromName} (internet)${if (m.sos) " SOS" else ""}")
            // gateway: relay internet group messages onto LoRa so radios without a phone see them
            val s = Prefs.settings.value
            if (s.gateway && host.radioConnected && m.to == null && !m.from.startsWith("l:"))
                sendLora(m.id, null, ChatMsg.clip((if (m.sos) "${ChatMsg.SOS_PREFIX} " else "") + "${m.fromName}: ${m.text}" + (m.lat?.let { " %.5f,%.5f".format(it, m.lon) } ?: "")))
        }
        for (r in ob.receipts) {
            if (r.by == myKey) continue
            dao.raiseStatus(r.ref, if (r.kind == "r") 3 else 2)
        }
        if (gotNew) LinkRepo.update { it.copy(msgTick = it.msgTick + 1) }
    }

    /** TEXT_MESSAGE_APP packet heard by our radio. */
    suspend fun onLoraText(p: MeshProtos.MeshPacket) {
        val from = p.from.toLong() and 0xFFFFFFFFL
        if (from == host.myRadioNum) return
        if (!RemovedPartners.active(from)) return                          // blocked
        val id = "%08x".format(p.id)
        val now = System.currentTimeMillis()
        if (dao.get(id) != null || !dedupe.firstTime(id, now)) return
        val to = (p.to.toLong() and 0xFFFFFFFFL).let { if (it == MeshCodec.BROADCAST) null else "l:$it" }
        val text = p.decoded.payload.toStringUtf8()
        val sos = text.startsWith(ChatMsg.SOS_PREFIX)
        val coords = if (sos) Regex("(-?\\d{1,2}\\.\\d+),\\s*(-?\\d{1,3}\\.\\d+)").find(text) else null
        val n = nodes.node(from)
        val name = n?.longName ?: NodeStore.defaultLong(from)
        val convo = if (to == null) "group" else "l:$from"
        val s = Prefs.settings.value
        val bridge = s.gateway && to == null && host.net?.online == true
        dao.insert(MessageEntity(id, convo, "l:$from", name, to, text, now, mine = false, sos = sos,
            lat = coords?.groupValues?.get(1)?.toDoubleOrNull() ?: if (sos) n?.lat else null,
            lon = coords?.groupValues?.get(2)?.toDoubleOrNull() ?: if (sos) n?.lon else null, via = "lora", read = false, bridged = bridge))
        notifyIncoming(convo, name, text, sos, id)
        if (bridge) schedulePublish()
        LinkRepo.log("Message from $name (LoRa)${if (bridge) ", bridged to internet" else ""}")
        LinkRepo.update { it.copy(msgTick = it.msgTick + 1) }
    }

    /** ROUTING_APP reply: an ack for one of our packets. */
    suspend fun onRouting(p: MeshProtos.MeshPacket) {
        val req = p.decoded.requestId
        if (req == 0) return
        val id = "%08x".format(req)
        val m = dao.get(id) ?: return
        if (!m.mine) return
        val err = runCatching { MeshProtos.Routing.parseFrom(p.decoded.payload).errorReason }.getOrNull()
        if (err == null || err == MeshProtos.Routing.Error.NONE) dao.raiseStatus(id, if (m.toKey != null) 2 else 1)
    }

    /** The user opened a conversation: mark read and send read receipts for internet messages. */
    suspend fun markRead(convo: String) {
        val unread = dao.unreadIn(convo)
        if (unread.isEmpty()) return
        dao.markRead(convo)
        unread.filter { it.fromKey.startsWith("n:") }.forEach { addReceipt(it.id, "r") }
        NotificationManagerCompat.from(ctx).cancel(notifId(convo))
    }

    // ------------------------------------------------------------------ notifications
    fun notifId(convo: String) = 2000 + (convo.hashCode() and 0xFFFF)

    private fun notifyIncoming(convo: String, from: String, text: String, sos: Boolean, id: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (!sos && MessagesVisible.convo == convo) return
        val open = PendingIntent.getActivity(ctx, notifId(convo),
            Intent(ctx, MainActivity::class.java).putExtra("tab", "messages").putExtra("convo", convo)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(ctx, if (sos) LinkService.CH_SOS else LinkService.CH_MSG)
            .setSmallIcon(R.drawable.ic_stat_trail)
            .setContentTitle(if (sos) "🆘 SOS from $from" else if (convo == "group") "$from (group)" else from)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (sos) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (sos) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open).setAutoCancel(true)
        if (!sos) {
            val reply = PendingIntent.getBroadcast(ctx, notifId(convo),
                Intent(ctx, ReplyReceiver::class.java).putExtra("convo", convo),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(NotificationCompat.Action.Builder(0, "Reply", reply)
                .addRemoteInput(RemoteInput.Builder(ReplyReceiver.KEY).setLabel("Message").build()).setAllowGeneratedReplies(true).build())
        }
        NotificationManagerCompat.from(ctx).notify(if (sos) 3000 + (id.hashCode() and 0xFFF) else notifId(convo), b.build())
    }
}

/** Which conversation is on screen (suppresses notifications for it). */
object MessagesVisible { @Volatile var convo: String? = null }

class ReplyReceiver : android.content.BroadcastReceiver() {
    companion object { const val KEY = "reply_text" }
    override fun onReceive(c: Context, i: Intent) {
        val text = RemoteInput.getResultsFromIntent(i)?.getCharSequence(KEY)?.toString() ?: return
        val convo = i.getStringExtra("convo") ?: return
        LinkService.sendMessage(c, convo, text, false)
        // replace the notification so the spinner stops
        NotificationManagerCompat.from(c).cancel(2000 + (convo.hashCode() and 0xFFFF))
    }
}
