package com.victor.traillink.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.victor.traillink.R
import com.victor.traillink.core.*
import com.victor.traillink.data.MessageEntity
import com.victor.traillink.data.Prefs
import com.victor.traillink.data.TrailDb
import com.victor.traillink.ui.MainActivity
import kotlinx.coroutines.*
import java.io.File

/**
 * Push-to-talk over the internet. Sender: live ~400 ms chunks (QoS 0) while the button is held, then the complete
 * clip retained in one of 5 rotating slots (24 h) for late/offline receivers. Receiver: plays live with walkie beeps
 * (or notify only), stores every clip as a voice bubble in the conversation.
 */
class VoiceHub(private val ctx: Context, private val scope: CoroutineScope, private val net: () -> NetLink?, private val netKey: () -> ByteArray?) {
    private val dao = TrailDb.get(ctx).msgs()
    private val dir = File(ctx.filesDir, "voice").apply { mkdirs() }
    private val myId get() = Prefs.settings.value.deviceId
    private val myKey get() = "n:$myId"

    // ------------------------------------------------------------------ sending
    private var rec: VoiceRecorder? = null
    private var txId: String? = null

    val transmitting get() = rec != null

    /** Starts recording to [convo] ("group" or peer key). Returns an error text, or null when talking. */
    fun startTalk(convo: String): String? {
        if (rec != null) return null
        val n = net(); val key = netKey()
        if (n == null || key == null || !n.online) return "Push-to-talk needs Internet sharing on and online"
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return "Microphone permission needed"
        val vk = VoiceWire.deriveVoiceKey(key); val g = NetCrypto.groupTopic(key)
        val id = ChatMsg.newId(); txId = id
        val ts = System.currentTimeMillis()
        val s = Prefs.settings.value
        val to = if (convo == "group") null else convo
        val all = ArrayList<ByteArray>()
        var seq = 0
        var csdAll: ByteArray? = null
        AudioRoute.acquire(ctx); AudioRoute.beep(false)
        LinkRepo.update { it.copy(talking = "You") }
        rec = VoiceRecorder { frames, csd, codec, last, dur ->
            all += frames; if (csd != null) csdAll = csd
            val h = VoiceHeader(id, myKey, s.myName.ifBlank { "Me" }, to, ts, codec, VoiceRecorder.SR, seq++, last, dur, csd)
            n.publishRaw("v/$myId", VoiceWire.seal(vk, g, VoicePacket(h, frames)), 0, false)
            if (last) scope.launch(Dispatchers.IO) { finishTx(id, convo, to, ts, codec, csdAll, all.toList(), dur, vk, g) }
        }.also { r -> scope.launch(Dispatchers.Default) { delay(130); if (txId == id) r.start() } }
        LinkRepo.log("Voice: talking to ${to ?: "group"}")
        return null
    }

    fun stopTalk() {
        val r = rec ?: return
        rec = null; txId = null
        r.stop()
        scope.launch(Dispatchers.Default) { r.join(); AudioRoute.beep(true); delay(300); AudioRoute.release(ctx); LinkRepo.update { it.copy(talking = null) } }
    }

    private suspend fun finishTx(id: String, convo: String, to: String?, ts: Long, codec: String, csd: ByteArray?, frames: List<ByteArray>, dur: Long, vk: ByteArray, g: String) {
        if (frames.isEmpty() || dur < 300) { LinkRepo.log("Voice: too short, not sent"); return }
        val s = Prefs.settings.value
        val pkt = VoicePacket(VoiceHeader(id, myKey, s.myName.ifBlank { "Me" }, to, ts, codec, VoiceRecorder.SR, -1, true, dur, csd), frames)
        val f = File(dir, "$id.tlv"); f.writeBytes(VoiceWire.pack(pkt))
        val slot = Prefs.nextVoiceSlot(ctx)
        val sealed = VoiceWire.seal(vk, g, pkt)
        val ok = net()?.publishRaw("vc/$myId/$slot", sealed, 1, true) == true
        dao.insert(MessageEntity(id, convo, "me", s.myName, to, "🎙 Voice clip", ts, mine = true, status = if (ok) 1 else -1,
            via = "net", kind = "voice", clipFile = f.absolutePath, durMs = dur))
        LinkRepo.log("Voice: clip ${dur / 1000.0}s, ${frames.size} frames, ${sealed.size / 1024} KB $codec ${if (ok) "sent" else "NOT sent"}")
    }

    // ------------------------------------------------------------------ receiving
    private class Rx(val h: VoiceHeader, var player: VoicePlayer?, val frames: java.util.TreeMap<Int, List<ByteArray>> = java.util.TreeMap(),
                     var lastAt: Long = System.currentTimeMillis(), var done: Boolean = false)
    private val rx = HashMap<String, Rx>()
    private var watchdog: Job? = null

    private fun forMe(h: VoiceHeader) = h.from != myKey && (h.to == null || h.to == myKey)
    private fun convoOf(h: VoiceHeader) = if (h.to == null) "group" else h.from

    fun onNet(rel: String, bytes: ByteArray) {
        val key = netKey() ?: return
        val p = VoiceWire.open(VoiceWire.deriveVoiceKey(key), NetCrypto.groupTopic(key), bytes) ?: return
        if (!forMe(p.h)) return
        if (com.victor.traillink.data.RemovedPartners.hidesKey(p.h.from, p.h.ts)) return   // removed/blocked (2.1)
        com.victor.traillink.data.RemovedPartners.active(com.victor.traillink.data.RemovedPartners.numForKey(p.h.from), p.h.ts)
        if (p.h.seq < 0) scope.launch(Dispatchers.IO) { onClip(p) } else onLive(p)
    }

