package com.paperang.mdprint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.paperang.mdprint.bt.Protocol

/**
 * 参考值由 python 算：zlib.crc32(bytes(n), seed)
 */
class ProtocolTest {

    /**
     * CRC 初值分两种，两种都得对：数据帧用 DATA_SEED（=0，对应打印机 crcKey=0），
     * SET_CRC_KEY 帧用 STD_KEY。早先只按 STD_KEY 写死了参考值，把 DATA_SEED 改成 0
     * 之后这条一直红着——红在测试里，不影响出纸，但会掩盖将来真正的 CRC 问题。
     */
    @Test
    fun crcMatchesZlibSeed() {
        assertEquals(0xF288B395.toInt(), Protocol.crc32(ByteArray(48)))
        assertEquals(0x811A86CF.toInt(), Protocol.crc32(ByteArray(48) { 1 }))
        assertEquals(0x2144DF1C.toInt(), Protocol.crc32(ByteArray(4)))

        assertEquals(0x61EB4FC9.toInt(), Protocol.crc32(ByteArray(48), seed = Protocol.STD_KEY))
        assertEquals(0x12797A93.toInt(), Protocol.crc32(ByteArray(48) { 1 }, seed = Protocol.STD_KEY))
        assertEquals(0xEDC4770F.toInt(), Protocol.crc32(ByteArray(4), seed = Protocol.STD_KEY))
    }

    @Test
    fun frameLayout() {
        // cmd=26 (FEED_LINE), payload=[210,0]
        val f = Protocol.frame(Protocol.Cmd.FEED_LINE, Protocol.u16le(210))
        assertEquals(12, f.size)
        assertEquals(0x02, f[0].toInt())
        assertEquals(26, f[1].toInt())
        assertEquals(0, f[2].toInt())
        assertEquals(2, f[3].toInt())
        assertEquals(0, f[4].toInt())
        assertEquals(210.toByte(), f[5])
        assertEquals(0.toByte(), f[6])
        assertEquals(0x03, f[11].toInt())
    }

    @Test
    fun imageChunkingKeepsRowAlignment() {
        // 3 行，每行 48 字节；chunk 给 500 → 应对齐到 480 = 10 行
        val packed = ByteArray(48 * 25)
        val frames = Protocol.imageFrames(packed, 48, 500)
        assertEquals(3, frames.size) // 480 + 480 + 240
        assertEquals(480 + 10, frames[0].size)
        assertEquals(240 + 10, frames[2].size)
    }

    @Test
    fun repliesParseStickyPackets() {
        val a = Protocol.frame(Protocol.Cmd.GET_BAT_STATUS, Protocol.u8(87))
        val b = Protocol.frame(Protocol.Cmd.SET_PAPER_TYPE, ByteArray(0))
        val buf = a + b
        val r = Protocol.parseReplies(buf, buf.size)
        assertEquals(2, r.size)
        assertEquals(87, r[0].payload[0].toInt())
        assertArrayEquals(ByteArray(0), r[1].payload)
    }
}
