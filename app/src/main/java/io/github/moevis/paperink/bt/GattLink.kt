package io.github.moevis.paperink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * BLE（GATT）这一条链路。
 *
 * 它只管三件事：把 GATT 连上并找到可写特征、把该开的通知打开、按 MTU 把一帧写出去。
 * 收到什么字节一律原样交给 [onBytes]——认协议、匹配回包都不是这一层的事。
 *
 * GATT 有两个坑，都在这里处理掉了：
 *  - 同一时刻只允许一个操作，所以开通知的 CCCD 必须一个一个写、一个一个等回执；
 *  - 一次写入最多 MTU-3 字节，超了固件直接丢，所以 MTU 要先协商再按它切包。
 */
@SuppressLint("MissingPermission")
internal class GattLink(private val ctx: Context) {

    companion object {
        private const val TAG = "Paperang"
    }

    /** 收到任何字节都往这儿送（会话层负责认协议、匹配回包）。 */
    var onBytes: ((ByteArray) -> Unit)? = null

    /** 协商到的 MTU。BLE 一次只能塞 MTU-3 字节，写帧时按它切包。 */
    var mtu: Int = 23
        private set

    private var gatt: BluetoothGatt? = null
    private var writeCh: BluetoothGattCharacteristic? = null
    private var notifyChars: List<BluetoothGattCharacteristic> = emptyList()
    private var connectGate: CompletableDeferred<Boolean>? = null
    private var writeGate: CompletableDeferred<Unit>? = null
    private var descGate: CompletableDeferred<Unit>? = null

    /** 可写特征挂在哪个服务下——协议探测时靠它先验猜协议。 */
    val writeServiceUuid get() = writeCh?.service?.uuid

    /** 连上并找到可写特征。GATT 走完流程要一两秒，超时按失败算。 */
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
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
        if (!ok) close()
        ok
    }

    /**
     * 写一帧。
     *
     * @param await true 表示等固件回一个写完成回执；false 表示写完就走
     *   （带 Write Without Response 特征时固件根本不会回执）。
     */
    suspend fun write(frame: ByteArray, await: Boolean) {
        val g = gatt ?: throw IOException("GATT 已断开")
        val ch = writeCh ?: throw IOException("找不到可写特征")
        val noResp = !await &&
            ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResp) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val gate = CompletableDeferred<Unit>()
        writeGate = gate
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, frame, type) == BluetoothStatusCodes.SUCCESS
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

    /** 断开并释放 GATT。 */
    fun close() {
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

    /** GATT 同一时刻只允许一个操作，所以 CCCD 必须一个一个写并等回执。 */
    private suspend fun enableNotifyAll() {
        val g = gatt ?: return
        for (c in notifyChars) {
            val gate = CompletableDeferred<Unit>()
            descGate = gate
            try {
                g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(PaperangProfile.CLIENT_CFG)
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
                close()
                connectGate?.complete(false)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else mtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var svc = g.getService(PaperangProfile.SVC)
            var ch: BluetoothGattCharacteristic? = null
            if (svc != null) {
                ch = svc.getCharacteristic(PaperangProfile.CH_WRITE) ?:
                    svc.getCharacteristic(PaperangProfile.CH_WRITE_ALT)
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
                    ch = svc.characteristics.firstOrNull { it.uuid == PaperangProfile.FF02 }
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
            onBytes?.invoke(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value ?: return
            onBytes?.invoke(v)
        }
    }
}
