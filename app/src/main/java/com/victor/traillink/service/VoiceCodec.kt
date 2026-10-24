package com.victor.traillink.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "TrailLink"

/** Bluetooth headset routing + audio focus (duck others) for push-to-talk. */
object AudioRoute {
    private var refs = 0
    private var focus: AudioFocusRequest? = null
    @Volatile var btActive = false; private set

    private fun btDevice(am: AudioManager): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } else null

    @Synchronized fun acquire(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (refs++ > 0) return
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build().also { am.requestAudioFocus(it) }
        val bt = btDevice(am)
        if (bt != null && Build.VERSION.SDK_INT >= 31) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            btActive = am.setCommunicationDevice(bt)
            Log.i(TAG, "Voice: Bluetooth headset ${bt.productName} ${if (btActive) "routed" else "routing failed"}")
        }
    }

    @Synchronized fun release(ctx: Context) {
        if (refs == 0 || --refs > 0) return
        val am = ctx.getSystemService(AudioManager::class.java)
        focus?.let { am.abandonAudioFocusRequest(it) }; focus = null
        if (btActive && Build.VERSION.SDK_INT >= 31) { am.clearCommunicationDevice(); am.mode = AudioManager.MODE_NORMAL }
        btActive = false
    }

    fun attrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (btActive) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private val tones by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }
    /** Walkie-talkie beeps: in = single short beep, out = "roger" double beep. */
    fun beep(out: Boolean) { runCatching { tones?.startTone(if (out) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, if (out) 220 else 120) } }
}

/**
 * Records 16 kHz mono from the mic and encodes with MediaCodec: Opus ~20 kbps (API 29+), else AAC-LC 24 kbps.
 * [onChunk] is called about every 400 ms with the encoded frames, and once more with last = true.
 */
class VoiceRecorder(private val onChunk: (frames: List<ByteArray>, csd: ByteArray?, codec: String, last: Boolean, durMs: Long) -> Unit) {
    companion object {
        const val SR = 16000
        const val CHUNK_MS = 400L
        fun pickCodec(): Pair<String, String> {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            if (Build.VERSION.SDK_INT >= 29) {
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SR, 1).apply { setInteger(MediaFormat.KEY_BIT_RATE, 20000) }
                if (list.findEncoderForFormat(f) != null) return "opus" to MediaFormat.MIMETYPE_AUDIO_OPUS
            }
            return "aac" to MediaFormat.MIMETYPE_AUDIO_AAC
        }
    }
    @Volatile private var running = false
    private var thread: Thread? = null
    var codecName = ""; private set

    @SuppressLint("MissingPermission")
    fun start() {
        running = true
        thread = Thread({ loop() }, "ptt-rec").apply { priority = Thread.MAX_PRIORITY; start() }
    }
    fun stop() { running = false }
    fun join() { thread?.join(3000) }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val (name, mime) = pickCodec(); codecName = name
        val fmt = MediaFormat.createAudioFormat(mime, SR, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, if (name == "opus") 20000 else 24000)
            if (name == "aac") setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
        }
        val enc = MediaCodec.createByCodecName(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(fmt))
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        val pcm = ByteArray(640)   // 20 ms
        var csd: ByteArray? = null
        val pending = ArrayList<ByteArray>()
        var samples = 0L
        var lastFlush = 0L
        val info = MediaCodec.BufferInfo()
        fun drain(eos: Boolean) {
            while (true) {
                val o = enc.dequeueOutputBuffer(info, if (eos) 20_000 else 0)
                if (o < 0) { if (o == MediaCodec.INFO_TRY_AGAIN_LATER && !eos) return; if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return; continue }
                val b = enc.getOutputBuffer(o)!!
                val bytes = ByteArray(info.size).also { b.position(info.offset); b.get(it) }
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) csd = (csd ?: ByteArray(0)) + bytes
                else if (bytes.isNotEmpty()) pending += bytes
                enc.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
        try {
            rec.startRecording()
            while (running && samples * 1000 / SR < VoiceRecorderLimit.maxMs) {
                var n = 0
                while (n < pcm.size) { val r = rec.read(pcm, n, pcm.size - n); if (r <= 0) break; n += r }
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    enc.getInputBuffer(i)!!.apply { clear(); put(pcm, 0, n) }
                    enc.queueInputBuffer(i, 0, n, samples * 1_000_000 / SR, 0)
                    samples += n / 2
                }
                drain(false)
                val ms = samples * 1000 / SR
                if (ms - lastFlush >= CHUNK_MS && pending.isNotEmpty()) { onChunk(pending.toList(), csd, name, false, ms); pending.clear(); lastFlush = ms }
            }
            rec.stop()
            val i = enc.dequeueInputBuffer(50_000)
            if (i >= 0) enc.queueInputBuffer(i, 0, 0, samples * 1_000_000 / SR, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } catch (e: Exception) { Log.w(TAG, "Voice: recorder error $e") }
        finally {
            runCatching { rec.release() }; runCatching { enc.stop(); enc.release() }
            onChunk(pending.toList(), csd, name, true, samples * 1000 / SR)
        }
    }
}
object VoiceRecorderLimit { var maxMs = com.victor.traillink.core.VoiceWire.MAX_CLIP_MS }

