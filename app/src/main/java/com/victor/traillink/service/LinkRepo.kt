package com.victor.traillink.service

import com.google.protobuf.ByteString
import com.victor.traillink.core.MeshCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.ConfigProtos.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Conn(val label: String) {
    IDLE("Not connected"), BONDING("Pairing (enter PIN)…"), CONNECTING("Connecting…"),
    SYNCING("Reading radio…"), CONNECTED("Connected"), RECONNECTING("Reconnecting…"), FAILED("Connection failed")
}

data class RadioInfo(
    val address: String? = null,
    val name: String? = null,
    val myNodeNum: Long = 0,
    val firmware: String? = null,
    val hwModel: String? = null,
    val configs: Map<Int, Config> = emptyMap(),
    val channels: Map<Int, ChannelProtos.Channel> = emptyMap(),
    val longName: String? = null,
    val shortName: String? = null,
) {
    val lora get() = configs[Config.LORA_FIELD_NUMBER]?.lora
    val device get() = configs[Config.DEVICE_FIELD_NUMBER]?.device
    val bluetooth get() = configs[Config.BLUETOOTH_FIELD_NUMBER]?.bluetooth
    val primary get() = channels[0]
    val aeadSupported get() = MeshCodec.supportsAead(firmware)
}

data class LinkState(
    val conn: Conn = Conn.IDLE,
    val radio: RadioInfo = RadioInfo(),
    val error: String? = null,
    val serviceRunning: Boolean = false,
    val net: NetLink.NetStatus = NetLink.NetStatus.OFF,
    val netDetail: String? = null,
    val lastPublish: Long = 0,
    val forwarded: Int = 0,
    val locationOn: Boolean = true,   // system Location switch
    val lastFixAt: Long = 0,
    val msgTick: Int = 0,
    val talking: String? = null,     // who is talking right now (voice)
)

data class SetupProgress(val running: Boolean = false, val steps: List<String> = emptyList(), val done: Boolean = false, val error: String? = null)

/** Process-wide state shared by the foreground service and the UI. */
object LinkRepo {
    private val _state = MutableStateFlow(LinkState())
    val state: StateFlow<LinkState> get() = _state
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> get() = _log
    private val _setup = MutableStateFlow(SetupProgress())
    val setup: StateFlow<SetupProgress> get() = _setup

    @Volatile var sessionPasskey: ByteString? = null
    @Volatile var controller: Controller? = null

    interface Controller {
        fun applySetup(plan: MeshCodec.SetupPlan)
    }

    fun update(f: (LinkState) -> LinkState) = _state.update(f)
    fun radio(f: (RadioInfo) -> RadioInfo) = _state.update { it.copy(radio = f(it.radio)) }
    fun setup(f: (SetupProgress) -> SetupProgress) = _setup.update(f)

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    fun log(msg: String) {
        android.util.Log.i("TrailLink", msg)
        _log.update { (it + "${fmt.format(Date())}  $msg").takeLast(80) }
    }
}
