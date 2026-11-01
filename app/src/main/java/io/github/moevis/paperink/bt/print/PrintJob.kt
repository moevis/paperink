package io.github.moevis.paperink.bt.print

import android.util.Log
import io.github.moevis.paperink.bt.PrinterSession

/**
 * 一页要打的东西：排好版、按行打包过的位图，外加走纸与节流的两个参数。
 *
 * 收成一个对象是有原因的：以前四个打印流程各有一套位置参数，A5 不要 rows、
 * 猫猫机不要 feedDots，调用方得记着「这条流程该传哪几个」，多传一个少传一个
 * 都不报错——因为类型全是 Int。现在是同一份 [PrintJob]，谁用得上谁自己取。
 */
class PrintJob(
    /** 1 = 黑、MSB 在左，每行 [bytesPerRow] 字节。 */
    val packed: ByteArray,
    /** 行数（位图高度）。 */
    val rows: Int,
    val bytesPerRow: Int,
    /** 打完后额外走纸多少点。 */
    val feedDots: Int = DEFAULT_FEED_DOTS,
    /** 每包之间的兜底延时；0 表示只靠打印机应答做背压。 */
    val chunkDelayMs: Long = DEFAULT_CHUNK_DELAY_MS,
) {
    companion object {
        /** 打完之后留一段空白，纸才不会在文字正下方被撕断。真机上调出来的。 */
        const val DEFAULT_FEED_DOTS = 260

        /** 固件不应答时的兜底节流。 */
        const val DEFAULT_CHUNK_DELAY_MS = 10L
    }
}

/**
 * 一种协议的打印流程。
 *
 * 四条流程差别很大——有的按行发、有的按包发、有的要包一层 lattice 包裹、
 * 有的要先握手——但对调用方都是同一件事：给我一页位图，你把纸打出来。
 */
internal interface PrintProtocol {

    suspend fun print(
        session: PrinterSession,
        job: PrintJob,
        onProgress: (Float) -> Unit,
    )
}

/** 进度回调在四条流程里都是同一个除法，提出来免得各写一遍（写错也不报错）。 */
internal fun progressOf(done: Int, total: Int): Float =
    if (total <= 0) 1f else done.toFloat() / total.toFloat()

/** 四条流程共用的日志标签。 */
internal const val TAG_PRINT = "Paperang"

internal fun logPrint(msg: String) = Log.i(TAG_PRINT, msg)
