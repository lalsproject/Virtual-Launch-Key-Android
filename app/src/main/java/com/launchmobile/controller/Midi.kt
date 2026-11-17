package com.launchmobile.controller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiDeviceService
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID

/** Helper untuk membentuk pesan MIDI dari kontrol / nilai. */
object Out {
    fun ch(c: Ctl): Int = if (c.channel in 1..16) c.channel else Cfg.keysCh

    fun cc(ch: Int, n: Int, v: Int) = MidiRouter.send(0xB0 or (ch - 1), n and 0x7F, v.coerceIn(0, 127))
    fun note(ch: Int, n: Int, v: Int) = MidiRouter.send(0x90 or (ch - 1), n and 0x7F, v.coerceIn(0, 127))
    fun pc(ch: Int, n: Int) = MidiRouter.send(0xC0 or (ch - 1), n and 0x7F)
    fun bend(ch: Int, v: Int) {
        val x = v.coerceIn(0, 16383)
        MidiRouter.send(0xE0 or (ch - 1), x and 0x7F, x shr 7)
    }

    fun press(c: Ctl, vel: Int = 127) {
        val ch = ch(c)
        when (c.type) {
            MsgType.NOTE -> note(ch, c.number, vel.coerceIn(1, 127))
            MsgType.CC -> cc(ch, c.number, c.maxV)
            MsgType.PC -> pc(ch, c.number)
            MsgType.REALTIME -> MidiRouter.send(c.number.coerceIn(248, 255))
            MsgType.PITCH -> bend(ch, 8192)
        }
    }

    fun release(c: Ctl) {
        val ch = ch(c)
        when (c.type) {
            MsgType.NOTE -> note(ch, c.number, 0) // Launchkey memakai Note On velocity 0 sebagai Note Off
            MsgType.CC -> cc(ch, c.number, c.minV)
            else -> {}
        }
    }

    fun value(c: Ctl, v: Int) {
        val ch = ch(c)
        when (c.type) {
            MsgType.CC -> cc(ch, c.number, v)
            MsgType.NOTE -> note(ch, c.number, v)
            MsgType.PC -> pc(ch, v)
            MsgType.PITCH -> bend(ch, v * 129)
            else -> {}
        }
    }

    /** All Sound Off + All Notes Off di semua channel, sustain off, pitch bend tengah. */
    fun panic() {
        Perf.allOff()
        for (ch in 1..16) {
            cc(ch, 123, 0)
            cc(ch, 120, 0)
            cc(ch, 64, 0)
        }
        bend(Cfg.keysCh, 8192)
    }
}

/** Mengirim pesan MIDI ke semua tujuan aktif: port sistem (USB/BT/app), perangkat virtual, BLE peripheral. */
object MidiRouter {
    private val thread = HandlerThread("midi-out").apply { start() }
    private val h = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var mm: MidiManager? = null

    private class Open(val name: String, val id: Int) {
        @Volatile var dev: MidiDevice? = null
        @Volatile var port: MidiInputPort? = null
    }

    private val open = ArrayList<Open>()

    @Volatile var monitor: ((String) -> Unit)? = null
    @Volatile var virtualOn = true
    @Volatile var bleOn = false
    var targets: Set<String> = emptySet()
    var onDevicesChanged: (() -> Unit)? = null

    val available: Boolean get() = mm != null

    fun start(ctx: Context) {
        if (mm != null) return
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) return
        val m = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        mm = m
        m.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                sync(); onDevicesChanged?.invoke()
            }

            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                sync(); onDevicesChanged?.invoke()
            }
        }, main)
    }

    /** Perangkat MIDI di sistem yang punya input port (bisa kita kirimi data). */
    @Suppress("DEPRECATION")
    fun devices(): List<MidiDeviceInfo> {
        val arr = mm?.devices ?: return emptyList()
        return arr.filter { it.inputPortCount > 0 }
    }

    fun nameOf(i: MidiDeviceInfo): String =
        i.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI ${i.id}"

    fun typeOf(i: MidiDeviceInfo): String = when (i.type) {
        MidiDeviceInfo.TYPE_USB -> "USB"
        MidiDeviceInfo.TYPE_VIRTUAL -> "App"
        MidiDeviceInfo.TYPE_BLUETOOTH -> "Bluetooth"
        else -> "?"
    }

    fun applyTargets(t: Set<String>) {
        targets = t
        sync()
    }

    fun sync() {
        val m = mm ?: return
        val infos = devices()
        synchronized(open) {
            val iter = open.iterator()
            while (iter.hasNext()) {
                val o = iter.next()
                if (o.name !in targets || infos.none { i -> i.id == o.id }) {
                    try { o.port?.close() } catch (e: Exception) { }
                    try { o.dev?.close() } catch (e: Exception) { }
                    iter.remove()
                }
            }
        }
        for (info in infos) {
            val n = nameOf(info)
            if (n !in targets) continue
            val already = synchronized(open) { open.any { it.id == info.id } }
            if (already) continue
            val entry = Open(n, info.id)
            synchronized(open) { open.add(entry) }
            m.openDevice(info, object : MidiManager.OnDeviceOpenedListener {
                override fun onDeviceOpened(device: MidiDevice?) {
                    if (device == null) {
                        synchronized(open) { open.remove(entry) }
                        return
                    }
                    entry.dev = device
                    entry.port = try { device.openInputPort(0) } catch (e: Exception) { null }
                }
            }, main)
        }
    }

    fun connectedCount(): Int = synchronized(open) { open.count { it.port != null } }

    fun send(s: Int, a: Int = -1, b: Int = -1) {
        val n = if (a < 0) 1 else if (b < 0) 2 else 3
        val buf = ByteArray(n)
        buf[0] = s.toByte()
        if (n > 1) buf[1] = a.toByte()
        if (n > 2) buf[2] = b.toByte()
        val mon = monitor
        if (mon != null) {
            val sb = StringBuilder()
            for (x in buf) sb.append(String.format("%02X ", x.toInt() and 0xFF))
            val text = sb.toString().trim()
            main.post { mon(text) }
        }
        h.post { dispatch(buf) }
    }

    private fun dispatch(buf: ByteArray) {
        synchronized(open) {
            for (o in open) {
                try { o.port?.send(buf, 0, buf.size) } catch (e: Exception) { }
            }
        }
        if (virtualOn) VirtualMidiService.push(buf)
        if (bleOn) BlePeripheral.push(buf)
    }
}

