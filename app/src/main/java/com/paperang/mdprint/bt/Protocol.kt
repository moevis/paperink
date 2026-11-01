package com.paperang.mdprint.bt

/**
 * 喵喵机 / Paperang 的串口协议。**从官方 App 7.60.74 的还原 dex 逐字段核对**
 * （com.paperang.libprinter.utils.CommUtil.packData_02 / CRC32Util）。
 *
 * 一帧数据：
 *
 *   0x02 | cmd(1) | packetId(1) | len(2, LE) | payload(len) | crc32(4, LE) | 0x03
 *
 * crc32 是 zlib 风格 CRC-32，但**初值分两种**（这是先前一直不出纸的根因）：
 *
 *  - cmd == 24 (SET_CRC_KEY)：初值 = 0x35769521（standardKey）
 *  - 其余所有帧：初值 = 打印机当前保存的 crcKey，**默认 0**
 *
 * 而 SET_CRC_KEY 的 payload 不是密钥本身，而是 `期望crcKey XOR standardKey`。
 * 官方 App 下发的是 `0x35769521`（即希望 crcKey = 0），所以数据帧一律用初值 0。
 * python-paperang 用的 0x06B8EF59 只是同一套换算的另一种取值。
 */
object Protocol {

    const val STD_KEY = 0x35769521

    /** 数据帧默认 CRC 初值（对应打印机 crcKey = 0）。 */
    const val DATA_SEED = 0

    /** 单帧 payload 上限，超了要拆帧（协议原值）。 */
    const val MAX_PAYLOAD = 2016

    object Cmd {
        const val PRINT_DATA = 0
        const val PRINT_DATA_COMPRESS = 1
        const val GET_VERSION = 4
        const val GET_MODEL = 6
        const val GET_SN = 10
        const val GET_BAT_STATUS = 16
        const val SET_CRC_KEY = 24
        const val SET_HEAT_DENSITY = 25
        const val FEED_LINE = 26
        const val PRINT_TEST_PAGE = 27
        const val GET_HEAT_DENSITY = 28
        const val SET_PAPER_TYPE = 44
        const val DISCONNECT_BT = 47
    }

