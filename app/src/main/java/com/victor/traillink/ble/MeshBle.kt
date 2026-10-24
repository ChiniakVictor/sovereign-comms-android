package com.victor.traillink.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Minimal Meshtastic BLE client (see https://meshtastic.org/docs/development/device/client-api/).
 * All GATT operations are serialized; FromRadio is drained whenever FromNum notifies.
 */
@SuppressLint("MissingPermission")
class MeshBle(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: State, detail: String? = null)
        fun onFromRadio(bytes: ByteArray)
        fun onReady()        // services found, notifications on — send want_config next
    }
    enum class State { BONDING, CONNECTING, CONNECTED, DISCONNECTED }

    companion object {
        val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TORADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROMRADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROMNUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var fromNum: BluetoothGattCharacteristic? = null

    private val opLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    private val drainReq = Channel<Unit>(Channel.CONFLATED)
    private var drainJob: Job? = null
    @Volatile var closed = false; private set

    private val btManager = ctx.getSystemService(BluetoothManager::class.java)

    fun device(address: String): BluetoothDevice? = btManager?.adapter?.getRemoteDevice(address)

    suspend fun connect(address: String) {
        closed = false
        val dev = device(address) ?: throw IllegalStateException("Bluetooth is off")
        if (dev.bondState != BluetoothDevice.BOND_BONDED) {
            listener.onState(State.BONDING)
            ensureBond(dev)
        }
        listener.onState(State.CONNECTING)
        val connected = CompletableDeferred<Unit>()
        connectWaiter = connected
        gatt = if (Build.VERSION.SDK_INT >= 23)
            dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        else dev.connectGatt(ctx, false, callback)
        withTimeout(20_000) { connected.await() }

        val g = gatt ?: throw IllegalStateException("GATT closed")
        runOp { g.requestMtu(512) }.also { /* MTU result ignored; firmware works with any MTU */ }
        runOp(15_000) { g.discoverServices() }
        val svc = g.getService(SERVICE) ?: throw IllegalStateException("Not a Meshtastic radio (service missing)")
        toRadio = svc.getCharacteristic(TORADIO)
        fromRadio = svc.getCharacteristic(FROMRADIO)
        fromNum = svc.getCharacteristic(FROMNUM)
        if (toRadio == null || fromRadio == null || fromNum == null) throw IllegalStateException("Meshtastic characteristics missing")

        g.setCharacteristicNotification(fromNum, true)
        fromNum!!.getDescriptor(CCCD)?.let { d -> runOp { writeDescriptorCompat(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) } }

        drainJob?.cancel()
        drainJob = scope.launch(Dispatchers.IO) {
            for (u in drainReq) {
                try { drainOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    android.util.Log.w("TrailLink", "drain: $e")
                }
            }
        }
        listener.onState(State.CONNECTED)
        listener.onReady()
    }

    /** Writes a ToRadio protobuf, then drains anything the radio queued in reply. */
    suspend fun send(bytes: ByteArray) {
        val g = gatt ?: throw IllegalStateException("Not connected")
        val ch = toRadio ?: throw IllegalStateException("Not connected")
        runOp { writeCharCompat(g, ch, bytes) }
        requestDrain()
    }

    fun requestDrain() { drainReq.trySend(Unit) }

    private suspend fun drainOnce() {
        val g = gatt ?: return
        val ch = fromRadio ?: return
        var n = 0
        while (!closed && n < 2000) {
            val data = runOp { g.readCharacteristic(ch) } as? ByteArray ?: break
            if (data.isEmpty()) break
            n++
            try { listener.onFromRadio(data) } catch (e: Exception) { android.util.Log.w("TrailLink", "fromRadio handler: $e") }
        }
    }

    fun close() {
        closed = true
        drainJob?.cancel()
        pending?.completeExceptionally(CancellationException("closed"))
        connectWaiter?.completeExceptionally(CancellationException("closed"))
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    // ---- bonding -----------------------------------------------------------------------------
    private suspend fun ensureBond(dev: BluetoothDevice) {
        val done = CompletableDeferred<Int>()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != dev.address) return
                val s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (s == BluetoothDevice.BOND_BONDED || s == BluetoothDevice.BOND_NONE) done.complete(s)
            }
        }
        ContextCompat.registerReceiver(ctx, rx, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        try {
            if (!dev.createBond()) return  // some firmware (NO_PIN mode) doesn't need bonding
            withTimeoutOrNull(90_000) { done.await() }
        } finally {
            try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}
        }
    }

    // ---- serialized GATT ops -----------------------------------------------------------------
    private var connectWaiter: CompletableDeferred<Unit>? = null

    private suspend fun runOp(timeoutMs: Long = 8_000, start: () -> Boolean): Any? = opLock.withLock {
        val d = CompletableDeferred<Any?>()
        pending = d
        if (!start()) { pending = null; throw IllegalStateException("GATT busy / op rejected") }
        try { withTimeout(timeoutMs) { d.await() } } finally { pending = null }
    }

    private fun complete(v: Any?) { pending?.complete(v) }
    private fun fail(msg: String) { pending?.completeExceptionally(IllegalStateException(msg)) }

    @Suppress("DEPRECATION")
    private fun writeCharCompat(g: BluetoothGatt, ch: BluetoothGattCharacteristic, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; ch.value = v; g.writeCharacteristic(ch) }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        else { d.value = v; g.writeDescriptor(d) }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectWaiter?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectWaiter?.completeExceptionally(IllegalStateException("Disconnected (status $status)"))
                pending?.completeExceptionally(IllegalStateException("Disconnected"))
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                if (!closed) listener.onState(State.DISCONNECTED, "status $status")
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = complete(mtu)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) =
            if (status == BluetoothGatt.GATT_SUCCESS) complete(true) else fail("discover failed $status")

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) complete(value) else fail("read failed $status")
        }
        @Deprecated("pre-33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            if (status == BluetoothGatt.GATT_SUCCESS) complete(ch.value ?: ByteArray(0)) else fail("read failed $status")
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            if (status == BluetoothGatt.GATT_SUCCESS) complete(true) else fail("write failed $status")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            if (status == BluetoothGatt.GATT_SUCCESS) complete(true) else fail("descriptor write failed $status")

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == FROMNUM) requestDrain()
        }
        @Deprecated("pre-33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            if (ch.uuid == FROMNUM) requestDrain()
        }
    }
}