/** Perangkat MIDI virtual "Launch Mobile Controller" untuk aplikasi lain di HP yang sama. */
class VirtualMidiService : MidiDeviceService() {
    override fun onGetInputPortReceivers(): Array<MidiReceiver> = arrayOf()

    override fun onCreate() {
        super.onCreate()
        inst = this
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var inst: VirtualMidiService? = null

        fun push(buf: ByteArray) {
            val s = inst ?: return
            try {
                val r = s.outputPortReceivers
                if (r.isNotEmpty()) r[0].send(buf, 0, buf.size)
            } catch (e: Exception) {
            }
        }
    }
}

/** HP sebagai perangkat Bluetooth LE MIDI (peripheral) supaya bisa dipasangkan dengan PC / Mac / iOS / Android lain. */
@SuppressLint("MissingPermission")
object BlePeripheral {
    private val SERVICE: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    private val CHAR: UUID = UUID.fromString("7772E5DB-3868-4112-A1A9-F2669D106BF3")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var server: BluetoothGattServer? = null
    private var ch: BluetoothGattCharacteristic? = null
    private var adv: BluetoothLeAdvertiser? = null
    private val subs = ArrayList<BluetoothDevice>()
    @Volatile var running = false
    @Volatile var lastError: String? = null

    private val advCb = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            lastError = "Advertising BLE gagal (kode $errorCode)"
        }
    }

    @Synchronized private fun setSub(d: BluetoothDevice, on: Boolean) {
        if (on) {
            if (!subs.contains(d)) subs.add(d)
        } else {
            subs.remove(d)
        }
    }

    @Synchronized private fun subSnapshot(): List<BluetoothDevice> = ArrayList(subs)

    private val gattCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) setSub(device, false)
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            startAdv()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD) {
                val enable = value != null && value.isNotEmpty() && (value[0].toInt() and 1) == 1
                setSub(device, enable)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }
    }

    /** Mengembalikan pesan error, atau null bila berhasil. */
    fun start(ctx: Context): String? {
        if (running) return null
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return "Bluetooth tidak tersedia"
        val ad = bm.adapter ?: return "Bluetooth tidak tersedia"
        if (!ad.isEnabled) return "Bluetooth belum aktif"
        val advertiser = ad.bluetoothLeAdvertiser ?: return "HP ini tidak mendukung mode BLE peripheral"
        val srv = bm.openGattServer(ctx.applicationContext, gattCb) ?: return "Gagal membuka GATT server"
        val c = BluetoothGattCharacteristic(
            CHAR,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        c.addDescriptor(
            BluetoothGattDescriptor(
                CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(c)
        adv = advertiser
        server = srv
        ch = c
        lastError = null
        srv.addService(svc)
        running = true
        return null
    }

    private fun startAdv() {
        val a = adv ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE))
            .build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        try { a.startAdvertising(settings, data, scan, advCb) } catch (e: Exception) { lastError = e.message }
    }

    fun stop() {
        try { adv?.stopAdvertising(advCb) } catch (e: Exception) { }
        try { server?.close() } catch (e: Exception) { }
        server = null
        ch = null
        adv = null
        synchronized(this) { subs.clear() }
        running = false
    }

    fun subscriberCount(): Int = subSnapshot().size

    @Suppress("DEPRECATION")
    fun push(buf: ByteArray) {
        val c = ch ?: return
        val s = server ?: return
        val targets = subSnapshot()
        if (targets.isEmpty()) return
        val ts = (SystemClock.elapsedRealtime() and 0x1FFF).toInt()
        val pkt = ByteArray(buf.size + 2)
        pkt[0] = (0x80 or ((ts shr 7) and 0x3F)).toByte()
        pkt[1] = (0x80 or (ts and 0x7F)).toByte()
        System.arraycopy(buf, 0, pkt, 2, buf.size)
        c.value = pkt
        for (d in targets) {
            try { s.notifyCharacteristicChanged(d, c, false) } catch (e: Exception) { }
        }
    }
}
