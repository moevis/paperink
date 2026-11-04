package io.github.moevis.paperink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        /** 打印机没开/不在附近时，`connect()` 会一直挂到系统超时。到点自己关 socket 把它踢醒。 */
        private const val CONNECT_TIMEOUT_MS = 12_000L
    }

    /** 收到的字节往这儿送。 */
    var onBytes: ((ByteArray) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: BluetoothSocket? = null

    /**
     * 连上串口。
     *
     * 这里是**阻塞**调用（RFCOMM 建链 + 读通道号），必须待在自己的 IO 线程上——
     * 调用方常常是主线程的协程，直接跑就会把界面冻住，5 秒后系统弹「无响应」。
     */
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        var sock: BluetoothSocket? = null
        try {
            val s = device.createRfcommSocketToServiceRecord(PaperangProfile.SPP)
            sock = s
            val watchdog = launch {
                delay(CONNECT_TIMEOUT_MS)
                // 关掉 socket 才能让阻塞中的 connect() 抛出来，否则它会一直等系统超时
                Log.w(TAG, "SPP 连接超过 ${CONNECT_TIMEOUT_MS}ms，放弃")
                runCatching { s.close() }
            }
            try {
                s.connect()
            } finally {
                watchdog.cancel()
            }
            socket = s
            scope.launch { readLoop(s) }
            true
        } catch (e: Exception) {
            runCatching { sock?.close() }
            Log.w(TAG, "SPP 连接失败: ${e.message}")
            false
        }
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