    @Synchronized private fun onLive(p: VoicePacket) {
        val now = System.currentTimeMillis()
        if (now - p.h.ts > 120_000 || p.h.ts - now > 300_000) return
        var r = rx[p.h.id]
        if (r == null) {
            if (p.h.seq > 3 && rx.isEmpty()) { /* joined late: still play from here */ }
            val auto = Prefs.settings.value.voiceAutoPlay
            val player = if (auto) {
                AudioRoute.acquire(ctx); AudioRoute.beep(false)
                VoicePlayer(p.h.codec, p.h.sr, p.h.csd) { onPlayerDone(p.h.id) }.start()
            } else null
            r = Rx(p.h, player); rx[p.h.id] = r
            LinkRepo.update { it.copy(talking = p.h.name) }
            if (!auto) notify(p.h, live = true)
            LinkRepo.log("Voice: ${p.h.name} talking (${p.h.codec})")
            startWatchdog()
        }
        if (r.done) return
        r.lastAt = now
        r.frames[p.h.seq] = p.frames
        r.player?.feed(p.frames)
        if (p.h.last) endRx(r, p.h.durMs)
    }

    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)
                synchronized(this@VoiceHub) {
                    rx.values.filter { !it.done && System.currentTimeMillis() - it.lastAt > 5000 }.forEach { endRx(it, null) }
                    if (rx.values.all { it.done }) return@launch
                }
            }
        }
    }

    private fun endRx(r: Rx, dur: Long?) {
        r.done = true
        r.player?.finish()
        if (r.player == null) finishedTalking(r.h.id)
        val frames = r.frames.values.flatten()
        val d = dur ?: (frames.size * 20L)
        scope.launch(Dispatchers.IO) { store(VoicePacket(r.h.copy(seq = -1, last = true, durMs = d), frames), complete = dur != null && r.frames.size == r.frames.lastKey() + 1, fromLive = true) }
    }

    private fun onPlayerDone(id: String) { AudioRoute.beep(true); scope.launch { delay(300); AudioRoute.release(ctx) }; finishedTalking(id) }

    @Synchronized private fun finishedTalking(id: String) {
        val r = rx[id]
        if (r != null && rx.values.none { it !== r && !it.done }) LinkRepo.update { it.copy(talking = null) }
        rx.entries.removeIf { it.value.done && System.currentTimeMillis() - it.value.lastAt > 60_000 }
    }

    /** Complete retained clip (late/offline receivers, or the full copy after a live stream). */
    private suspend fun onClip(p: VoicePacket) {
        if (System.currentTimeMillis() - p.h.ts > 24 * 3_600_000L) return
        val known = dao.get(p.h.id)
        val live = synchronized(this) { rx[p.h.id] }
        store(p, complete = true, fromLive = live != null)
        if (known == null && live == null) {
            val fresh = System.currentTimeMillis() - p.h.ts < 90_000
            if (fresh && Prefs.settings.value.voiceAutoPlay) play(File(dir, "${p.h.id}.tlv").absolutePath) else notify(p.h, live = false)
        }
    }

    private suspend fun store(p: VoicePacket, complete: Boolean, fromLive: Boolean) {
        val f = File(dir, "${p.h.id}.tlv")
        val existing = dao.get(p.h.id)
        if (existing != null && !complete) return
        if (existing != null && existing.durMs != null && f.exists() && f.length() >= VoiceWire.pack(p).size) return
        f.writeBytes(VoiceWire.pack(p))
        val m = MessageEntity(p.h.id, convoOf(p.h), p.h.from, p.h.name, p.h.to, "🎙 Voice clip", p.h.ts, mine = false, via = "net",
            read = false, kind = "voice", clipFile = f.absolutePath, durMs = p.h.durMs)
        if (existing == null) dao.insert(m) else dao.update(m.copy(read = existing.read))
        LinkRepo.update { it.copy(msgTick = it.msgTick + 1) }
    }

    private fun notify(h: VoiceHeader, live: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val convo = convoOf(h)
        val open = PendingIntent.getActivity(ctx, 4000 + (convo.hashCode() and 0xFFF),
            Intent(ctx, MainActivity::class.java).putExtra("tab", "messages").putExtra("convo", convo)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        NotificationManagerCompat.from(ctx).notify(4000 + (h.id.hashCode() and 0xFFF), NotificationCompat.Builder(ctx, LinkService.CH_MSG)
            .setSmallIcon(R.drawable.ic_stat_trail)
            .setContentTitle("🎙 ${h.name}${if (h.to == null) " (group)" else ""}")
            .setContentText(if (live) "is talking — tap to listen in Chat" else "sent a voice clip").setContentIntent(open).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE).build())
    }

    companion object {
        @Volatile private var replay: VoicePlayer? = null
        /** Replays a stored clip (works without the service). */
        fun play(path: String, ctx: Context? = null, onDone: () -> Unit = {}) {
            val p = runCatching { VoiceWire.unpack(File(path).readBytes()) }.getOrNull() ?: return
            replay?.stop()
            ctx?.let { AudioRoute.acquire(it) }
            AudioRoute.beep(false)
            replay = VoicePlayer(p.h.codec, p.h.sr, p.h.csd) {
                AudioRoute.beep(true); ctx?.let { c -> Thread.sleep(250); AudioRoute.release(c) }; onDone()
            }.start().also { it.feed(p.frames); it.finish() }
        }
        fun stopReplay() { replay?.stop(); replay = null }
    }
}
