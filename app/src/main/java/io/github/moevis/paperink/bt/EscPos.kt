package io.github.moevis.paperink.bt

/**
 * ESC/POS 指令集。
 *
 * 喵喵机 P3 / C1 这类 3~4 寸机型不走 P1/P2 的私有帧，而是标准 ESC/POS：
 * 文本按 GBK 直接发，位图用 GS v 0，查询用 DLE EOT。这里的字节序和
 * PrintRenderer 的打点顺序一致（MSB 为最左侧点）。
 */
object EscPos {

    /** 1B 40：初始化，清空缓冲区、恢复默认参数。不打印任何内容。 */
    fun init(): ByteArray = byteArrayOf(0x1B, 0x40)

    /**
     * 10 04 n：DLE EOT 实时状态查询。n=1 打印机状态，n=2 脱机状态，
     * n=3 错误状态，n=4 纸张状态。回 1 字节，全程不出纸。
     */
    fun statusQuery(n: Int): ByteArray = byteArrayOf(0x10, 0x04, n.toByte())

    /** 1D 72 n：GS r n，等同 DLE EOT 的另一种写法。 */
    fun gsStatus(n: Int): ByteArray = byteArrayOf(0x1D, 0x72, n.toByte())

    /** 1B 4A n：走纸 n 点。 */
    fun feedDots(dots: Int): ByteArray = byteArrayOf(0x1B, 0x4A, dots.coerceIn(0, 255).toByte())

    /** 1B 64 n：走纸 n 行。 */
    fun feedLines(lines: Int): ByteArray = byteArrayOf(0x1B, 0x64, lines.coerceIn(0, 255).toByte())

    /** 1B 61 n：0 左 / 1 居中 / 2 右。 */
    fun align(mode: Int): ByteArray = byteArrayOf(0x1B, 0x61, mode.toByte())

    /** 1B 21 n：字符倍高倍宽（n = 0x00 正常，0x10 倍高，0x20 倍宽，0x30 双倍）。 */
    fun textSize(n: Int): ByteArray = byteArrayOf(0x1B, 0x21, n.toByte())

    /** 1B 45 n：加粗。 */
    fun bold(on: Boolean): ByteArray = byteArrayOf(0x1B, 0x45, if (on) 1 else 0)

    /** 1B 33 n：行距（点）。 */
    fun lineSpacing(dots: Int): ByteArray = byteArrayOf(0x1B, 0x33, dots.coerceIn(0, 255).toByte())

    /** 1F 11 02 04：Phomemo/Marklife 一族每次 reset 后必发的初始化参数。 */
    fun vendorInit(mode: Int = 0x04): ByteArray =
        byteArrayOf(0x1F, 0x11, 0x02, mode.toByte())

    /** 1F 11 NN：厂商状态查询（电量 0x08 / 纸张 0x11 / 上盖 0x12）。 */
    fun vendorQuery(op: Int): ByteArray = byteArrayOf(0x1F, 0x11, op.toByte())

    /** 1D 28 45 ... 部分国产固件用它设浓度，参数 0~255。 */
    fun density(v: Int): ByteArray =
        byteArrayOf(0x1D, 0x28, 0x45, 0x02, 0x00, 0x05, v.coerceIn(0, 255).toByte())

    /** GBK 文本，末尾补换行。 */
    fun text(s: String): ByteArray =
        (s + "\n").toByteArray(charset("GBK"))

    /** 换行。 */
    fun newline(): ByteArray = byteArrayOf(0x0A)

    /**
     * 1D 76 30 m xL xH yL yH d...：GS v 0 光栅位图。
     * xL+xH*256 是每行字节数（P3 的 576 点 = 72 字节），yL+yH*256 是行数。
     */
    @JvmStatic
    fun rasterHeader(bytesPerRow: Int, rows: Int): ByteArray = byteArrayOf(
        0x1D, 0x76, 0x30, 0x00,
        (bytesPerRow and 0xFF).toByte(),
        (bytesPerRow shr 8 and 0xFF).toByte(),
        (rows and 0xFF).toByte(),
        (rows shr 8 and 0xFF).toByte(),
    )

    fun raster(packed: ByteArray, bytesPerRow: Int, rows: Int): ByteArray {
        val head = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            (bytesPerRow and 0xFF).toByte(),
            (bytesPerRow shr 8 and 0xFF).toByte(),
            (rows and 0xFF).toByte(),
            (rows shr 8 and 0xFF).toByte(),
        )
        val body = if (packed.size == bytesPerRow * rows) {
            packed
        } else {
            packed.copyOf(bytesPerRow * rows)
        }
        return head + body
    }

    /** 把一帧长指令切成小块，串口一次写太多会被固件丢字节。 */
    fun chunk(data: ByteArray, size: Int): List<ByteArray> {
        if (size <= 0 || data.size <= size) return listOf(data)
        val out = ArrayList<ByteArray>((data.size + size - 1) / size)
        var i = 0
        while (i < data.size) {
            val end = minOf(i + size, data.size)
            out.add(data.copyOfRange(i, end))
            i = end
        }
        return out
    }

    fun hex(b: ByteArray): String =
        b.joinToString(" ") { "%02X".format(it) }
}