    private val table = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    /**
     * 等价于 Python `zlib.crc32(data, seed)`：zlib 对 seed 与结果各取反一次。
     * 不能用 java.util.zip.CRC32 —— 它没有 seed 参数。
     */
    fun crc32(data: ByteArray, from: Int = 0, len: Int = data.size, seed: Int = DATA_SEED): Int {
        var c = seed.inv()
        val end = from + len
        for (i in from until end) {
            c = table[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        return c.inv()
    }

    fun frame(cmd: Int, payload: ByteArray, packetId: Int = 0, seed: Int = DATA_SEED): ByteArray {
        val n = payload.size
        require(n <= MAX_PAYLOAD) { "payload $n > $MAX_PAYLOAD" }
        val out = ByteArray(n + 10)
        out[0] = 0x02
        out[1] = cmd.toByte()
        out[2] = (packetId and 0xFF).toByte()
        out[3] = (n and 0xFF).toByte()
        out[4] = ((n ushr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 5, n)
        // 只有 SET_CRC_KEY 这一帧用 standardKey 算 CRC，其余用 crcKey（默认 0）
        val crcSeed = if (cmd == Cmd.SET_CRC_KEY) STD_KEY else seed
        val crc = crc32(payload, seed = crcSeed)
        out[5 + n] = (crc and 0xFF).toByte()
        out[6 + n] = ((crc ushr 8) and 0xFF).toByte()
        out[7 + n] = ((crc ushr 16) and 0xFF).toByte()
        out[8 + n] = ((crc ushr 24) and 0xFF).toByte()
        out[9 + n] = 0x03
        return out
    }

    /** 复位 CRC 密钥：payload 是 `0 XOR standardKey`，下发后打印机 crcKey = 0。 */
    fun setCrcKeyFrame(): ByteArray = frame(Cmd.SET_CRC_KEY, u32le(STD_KEY))

    /**
     * A5 协议帧（label 机 / 新协议族）：`A5 | 01 | len(2,LE) | payload | crc32_standard(4,LE) | 5A`
     * payload 形如 `01 15 01 00 00`（group, cmd, type, len(2)）。
     */
    fun a5(payload: ByteArray): ByteArray {
        val n = payload.size
        val out = ByteArray(n + 9)
        out[0] = 0xA5.toByte()
        out[1] = 0x01
        out[2] = (n and 0xFF).toByte()
        out[3] = ((n ushr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 4, n)
        val crc = crc32(payload, seed = STD_KEY)
        out[4 + n] = (crc and 0xFF).toByte()
        out[5 + n] = ((crc ushr 8) and 0xFF).toByte()
        out[6 + n] = ((crc ushr 16) and 0xFF).toByte()
        out[7 + n] = ((crc ushr 24) and 0xFF).toByte()
        out[8 + n] = 0x5A
        return out
    }

    /**
     * 07 协议帧：`07 | cmd | pid | perLineBytes | len(2,LE) | crc32(4,LE,seed0) | Σ(1) | payload`
     * Σ 是前面所有字节的无符号和取低 8 位。
     */
    fun p07(cmd: Int, payload: ByteArray, packetId: Int, perLineBytes: Int): ByteArray {
        val n = payload.size
        val head = byteArrayOf(
            0x07, cmd.toByte(), (packetId and 0xFF).toByte(), (perLineBytes and 0xFF).toByte(),
            (n and 0xFF).toByte(), ((n ushr 8) and 0xFF).toByte(),
        )
        val crc = crc32(payload, seed = DATA_SEED)
        val crcB = u32le(crc)
        var sum = 0
        for (b in head) sum += b.toInt() and 0xFF
        for (b in crcB) sum += b.toInt() and 0xFF
        return head + crcB + byteArrayOf((sum and 0xFF).toByte()) + payload
    }

    fun u8(v: Int) = byteArrayOf((v and 0xFF).toByte())

    fun u32le(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )
    fun u16le(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    /**
     * A5 协议（P3 MAX 这一类新机用的就是它，实测 2026-10-31 跑通）。
     *
     * 帧：`A5 | 01 | len(2,LE) | payload | crc32_standard(4,LE) | 5A`
     * payload：`group | cmd | type | …`
     *   type=1 → 后面是 `len(2,LE) + value`
     *   type=2 → 后面是 `contentLen(2,LE)`，再是若干个 `id(1) | len(2,LE) | value`
     */
    object A5 {
        const val GROUP_PRINT = 5
        const val GROUP_SYS = 1

        const val CMD_PROTO_VERSION = 21      // 01 15
        const val CMD_MAX_LEN = 20            // 01 14 → 回包是单包最大字节数
        const val CMD_SHAKE = 23              // 01 17
        const val PRINT_START = 25            // 05 19
        const val PRINT_FINISH = 26           // 05 1A
        const val PRINT_DATA = 27             // 05 1B
        const val FEED_LINE = 22              // 05 16
        const val PAPER_LOCATION = 33         // 05 21
        const val SELF_TEST = 23              // 05 17
        const val SUPPORT_SIZE = 40           // 05 28 → 回包 JSON：TPSizeInfo 数组
        const val GET_DPI = 4                 // 05 04
        const val GET_PAPER_TYPE = 31         // 05 1F
        const val SET_PAPER_TYPE = 32         // 05 20 → 0 连续纸 / 1 标签纸 / 2 卡片纸
        const val DEV_INFO = 1                // 05 01 → 回包 JSON：TPInfo（含 PaperType、当前纸宽）

        /**
         * 一种纸型（`05 28` 回包里 TPSizeInfo 的一项）。
         * P3 MAX 实测三档：57mm→576 点/偏移 144，68mm→720 点/偏移 72，79mm→864 点/偏移 0。
         * hotSpot 就是该纸型下要渲染的横向点数，offsetStart 是这一行数据在热敏头上的起点。
         */
        data class PaperSize(val paperWidth: Int, val hotSpot: Int, val offsetStart: Int)

        private val SIZE_OBJ = Regex("\\{[^{}]*\"PaperWidth\"[^{}]*\\}")

        /** 解析 `{"TPSizeInfo":[{...},{...}]}`。故意手写而不用 org.json，好让本文件保持纯 Kotlin。 */
        fun parseSizeInfo(json: String): List<PaperSize> {
            val out = ArrayList<PaperSize>()
            for (m in SIZE_OBJ.findAll(json)) {
                val s = m.value
                fun num(k: String): Int? =
                    Regex("\"$k\"\\s*:\\s*(-?\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()
                val w = num("PaperWidth") ?: continue
                val h = num("HotSpot") ?: continue
                val o = num("OffsetStart") ?: 0
                if (h > 0) out.add(PaperSize(w, h, o))
            }
            return out
        }

        fun wrap(payload: ByteArray) = a5(payload)

        private fun simple(group: Int, cmd: Int) =
            a5(byteArrayOf(group.toByte(), cmd.toByte(), 1, 0, 0))

        fun protoVersion() = simple(GROUP_SYS, CMD_PROTO_VERSION)
        fun maxLen() = simple(GROUP_SYS, CMD_MAX_LEN)
        fun printStart() = simple(GROUP_PRINT, PRINT_START)
        fun printFinish() = simple(GROUP_PRINT, PRINT_FINISH)
        fun selfTest() = simple(GROUP_PRINT, SELF_TEST)
        fun supportSize() = simple(GROUP_PRINT, SUPPORT_SIZE)
        fun getDpi() = simple(GROUP_PRINT, GET_DPI)
        fun getPaperType() = simple(GROUP_PRINT, GET_PAPER_TYPE)
        fun deviceInfo() = simple(GROUP_PRINT, DEV_INFO)

        /** 告诉打印机装的是哪种纸。卡片纸（2）打完要做定位，不能靠走纸。 */
        fun setPaperType(type: Int): ByteArray =
            a5(byteArrayOf(GROUP_PRINT.toByte(), SET_PAPER_TYPE.toByte(), 1) + u16le(2) +
                byteArrayOf(type.toByte(), 0))

        /** `05 01` 回包里的 TPInfo 摘要。只取打印要用到的两项。 */
        data class TpInfo(val paperType: Int, val curPaperWidth: Int)

        /** 解析 `{"TPInfo":{…,"TPCurrentSize":79,"PaperType":0}}`。缺失的键按 0 处理。 */
        fun parseTpInfo(json: String): TpInfo? {
            if (!json.contains("TPInfo")) return null
            fun num(k: String): Int =
                Regex("\"$k\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return TpInfo(paperType = num("PaperType"), curPaperWidth = num("TPCurrentSize"))
        }

        fun feedLine(dots: Int): ByteArray {
            val v = u16le(dots.coerceIn(0, 0xFFFF))
            return a5(byteArrayOf(GROUP_PRINT.toByte(), FEED_LINE.toByte(), 1) + u16le(v.size) + v)
        }

        fun paperLocation(dots: Int): ByteArray {
            val v = u32le(dots)
            return a5(byteArrayOf(GROUP_PRINT.toByte(), PAPER_LOCATION.toByte(), 1) + u16le(v.size) + v)
        }

        /**
         * 一包打印数据。widthBytes = 每行字节数，offsetStart 一般 0。
         * 结构（对齐官方 packData_A5）：
         *   payload = `05 1B 01 | len(2) ` + `pid+1(2)` + `len(2)` +
         *             `precision(1)=1 | widthBytes(2) | offsetStart(2) | 0 | chunkLen(2) | chunk`
         */
        fun printData(chunk: ByteArray, widthBytes: Int, offsetStart: Int, packetIndex: Int): ByteArray {
            val body = ArrayList<Byte>(chunk.size + 8)
            body.add(1)                                   // PRINT_PRECISION_NORMAL
            u16le(widthBytes).forEach { body.add(it) }
            u16le(offsetStart).forEach { body.add(it) }
            body.add(0)
            u16le(chunk.size).forEach { body.add(it) }
            chunk.forEach { body.add(it) }
            val bodyArr = body.toByteArray()

            val inner = u16le(packetIndex + 1) + u16le(bodyArr.size) + bodyArr
            return a5(
                byteArrayOf(GROUP_PRINT.toByte(), PRINT_DATA.toByte(), 1) +
                    u16le(inner.size) + inner
            )
        }

        class Reply(val group: Int, val cmd: Int, val params: Map<Int, ByteArray>, val raw: ByteArray) {
            fun text(id: Int): String? = params[id]?.toString(Charsets.UTF_8)
            fun int(id: Int): Int? = params[id]?.let {
                if (it.size < 2) null
                else (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8)
            }
        }

        /** 解析 A5 回包（一条或多条）。 */
        fun parse(buf: ByteArray, len: Int): List<Reply> {
            val out = ArrayList<Reply>()
            var i = 0
            while (i + 5 <= len) {
                if (buf[i] != 0xA5.toByte() || buf[i + 1] != 0x01.toByte()) { i++; continue }
                val n = (buf[i + 2].toInt() and 0xFF) or ((buf[i + 3].toInt() and 0xFF) shl 8)
                val end = i + 4 + n + 4 + 1
                if (end > len || buf[end - 1] != 0x5A.toByte()) { i++; continue }
                val p = buf.copyOfRange(i + 4, i + 4 + n)
                out.add(parsePayload(p, buf.copyOfRange(i, end)))
                i = end
            }
            return out
        }

        private fun parsePayload(p: ByteArray, raw: ByteArray): Reply {
            val g = if (p.isNotEmpty()) p[0].toInt() and 0xFF else -1
            val c = if (p.size > 1) p[1].toInt() and 0xFF else -1
            val t = if (p.size > 2) p[2].toInt() and 0xFF else -1
            val params = LinkedHashMap<Int, ByteArray>()
            when (t) {
                1 -> if (p.size >= 5) {
                    val vl = (p[3].toInt() and 0xFF) or ((p[4].toInt() and 0xFF) shl 8)
                    if (5 + vl <= p.size) params[1] = p.copyOfRange(5, 5 + vl)
                }
                2 -> {
                    if (p.size >= 5) {
                        val cl = (p[3].toInt() and 0xFF) or ((p[4].toInt() and 0xFF) shl 8)
                        var k = 5
                        val stop = minOf(p.size, 5 + cl)
                        while (k + 3 <= stop) {
                            val id = p[k].toInt() and 0xFF
                            val vl = (p[k + 1].toInt() and 0xFF) or ((p[k + 2].toInt() and 0xFF) shl 8)
                            if (k + 3 + vl > p.size) break
                            params[id] = p.copyOfRange(k + 3, k + 3 + vl)
                            k += 3 + vl
                        }
                    }
                }
            }
            return Reply(g, c, params, raw)
        }
    }

    /** 把整幅位图按「每行 [bytesPerRow] 字节」切成若干帧。按行对齐切分，否则断行会撕裂。 */
    fun imageFrames(packed: ByteArray, bytesPerRow: Int, chunkBytes: Int): List<ByteArray> {
        val aligned = maxOf(bytesPerRow, (chunkBytes / bytesPerRow) * bytesPerRow)
        val frames = ArrayList<ByteArray>()
        var off = 0
        var id = 0
        while (off < packed.size) {
            val n = minOf(aligned, packed.size - off)
            val payload = packed.copyOfRange(off, off + n)
            frames.add(frame(Cmd.PRINT_DATA, payload, id))
            off += n
            id = (id + 1) and 0xFF
        }
        return frames
    }

    class Reply(val cmd: Int, val payload: ByteArray)

    /** 解析打印机回包（notify 通道）。可能粘包，逐帧拆。 */
    fun parseReplies(buf: ByteArray, len: Int): List<Reply> {
        val out = ArrayList<Reply>()
        var i = 0
        while (i + 5 <= len) {
            if (buf[i] != 0x02.toByte()) { i++; continue }
            val cmd = buf[i + 1].toInt() and 0xFF
            val plen = (buf[i + 3].toInt() and 0xFF) or ((buf[i + 4].toInt() and 0xFF) shl 8)
            if (i + 10 + plen > len) break
            if (buf[i + 9 + plen] != 0x03.toByte()) { i++; continue }
            out.add(Reply(cmd, buf.copyOfRange(i + 5, i + 5 + plen)))
            i += 10 + plen
        }
        return out
    }
}
