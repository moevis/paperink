package io.github.moevis.paperink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 经典蓝牙（RFCOMM / SPP）链路：一条纯字节流，一次能写两千多字节。
 *
 * 老机型只有这条路能打印——BLE 那条只能读状态，出纸必须走串口。
 * 与 BLE 不同，这边没有帧头帧尾，写出去的就是打印机看到的字节。
 */
@SuppressLint("MissingPermission")
internal class SppLink {

    companion object {
        private const val TAG = "Paperang"
    }

    /** 收到的字节往这儿送。 */
    var onBytes: ((ByteArray) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: BluetoothSocket? = null

    suspend fun connect(device: BluetoothDevice): Boolean = try {
        val s = device.createRfcommSocketToServiceRecord(PaperangProfile.SPP)
        s.connect()
        socket = s
        scope.launch { readLoop(s) }
        true
    } catch (e: Exception) {
        Log.w(TAG, "SPP 连接失败: ${e.message}")
        false
    }

    /** 写一段字节。连接断了就抛。 */
    fun write(data: ByteArray) {
        val out = socket?.outputStream ?: throw IOException("连接已断开")
        out.write(data)
        out.flush()
    }

    fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
        socket = null
    }

    /** 串口没有「来一包」的边界，只能一直读，读到的都是打印机主动发的。 */
    private suspend fun readLoop(s: BluetoothSocket) {
        val buf = ByteArray(1024)
        try {
            val input = s.inputStream
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                onBytes?.invoke(buf.copyOf(n))
            }
        } catch (e: Exception) {
            Log.i(TAG, "串口读结束: ${e.message}")
        }
    }
}
