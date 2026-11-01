package com.paperang.mdprint.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

enum class Transport { BLE, SPP }

/** 打印链路用哪套协议，由 [PrinterClient] 探测决定。 */
enum class Kind { NONE, PAPERANG, CAT, ESCPOS }

data class Link(
    val connected: Boolean = false,
    val name: String? = null,
    val transport: Transport? = null,
    val battery: Int? = null,
)

data class Found(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int,
    val transport: Transport,
)


/**
 * 喵喵机连接器。两条通道共用 Protocol 的帧格式，只是 MTU / 分片大小不同：
 *  - BLE：GATT 透传（49535343-…），写入按 MTU 分片，且必须按 48 字节对齐（不断行）。
 *  - 经典蓝牙：RFCOMM/SPP，直接写字节流，可以一次 2016 字节。
 */
@SuppressLint("MissingPermission")
class PrinterClient(private val ctx: Context) {

    companion object {
        private const val TAG = "Paperang"

        /** 猫猫机单行字节数（384 点 / 8），老机型固定这个宽度。 */
        const val ROW_BYTES = 48

        /** 猫猫机加热能量，官方默认值。 */
        private const val CAT_ENERGY = 7500

        /** 问不到单包上限时按官方 P3 MAX 的值来。 */
        private const val FALLBACK_MAX_LEN = 1944
        val SVC: UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
        val CH_WRITE: UUID = UUID.fromString("49535343-6daa-4d02-abf6-19569aca69fe")
        val CH_WRITE_ALT: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
        val CH_NOTIFY: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
        val FF00: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val FF02: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val CLIENT_CFG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        fun looksLikePrinter(name: String?): Boolean {
            val n = name?.lowercase() ?: return false
            return n.contains("paperang") || n.contains("miaomiao") || n.contains("喵喵") ||
                n.contains("printer") || n.contains("pp-") || n.startsWith("p1") || n.startsWith("p2")
        }
    }

    private val _link = MutableStateFlow(Link())
    val link: StateFlow<Link> = _link

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found

    val scanner: BluetoothAdapter?
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val enabled: Boolean get() = scanner?.isEnabled == true

    // ------------------------------------------------------------- scanning