/** Streams encoded frames through a MediaCodec decoder into an AudioTrack. Feed frames, then [finish]. */
class VoicePlayer(private val codec: String, private val sr: Int, private val csd: ByteArray?, private val onDone: () -> Unit) {
    private val q = LinkedBlockingQueue<ByteArray>()
    private val END = ByteArray(0)
    @Volatile private var stopped = false
    private val thread = Thread({ loop() }, "ptt-play")

    fun start(): VoicePlayer { thread.start(); return this }
    fun feed(frames: List<ByteArray>) { frames.forEach { q.offer(it) } }
    fun finish() { q.offer(END) }
    fun stop() { stopped = true; q.offer(END) }

    private fun format(): MediaFormat {
        val mime = if (codec == "opus") MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AAC
        val f = MediaFormat.createAudioFormat(mime, if (codec == "opus") 48000 else sr, 1)
        val c = csd ?: return f
        if (codec == "opus" && c.size >= 8 && String(c, 0, 8, Charsets.US_ASCII) == "OpusHead") {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(c))
            fun ns(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(v).apply { flip() }
            val preSkip = (c[10].toInt() and 0xff) or ((c[11].toInt() and 0xff) shl 8)
            f.setByteBuffer("csd-1", ns(preSkip * 1_000_000_000L / 48000)); f.setByteBuffer("csd-2", ns(80_000_000L))
        } else f.setByteBuffer("csd-0", ByteBuffer.wrap(c))
        return f
    }

    private fun loop() {
        var dec: MediaCodec? = null; var track: AudioTrack? = null
        try {
            val fmt = format()
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(fmt, null, null, 0); dec.start()
            val info = MediaCodec.BufferInfo()
            var pts = 0L; var eosQueued = false
            fun makeTrack(rate: Int, ch: Int): AudioTrack {
                val cfg = if (ch >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val min = AudioTrack.getMinBufferSize(rate, cfg, AudioFormat.ENCODING_PCM_16BIT)
                return AudioTrack.Builder().setAudioAttributes(AudioRoute.attrs())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(cfg).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(min * 4).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
            }
            while (!stopped) {
                if (!eosQueued) {
                    val f = q.poll(6, TimeUnit.SECONDS) ?: END
                    val i = dec.dequeueInputBuffer(20_000)
                    if (i >= 0) {
                        if (f === END || f.isEmpty() && q.isEmpty() && f === END) { dec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosQueued = true }
                        else { dec.getInputBuffer(i)!!.apply { clear(); put(f) }; dec.queueInputBuffer(i, 0, f.size, pts, 0); pts += 20_000 }
                    } else if (f !== END) q.offer(f) else q.offer(END)
                }
                while (true) {
                    val o = dec.dequeueOutputBuffer(info, if (eosQueued) 20_000 else 0)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = dec.outputFormat
                        track?.release(); track = makeTrack(of.getInteger(MediaFormat.KEY_SAMPLE_RATE), of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)); continue
                    }
                    if (o < 0) break
                    val b = dec.getOutputBuffer(o)!!
                    if (info.size > 0) {
                        if (track == null) track = makeTrack(if (codec == "opus") 48000 else sr, 1)
                        val pcm = ByteArray(info.size); b.position(info.offset); b.get(pcm)
                        track.write(pcm, 0, pcm.size)
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { stopped = true; break }
                }
            }
            // let the tail play out
            track?.let { t -> runCatching { t.stop() }; Thread.sleep(150) }
        } catch (e: Exception) { Log.w(TAG, "Voice: player error $e") }
        finally {
            runCatching { dec?.stop(); dec?.release() }; runCatching { track?.release() }
            onDone()
        }
    }
}
