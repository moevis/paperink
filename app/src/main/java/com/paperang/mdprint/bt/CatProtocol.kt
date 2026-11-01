package com.paperang.mdprint.bt

/**
 * 「猫猫机」系热敏协议（FF00/FF02/FF03 通道，GB01 / X6 / 喵喵机 P3 这一族）。
 *
 *   51 78 | cmd | 00 | lenLo | lenHi | payload | crc8 | FF
 *
 * crc8 = 多项式 0x07、初值 0、不反射、不异或输出，只算 payload。
 * 与 Paperang 老协议的关键差别：位图每行是一条独立命令，且**最低位在左**。
 */
object CatProtocol {

    object Cmd {
        const val GET_STATE = 0xA3
        const val GET_INFO = 0xA8
        const val UPDATE_DEVICE = 0xA9
        const val SET_QUALITY = 0xA4
        const val SET_SPEED = 0xBD
        const val SET_ENERGY = 0xAF
        const val APPLY_ENERGY = 0xBE
        const val LATTICE_START_END = 0xA6
        const val ROW_RAW = 0xA2
        const val ROW_RLE = 0xBF
        const val FEED = 0xA1
        const val RETRACT = 0xA0
    }

    val LATTICE_START = byteArrayOf(
        0xAA.toByte(), 0x55, 0x17, 0x38, 0x44, 0x5F, 0x5F, 0x5F, 0x44, 0x38, 0x2C
    )
    val LATTICE_END = byteArrayOf(
        0xAA.toByte(), 0x55, 0x17, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x17
    )

    private val table = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        c
    }

    fun crc8(data: ByteArray, from: Int = 0, len: Int = data.size): Int {
        var c = 0
        for (i in from until from + len) c = table[(c xor data[i].toInt()) and 0xFF]
        return c
    }

    fun frame(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val n = payload.size
        val out = ByteArray(n + 8)
        out[0] = 0x51
        out[1] = 0x78
        out[2] = cmd.toByte()
        out[3] = 0x00
        out[4] = (n and 0xFF).toByte()
        out[5] = ((n ushr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 6, n)
        out[6 + n] = crc8(payload).toByte()
        out[7 + n] = 0xFF.toByte()
        return out
    }

    private val REV = IntArray(256) { v ->
        var x = v
        var r = 0
        repeat(8) {
            r = (r shl 1) or (x and 1)
            x = x ushr 1
        }
        r
    }

    /** Paperang 的打包是 MSB 在左，猫猫机要反过来：整字节位序反转即可。 */
    fun reverseBits(msbRow: ByteArray, off: Int, len: Int, out: ByteArray = ByteArray(len)): ByteArray {
        for (i in 0 until len) out[i] = REV[msbRow[off + i].toInt() and 0xFF].toByte()
        return out
    }

    /** 一行原始位图的命令；顺带做位序转换。 */
    fun rowFrame(msbRow: ByteArray, off: Int, len: Int, scratch: ByteArray): ByteArray {
        reverseBits(msbRow, off, len, scratch)
        return frame(Cmd.ROW_RAW, scratch.copyOf(len))
    }

    /** 一整行压成 run-length（bit7 是颜色，低 7 位是 1..127 的长度），能短就短。 */
    fun rleRow(msbRow: ByteArray, off: Int, len: Int): ByteArray? {
        val dots = IntArray(len * 8)
        for (i in 0 until len) {
            val b = REV[msbRow[off + i].toInt() and 0xFF]
            for (k in 0 until 8) dots[i * 8 + k] = (b shr k) and 1
        }
        val out = ArrayList<Byte>()
        var i = 0
        while (i < dots.size) {
            val v = dots[i]
            var run = 0
            while (i + run < dots.size && dots[i + run] == v && run < 127) run++
            out.add(((if (v == 1) 0x80 else 0x00) or run).toByte())
            i += run
        }
        return if (out.size < len) out.toByteArray() else null
    }

    class Status(val raw: Int) {
        val noPaper get() = raw and 0x01 != 0
        val binOpen get() = raw and 0x02 != 0
        val tooHot get() = raw and 0x04 != 0
        val lowPower get() = raw and 0x08 != 0
        fun text(): String = buildString {
            if (noPaper) append("缺纸 ")
            if (binOpen) append("仓盖打开 ")
            if (tooHot) append("过热 ")
            if (lowPower) append("电量低 ")
            if (isEmpty()) append("正常")
        }.trim()
    }

    /** 认领一帧猫猫机回包：51 78 cmd 01 lenLo lenHi payload crc ff */
    fun parse(buf: ByteArray, len: Int): Pair<Int, ByteArray>? {
        var i = 0
        while (i + 8 <= len) {
            if (buf[i] != 0x51.toByte() || buf[i + 1] != 0x78.toByte()) {
                i++
                continue
            }
            val cmd = buf[i + 2].toInt() and 0xFF
            val n = (buf[i + 4].toInt() and 0xFF) or ((buf[i + 5].toInt() and 0xFF) shl 8)
            if (i + 8 + n > len) return null
            if (buf[i + 7 + n] != 0xFF.toByte()) {
                i++
                continue
            }
            return cmd to buf.copyOfRange(i + 6, i + 6 + n)
        }
        return null
    }
}
