package com.victor.traillink.service

import com.victor.traillink.core.NetCrypto
import com.victor.traillink.core.NetPosition
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

/** MQTT transport (TLS) for end-to-end encrypted Sovereign Comms positions. */
class NetLink(
    private val brokerUrl: String,
    private val user: String,
    private val pass: String,
    private val netKey: ByteArray,
    private val myId: String,
    private val onPosition: (NetPosition) -> Unit,
    private val onStatus: (NetStatus, String?) -> Unit,
    /** Messages / voice: topic below the group ("o/<id>", "v/<id>", "vc/<id>") and raw payload. */
    private val onOther: (String, ByteArray) -> Unit = { _, _ -> },
) {
    enum class NetStatus(val label: String) { OFF("Off"), CONNECTING("Connecting…"), ONLINE("Online"), OFFLINE("Offline — retrying") }

    // ssl://host:port -> tcp:// + our own dual-stack TLS socket (Paho only tries the first DNS address)
    private val useOwnTls = brokerUrl.startsWith("ssl://")
    private val pahoUrl = when {
        useOwnTls -> "tcp://" + brokerUrl.removePrefix("ssl://")
        else -> brokerUrl
    }
    private val client = MqttAsyncClient(pahoUrl, "traillink-$myId-${System.currentTimeMillis() % 100000}", MemoryPersistence())
    private val filter = NetCrypto.subscribeFilter(netKey)
    @Volatile var online = false; private set

    fun start() {
        val o = MqttConnectOptions().apply {
            isCleanSession = true
            keepAliveInterval = 60
            connectionTimeout = 20
            isAutomaticReconnect = true
            maxReconnectDelay = 60_000
            socketFactory = when {
                useOwnTls -> DualStackTlsFactory(tls = true)
                brokerUrl.startsWith("tcp://") -> DualStackTlsFactory(tls = false)
                brokerUrl.startsWith("wss://") -> SSLSocketFactory.getDefault()
                else -> SocketFactory.getDefault()
            }
            if (user.isNotBlank()) { userName = user; password = pass.toCharArray() }
            // Last will: nothing (retained last position stays, so partners still see where we were).
        }
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                online = true
                onStatus(NetStatus.ONLINE, brokerUrl)
                val g = NetCrypto.groupTopic(netKey)
                runCatching { client.subscribe(arrayOf(filter, "$g/o/+", "$g/vc/+/+", "$g/v/+", "$g/p/+/+", "$g/c/$myId/+"), intArrayOf(1, 1, 1, 0, 1, 1)) }
            }
            override fun connectionLost(cause: Throwable?) { online = false; onStatus(NetStatus.OFFLINE, cause?.message) }
            override fun messageArrived(topic: String, message: MqttMessage) {
                if (topic.endsWith("/$myId") || topic.contains("/$myId-") || topic.contains("/vc/$myId/") || topic.contains("/p/$myId/")) return  // our own echo
                val g = NetCrypto.groupTopic(netKey) + "/"
                val rel = topic.removePrefix(g)
                if (rel.startsWith("c/$myId/")) { if (message.payload.isNotEmpty()) onOther(rel, message.payload); return }   // call signaling (2.0)
                if (rel.startsWith("o/") || rel.startsWith("v/") || rel.startsWith("vc/") || rel.startsWith("p/")) { if (message.payload.isNotEmpty()) onOther(rel, message.payload); return }
                if (message.payload.isEmpty()) return               // retained message cleared
                NetCrypto.open(netKey, message.payload)?.let(onPosition)
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        onStatus(NetStatus.CONNECTING, null)
        try {
            client.connect(o, null, object : IMqttActionListener {
                override fun onSuccess(t: IMqttToken?) {}
                override fun onFailure(t: IMqttToken?, e: Throwable?) {
                    onStatus(NetStatus.OFFLINE, e?.message)
                    // automatic reconnect only kicks in after a first successful connect; retry ourselves
                    Thread { Thread.sleep(15_000); if (!stopped) runCatching { client.connect(o, null, this) } }.start()
                }
            })
        } catch (e: Exception) { onStatus(NetStatus.OFFLINE, e.message) }
    }

    @Volatile private var stopped = false

    /** Publishes a position as the retained last message on this sender's (or forwarded node's) topic. */
    fun publish(p: NetPosition): Boolean {
        if (!online) return false
        val sub = if (p.src == NetPosition.SRC_LORA && p.node != null) "$myId-%08x".format(p.node) else myId
        return try {
            client.publish(NetCrypto.groupTopic(netKey) + "/" + sub, MqttMessage(NetCrypto.seal(netKey, p)).apply { qos = 1; isRetained = true })
            true
        } catch (e: Exception) { false }
    }

    /** Publishes raw bytes below the group topic, e.g. sub = "o/<myId>". */
    fun publishRaw(sub: String, bytes: ByteArray, qos: Int, retained: Boolean): Boolean {
        if (!online) return false
        return try { client.publish(NetCrypto.groupTopic(netKey) + "/" + sub, MqttMessage(bytes).apply { this.qos = qos; isRetained = retained }); true }
        catch (e: Exception) { false }
    }

    /** Removes our retained position from the broker (used when sharing is turned off). */
    fun clearRetained(extraSubs: Collection<String> = emptyList()) {
        if (!online) return
        (listOf(myId, "o/$myId") + (0 until com.victor.traillink.core.VoiceWire.SLOTS).map { "vc/$myId/$it" } + extraSubs).forEach { s ->
            runCatching { client.publish(NetCrypto.groupTopic(netKey) + "/" + s, ByteArray(0), 1, true).waitForCompletion(5000) }
        }
    }

    fun stop() {
        stopped = true
        online = false
        runCatching { client.disconnect(2000).waitForCompletion(2500) }
        runCatching { client.close(true) }
    }
}