    private var scanning = false
    private var showAll = false

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = try { dev.name } catch (e: SecurityException) { null } ?: result.scanRecord?.deviceName
            val label = name?.trim().orEmpty()
            if (!showAll && !looksLikePrinter(label) && !advertises(result)) return
            val shown = label.ifBlank { "未命名设备" }
            val item = Found(dev, shown, result.rssi, Transport.BLE)
            val list = _found.value.filterNot { it.device.address == dev.address } + item
            _found.value = list.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.w(TAG, "scan failed $errorCode")
        }
    }

    private fun advertises(r: ScanResult): Boolean {
        val rec = r.scanRecord ?: return false
        return rec.serviceUuids?.any { it.uuid == SVC } == true
    }

    fun startScan(all: Boolean = false) {
        showAll = all
        val adapter = scanner ?: return
        if (!adapter.isEnabled) return
        _found.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            adapter.bluetoothLeScanner?.startScan(null, settings, scanCb)
            scanning = true
        } catch (e: SecurityException) {
            Log.w(TAG, "no scan permission", e)
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (e: SecurityException) {
            // 权限被撤，忽略
        }
    }

    /** 按 MAC 直连（绕过 bondedDevices 的限制）。 */
    suspend fun connectAddress(addr: String, prefer: Transport = Transport.SPP): Boolean {
        val a = scanner ?: return false
        val d = try {
            a.getRemoteDevice(addr)
        } catch (e: Exception) {
            Log.w(TAG, "getRemoteDevice 失败 $addr", e)
            return false
        }
        return connect(d, prefer)
    }

    /** 已配对的喵喵机（系统配对记录里按名字找），用于开机自动重连。 */
    fun bondedPrinter(): Found? = try {
        scanner?.bondedDevices?.firstOrNull { d ->
            val n = d.name.orEmpty().lowercase()
            n.contains("paperang") || n.contains("miaomiao") || n.contains("喵喵")
        }?.let { Found(it, it.name?.trim().orEmpty(), 0, Transport.SPP) }
    } catch (e: SecurityException) {
        null
    }

    fun bondedDevices(): List<Found> = try {
        scanner?.bondedDevices.orEmpty().map {
            Found(it, it.name?.trim().orEmpty().ifBlank { it.address }, 0, Transport.SPP)
        }
    } catch (e: SecurityException) {
        emptyList()
    }

    // ------------------------------------------------------------ gatt link

    private var gatt: BluetoothGatt? = null
    private var writeCh: BluetoothGattCharacteristic? = null
    private var spp: BluetoothSocket? = null
    private var mtu = 23

    /** 最近一次连成的地址，由 App 层持久化。 */
    var lastAddress: String? = null

    /** 握手确认走 A5 帧族（P3 MAX 实测就是它），打印流程与 0x02 私有帧完全不同。 */
    @Volatile
    var a5Mode: Boolean = false
        private set

    /** A5 单包最大字节数，由打印机自报（P3 MAX 实测 1944）。 */
    @Volatile
    var devMaxLen: Int = 1944
        private set

    /** 打印机自报的固件协议版本，例如 01.03.08。 */
    @Volatile
    var fwVersion: String? = null
        private set

    /** 支持的纸型表（`05 28` 回包）。空表示机型没报，按偏移 0 处理。 */
    @Volatile
    var paperSizes: List<Protocol.A5.PaperSize> = emptyList()
        private set

    /** 打印机自报的 TPInfo（`05 01`）：装的是哪种纸、纸宽多少 mm。没答话就是 null。 */
    @Volatile
    var tpInfo: Protocol.A5.TpInfo? = null
        private set

    @Volatile
    private var paused = false

    /** A5 打印应答计数（收到一条 group=5 的回包就 +1），用于逐包背压。 */
    private val a5Ack = AtomicInteger(0)

    private var connectGate: CompletableDeferred<Boolean>? = null
    private var writeGate: CompletableDeferred<Unit>? = null
    private var replyGate: CompletableDeferred<ByteArray>? = null
    private var descGate: CompletableDeferred<Unit>? = null
    private var notifyChars: List<BluetoothGattCharacteristic> = emptyList()
    private var proto = Kind.NONE
    private val ioMutex = Mutex()

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                val ok = try {
                    g.requestMtu(517)
                } catch (e: SecurityException) {
                    false
                }
                if (!ok) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt()
                connectGate?.complete(false)
                _link.value = Link()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else mtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var svc = g.getService(SVC)
            var ch: BluetoothGattCharacteristic? = null
            if (svc != null) {
                ch = svc.getCharacteristic(CH_WRITE) ?: svc.getCharacteristic(CH_WRITE_ALT)
            }
            if (svc == null || ch == null) {
                // 新款（P3 MAX 等）用 ff00/ff02 这一族，服务名换了但写法一样
                svc = g.services.firstOrNull { s ->
                    s.characteristics.any {
                        it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    }
                }
                if (svc != null) {
                    ch = svc.characteristics.firstOrNull { it.uuid == FF02 }
                        ?: svc.characteristics.firstOrNull {
                            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        }
                }
            }
            // 记录要开通知的特征，统一在连接流程里串行开（GATT 一次只能一个操作）
            notifyChars = svc?.characteristics?.filter {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            }.orEmpty()
            for (s in g.services) {
                for (c in s.characteristics) {
                    Log.i(
                        TAG,
                        "gatt ${s.uuid} / ${c.uuid} props=0x${Integer.toHexString(c.properties)}"
                    )
                }
            }
            if (ch == null) {
                // 兜底：任何可写特征都试一遍，兼容换个壳的机器
                for (s in g.services) {
                    for (c in s.characteristics) {
                        val p = c.properties
                        if (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ) {
                            ch = c
                            Log.w(TAG, "用兜底写特征 ${s.uuid} / ${c.uuid}")
                            break
                        }
                    }
                    if (ch != null) break
                }
            }
            writeCh = ch
            connectGate?.complete(ch != null)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            d: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.i(TAG, "cccd ${d.characteristic.uuid} status=$status")
            descGate?.complete(Unit)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeGate?.complete(Unit)
        }

        // API 33+ 走新签名，老系统走旧签名，两个都实现
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            consume(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value ?: return
            consume(v)
        }
    }

    private fun consume(value: ByteArray) {
        Log.i(TAG, "rx " + value.joinToString(" ") { "%02X".format(it) })
        // 打印时的流控：打印机每收一包就回一条 05 1B 应答，靠它决定何时发下一包
        if (a5Mode && value.isNotEmpty() && value[0] == 0xA5.toByte()) {
            for (r in Protocol.A5.parse(value, value.size)) {
                if (r.group == Protocol.A5.GROUP_PRINT) a5Ack.incrementAndGet()
            }
        }
        replyGate?.complete(value)
        val cat = CatProtocol.parse(value, value.size)
        if (cat != null && cat.first == 0xAE) {
            paused = cat.second.firstOrNull()?.toInt() == 1
            Log.i(TAG, "cat flow paused=$paused")
            return
        }
        if (cat != null) {
            val st = CatProtocol.Status(cat.second.firstOrNull()?.toInt()?.and(0xFF) ?: 0)
            Log.i(TAG, "cat reply cmd=0x${Integer.toHexString(cat.first)} ${st.text()}")
        }
        val replies = Protocol.parseReplies(value, value.size)
        for (r in replies) {
            if (r.cmd == Protocol.Cmd.GET_BAT_STATUS && r.payload.isNotEmpty()) {
                val pct = r.payload[0].toInt() and 0xFF
                _link.value = _link.value.copy(battery = pct)
            }
        }
    }

    /** 探测器：先问猫猫机状态，再问 Paperang 电量，谁答话就是谁。 */
    private suspend fun detect(): Kind {
        val r1 = exchange(CatProtocol.frame(CatProtocol.Cmd.GET_STATE, byteArrayOf(0)), 1_500)
        if (r1 != null && r1.size >= 2 && r1[0] == 0x51.toByte() && r1[1] == 0x78.toByte()) {
            Log.i(TAG, "协议识别：猫猫机 (0x51 0x78)")
            exchange(CatProtocol.frame(CatProtocol.Cmd.GET_INFO, byteArrayOf(0)), 800)
            return Kind.CAT
        }
        val r2 = exchange(Protocol.frame(Protocol.Cmd.GET_BAT_STATUS, Protocol.u8(1)), 1_000)
        if (r2 != null && r2.isNotEmpty() && r2[0] == 0x02.toByte()) {
            Log.i(TAG, "协议识别：Paperang (0x02 … 0x03)")
            return Kind.PAPERANG
        }
        // 都不答话，就按服务特征先验猜一个
        return when (writeCh?.service?.uuid) {
            SVC -> Kind.PAPERANG
            FF00 -> Kind.ESCPOS
            else -> Kind.CAT
        }
    }

    suspend fun connect(device: BluetoothDevice, prefer: Transport? = null): Boolean =
        ioMutex.withLock {
            disconnectQuiet()
            val name = try { device.name } catch (e: SecurityException) { null } ?: device.address

            suspend fun switchedOn(t: Transport) {
                _link.value = Link(true, name, t, null)
                lastAddress = device.address
                if (t == Transport.BLE) {
                    proto = detect()
                } else {
                    proto = Kind.PAPERANG
                }
                /** 问一句 A5 版本：答话的就是 A5 机（P3 MAX 属于这一类）。 */
                suspend fun adoptA5(): Boolean {
                    if (!handshakeA5()) return false
                    proto = Kind.PAPERANG
                    a5Mode = true
                    devMaxLen = queryMaxLen()
                    readSupportSize()
                    readTpInfo()
                    Log.i(
                        TAG,
                        "A5 握手成功 fw=$fwVersion maxLen=$devMaxLen paper=$paperSizes tp=$tpInfo"
                    )
                    return true
                }

                if (proto == Kind.PAPERANG) {
                    if (!adoptA5()) {
                        // 老机型：复位 CRC 密钥（payload = 0 XOR standardKey），之后帧 CRC 初值为 0
                        runCatching { writeFrame(Protocol.setCrcKeyFrame()) }
                        delay(150)
                        runCatching {
                            writeFrame(Protocol.frame(Protocol.Cmd.GET_BAT_STATUS, Protocol.u8(1)))
                        }
                    }
                }

                // detect() 在谁都不答话时按「可写特征所属服务」猜协议，ff00 会被猜成
                // ESC/POS；而 P3 MAX 恰恰就用 ff00/ff02 这一族，于是整台 A5 机被当成
                // ESC/POS，打出去的一千多包光栅帧它一句不认（实测：走完进度条，一张
                // 纸没出）。所以猜完之后一律再问一句 A5，答话的以 A5 为准。
                if (proto != Kind.PAPERANG && adoptA5()) {
                    Log.w(TAG, "特征先验猜错，补测到 A5，已改按 A5 处理")
                }
            }

            // 指定串口就只认串口：这类机型只有 SPP 能打印，降级到 BLE 等于白连
            if (prefer == Transport.SPP) {
                repeat(3) { i ->
                    if (connectSpp(device)) {
                        switchedOn(Transport.SPP)
                        return@withLock true
                    }
                    if (i < 2) delay(900)
                }
                Log.w(TAG, "SPP 三次都失败，退回 BLE（只能读状态，不能打印）")
            }

            val order = listOf(Transport.BLE, Transport.SPP)
            for (t in order) {
                val ok = if (t == Transport.BLE) connectGatt(device) else connectSpp(device)
                if (ok) {
                    switchedOn(t)
                    return@withLock true
                }
            }
            _link.value = Link()
            false
        }

    private suspend fun connectGatt(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        val gate = CompletableDeferred<Boolean>()
        connectGate = gate
        val g = try {
            device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
        if (g == null) {
            connectGate = null
            return@withContext false
        }
        gatt = g
        val ok = withTimeoutOrNull(12_000) { gate.await() } ?: false
        connectGate = null
        if (ok) enableNotifyAll()
        if (!ok) closeGatt()
        ok
    }

    /** GATT 同一时刻只允许一个操作，所以 CCCD 必须一个一个写并等回执。 */
    private suspend fun enableNotifyAll() {
        val g = gatt ?: return
        for (c in notifyChars) {
            val gate = CompletableDeferred<Unit>()
            descGate = gate
            try {
                g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(CLIENT_CFG)
                if (d == null) {
                    Log.w(TAG, "无 CCCD: ${c.uuid}")
                    descGate = null
                    continue
                }
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(d)
                    }
                }
                if (accepted) withTimeoutOrNull(1_500) { gate.await() }
                Log.i(TAG, "notify on ${c.uuid} accepted=$accepted")
            } catch (e: Exception) {
                Log.w(TAG, "notify 开启失败 ${c.uuid}", e)
            } finally {
                descGate = null
            }
        }
    }

    private suspend fun connectSpp(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = device.createRfcommSocketToServiceRecord(SPP)
            socket.connect()
            spp = socket
            scope.launch { readLoop(socket) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "SPP 连接失败: ${e.message}")
            false
        }
    }

    private suspend fun readLoop(socket: BluetoothSocket) {
        val buf = ByteArray(1024)
        try {
            val input = socket.inputStream
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                consume(buf.copyOf(n))
            }
        } catch (e: Exception) {
            Log.i(TAG, "串口读结束: ${e.message}")
        }
    }

    fun disconnect() {
        disconnectQuiet()
        _link.value = Link()
    }

    private fun disconnectQuiet() {
        closeGatt()
        try {
            spp?.close()
        } catch (e: Exception) {
            // ignore
        }
        spp = null
    }

    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            // ignore
        }
        gatt = null
        writeCh = null
        writeGate?.cancel()
        writeGate = null
    }

    // ------------------------------------------------------------- printing

    /**
     * 发一整页位图。
     * @param packed PrintRenderer 打好的位图数据（1=黑），每行 [bytesPerRow] 字节
     * @param rows 行数
     */
    suspend fun printImage(
        packed: ByteArray,
        rows: Int,
        bytesPerRow: Int,
        feedDots: Int = 260,
        chunkDelayMs: Long = 10,
        onProgress: (Float) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (_link.value.transport == null) return@withContext Result.failure(IOException("未连接打印机"))
        try {
            when {
                proto == Kind.CAT -> printCat(packed, rows, bytesPerRow, feedDots, chunkDelayMs, onProgress)
                proto == Kind.ESCPOS -> printEscPosRaster(packed, rows, bytesPerRow, onProgress)
                a5Mode -> printA5(packed, bytesPerRow, feedDots, chunkDelayMs, onProgress)
                else -> printPaperang(packed, bytesPerRow, feedDots, chunkDelayMs, onProgress)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 老机型：0x02 私有帧，每包 5 行。 */
    private suspend fun printPaperang(
        packed: ByteArray,
        bytesPerRow: Int,
        feedDots: Int,
        chunkDelayMs: Long,
        onProgress: (Float) -> Unit,
    ) {
        val chunk = bytesPerRow * 5
        runCatching { writeFrame(Protocol.frame(Protocol.Cmd.SET_PAPER_TYPE, Protocol.u8(0))) }
        val frames = Protocol.imageFrames(packed, bytesPerRow, chunk)
        var sent = 0
        for (f in frames) {
            writeFrame(f)
            sent++
            onProgress(sent.toFloat() / (frames.size + 1))
            if (chunkDelayMs > 0) delay(chunkDelayMs)
        }
        writeFrame(
            Protocol.frame(Protocol.Cmd.FEED_LINE, Protocol.u16le(feedDots.coerceIn(0, 0xFFFF)))
        )
        onProgress(1f)
    }

    /**
     * A5 帧族打印（P3 MAX）。流程对齐官方 App：
     *   printStart(05 19) → N×(05 1B 带 pid/宽/偏移/数据) → printFinish(05 1A) → feed(05 16)。
     * 每包必须按行对齐，包大小取「打印机自报 maxLen − 2 行」的最大整数行。
     */
    private suspend fun printA5(
        packed: ByteArray,
        bytesPerRow: Int,
        feedDots: Int,
        chunkDelayMs: Long,
        onProgress: (Float) -> Unit,
    ) {
        val rowBytes = maxOf(1, bytesPerRow)
        // 单包上限取两家的较小值：固件自报的 maxLen（扣两行 + 帧头 CRC 余量），以及
        // 走 BLE 时 MTU 折出来的空间——writeCharacteristic 一次只能塞 MTU-3 字节，
        // 超了写不进去（帧头/CRC 约 20 字节，留 32 当余量）。
        val ble = _link.value.transport == Transport.BLE
        val mtuRoom = if (ble) mtu - 3 - 32 else Int.MAX_VALUE
        val room = minOf(devMaxLen - 2 * rowBytes - 32, mtuRoom).coerceAtLeast(rowBytes)
        val chunk = (room / rowBytes) * rowBytes
        val total = (packed.size + chunk - 1) / chunk
        // 57mm 纸在 864 点的热敏头上是居中装着的，数据得从第 144 点开始铺，否则整页左移
        val offset = offsetFor(rowBytes * 8)
        Log.i(TAG, "A5 打印 ${packed.size}B 每行${rowBytes}B 包$chunk 偏移$offset 共$total 包 ble=$ble mtu=$mtu")

        writeFrame(Protocol.A5.printStart(), await = false)
        delay(80)

        var off = 0
        var pid = 0
        while (off < packed.size) {
            val n = minOf(chunk, packed.size - off)
            val part = packed.copyOfRange(off, off + n)
            val before = a5Ack.get()
            writeFrame(Protocol.A5.printData(part, rowBytes, offset, pid), await = false)
            off += n
            pid++
            onProgress(pid.toFloat() / (total + 1).toFloat())
            // 逐包背压：等着打印机把这包收下的应答，长文档才不会把固件缓冲灌爆；
            // 万一这版固件不回应答，超时后按原延时继续，不让整单卡死
            val acked = withTimeoutOrNull(600) {
                while (a5Ack.get() == before) delay(4)
                true
            } == true
            if (!acked && chunkDelayMs > 0) delay(chunkDelayMs)
        }

        writeFrame(Protocol.A5.printFinish(), await = false)
        delay(150)
        if (feedDots > 0) {
            writeFrame(Protocol.A5.feedLine(feedDots.coerceIn(0, 0xFFFF)), await = false)
        }
        delay(200)
        onProgress(1f)
    }

    /**
     * ESC/POS 光栅打印（Phomemo / Marklife 一族，ff00/ff02 通道）。
     *
     * 要点全部来自 sgrankin/phomemo 的实测文档：
     *  - 必须 Write Without Response，否则长传输会中途卡死
     *  - 发送前先 1B 40，再 1F 11 02 04；两步之间留一点时间
     *  - 位图用 GS v 0，整幅高度一次发，不分 255 行块
     *  - 每包之间要限速，发太快打印头只打几行就停
     */
    private suspend fun printEscPosRaster(
        packed: ByteArray,
        rows: Int,
        bytesPerRow: Int,
        onProgress: (Float) -> Unit,
    ) {
        val chunk = minOf(182, maxOf(20, mtu - 3))

        writeRaw(EscPos.init())
        delay(80)
        writeRaw(EscPos.vendorInit(0x04))
        delay(80)

        writeRaw(EscPos.rasterHeader(bytesPerRow, rows))
        delay(40)

        val total = packed.size
        var off = 0
        while (off < total) {
            val n = minOf(chunk, total - off)
            writeRaw(packed.copyOfRange(off, off + n))
            off += n
            onProgress(off.toFloat() / total)
            // 30ms 左右是文档给的实测安全值；固定延时比赌 BLE 栈缓冲可靠
            delay(26)
        }

        delay(120)
        writeRaw(EscPos.feedLines(4))
        // 固件在最后一个字节后约 2 秒才走完纸，提前断开最后几行会不打
        delay(1_800)
        onProgress(1f)
    }

    /**
     * 猫猫机：一行一条命令，print 前后要发 lattice 包裹，否则纸不动。
     * 官方流程是 state → dpi → speed → energy → apply → update → start → rows → end → feed。
     */
    private suspend fun printCat(
        packed: ByteArray,
        rows: Int,
        bytesPerRow: Int,
        feedDots: Int,
        chunkDelayMs: Long,
        onProgress: (Float) -> Unit,
    ) {
        writeFrame(CatProtocol.frame(CatProtocol.Cmd.SET_QUALITY, byteArrayOf(0x32)))
        writeFrame(CatProtocol.frame(CatProtocol.Cmd.SET_SPEED, byteArrayOf(0x20)))
        writeFrame(CatProtocol.frame(CatProtocol.Cmd.SET_ENERGY, Protocol.u16le(CAT_ENERGY)))
        writeFrame(CatProtocol.frame(CatProtocol.Cmd.APPLY_ENERGY, byteArrayOf(0x01)))
        writeFrame(CatProtocol.frame(CatProtocol.Cmd.UPDATE_DEVICE, byteArrayOf(0x00)))
        writeFrame(CatProtocol.frame(CatProtocol.Cmd.LATTICE_START_END, CatProtocol.LATTICE_START))

        val scratch = ByteArray(bytesPerRow)
        for (r in 0 until rows) {
            val off = r * bytesPerRow
            val rle = CatProtocol.rleRow(packed, off, bytesPerRow)
            val f = if (rle != null) {
                CatProtocol.frame(CatProtocol.Cmd.ROW_RLE, rle)
            } else {
                CatProtocol.rowFrame(packed, off, bytesPerRow, scratch)
            }
            writeFrame(f, await = false)
            while (paused) delay(20)
            onProgress((r + 1).toFloat() / (rows + 2))
            if (chunkDelayMs > 0) delay(chunkDelayMs)
        }

        writeFrame(CatProtocol.frame(CatProtocol.Cmd.LATTICE_START_END, CatProtocol.LATTICE_END))
        writeFrame(
            CatProtocol.frame(CatProtocol.Cmd.FEED, Protocol.u16le(feedDots.coerceIn(0, 0xFFFF)))
        )
        onProgress(1f)
    }

    /** 不走帧格式的裸写：ESC/POS 是纯字节流，没有包头包尾。 */
    private suspend fun writeRaw(data: ByteArray) {
        for (c in EscPos.chunk(data, maxOf(20, mtu - 3))) {
            writeFrame(c, await = false)
            delay(2)
        }
    }

    /**
     * 发一帧、等一条回包（原始字节）。超时或写失败就返回 null。
     * 探协议、问版本、问单包上限、问纸型都走这一条路，别各自复制一套 gate。
     */
    private suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
        val gate = CompletableDeferred<ByteArray>()
        replyGate = gate
        // BLE 的往返比 SPP 慢一个数量级（实测同一句 A5 版本查询：SPP 约 30ms、
        // BLE 约 2s），照 SPP 的 1 秒档等必然错过回包、把 A5 机误判成别的协议。
        val wait = if (_link.value.transport == Transport.BLE) timeoutMs * 3 else timeoutMs
        return try {
            writeFrame(frame, await = false)
            withTimeoutOrNull(wait) { gate.await() }
        } catch (e: Exception) {
            null
        } finally {
            replyGate = null
        }
    }

    /** 用 A5 协议版本查询做握手：答话即说明是 A5 帧族。 */
    private suspend fun handshakeA5(): Boolean {
        val raw = exchange(Protocol.A5.protoVersion(), 1_200) ?: return false
        if (raw.isEmpty() || raw[0] != 0xA5.toByte()) return false
        val ver = Protocol.A5.parse(raw, raw.size)
            .firstOrNull { it.cmd == Protocol.A5.CMD_PROTO_VERSION } ?: return false
        fwVersion = ver.text(1)
        return true
    }

    /** 问打印机单包最大字节数（P3 MAX 回 1944）。 */
    private suspend fun queryMaxLen(): Int {
        val raw = exchange(Protocol.A5.maxLen(), 1_000) ?: return FALLBACK_MAX_LEN
        val n = Protocol.A5.parse(raw, raw.size)
            .firstOrNull { it.cmd == Protocol.A5.CMD_MAX_LEN }?.int(1)
        return n?.takeIf { it in 128..16384 } ?: FALLBACK_MAX_LEN
    }

    /**
     * 问支持的纸型。回包是一段 JSON：`{"TPSizeInfo":[{"PaperWidth":57,"HotSpot":576,"OffsetStart":144}, …]}`
     * 拿到后就知道每种纸该渲染多宽、数据在热敏头上从第几点开始铺。
     */
    private suspend fun readSupportSize() {
        val raw = exchange(Protocol.A5.supportSize(), 1_200)
        val json = raw?.let { Protocol.A5.parse(it, it.size) }
            ?.firstOrNull { it.cmd == Protocol.A5.SUPPORT_SIZE }?.text(1)
        if (json.isNullOrBlank()) {
            Log.w(TAG, "纸型查询无回包")
            return
        }
        val list = Protocol.A5.parseSizeInfo(json)
        if (list.isNotEmpty()) paperSizes = list
        Log.i(TAG, "纸型表 $json")
    }

    /** 给定渲染点数，返回该纸型下数据在热敏头上的起始偏移；查不到就 0。 */
    fun offsetFor(widthDots: Int): Int {
        val s = paperSizes
        if (s.isEmpty()) return 0
        return (s.minByOrNull { abs(it.hotSpot - widthDots) } ?: return 0).offsetStart
    }

    /**
     * 问设备信息（`05 01`）。回包是 JSON，里面 `PaperType` 说明装的是哪种纸
     * （官方逻辑里 2 = 卡片纸），`TPCurrentSize` 是卡扣读到的当前纸宽 mm。
     */
    private suspend fun readTpInfo() {
        val raw = exchange(Protocol.A5.deviceInfo(), 1_200)
        val json = raw?.let { Protocol.A5.parse(it, it.size) }
            ?.firstOrNull { it.cmd == Protocol.A5.DEV_INFO }?.text(1)
        if (json.isNullOrBlank()) {
            Log.w(TAG, "设备信息无回包")
            return
        }
        tpInfo = Protocol.A5.parseTpInfo(json)
        Log.i(TAG, "设备信息 $json")
    }

    private suspend fun writeFrame(frame: ByteArray, await: Boolean = true) {
        val t = _link.value.transport ?: throw IOException("未连接")
        if (t == Transport.SPP) {
            val out = spp?.outputStream ?: throw IOException("连接已断开")
            out.write(frame)
            out.flush()
            return
        }
        val g = gatt ?: throw IOException("GATT 已断开")
        val ch = writeCh ?: throw IOException("找不到可写特征")
        val noResp = !await &&
            ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val gate = CompletableDeferred<Unit>()
        writeGate = gate
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            val r = g.writeCharacteristic(ch, frame, type)
            r == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = type
                ch.value = frame
                g.writeCharacteristic(ch)
            }
        }
        if (!accepted) {
            writeGate = null
            Log.w(TAG, "写入被拒绝 len=${frame.size}")
            throw IOException("写入被拒绝")
        }
        Log.i(TAG, "tx ${frame.size}B cmd=${frame[1].toInt() and 0xFF}")
        // 部分固件不回 onCharacteristicWrite，超时就往下走，别整单卡死
        if (!noResp) withTimeoutOrNull(4_000) { gate.await() }
        writeGate = null
    }

    fun close() {
        stopScan()
        disconnect()
    }
}
