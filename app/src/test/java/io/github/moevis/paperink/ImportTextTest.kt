package io.github.moevis.paperink

import io.github.moevis.paperink.file.decodeText
import io.github.moevis.paperink.file.readCapped
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/**
 * 导入文件的解码钉子。
 *
 * 这个功能只在「从别的 app 拿文件」时才走到，平时编辑区看不见，出错了也不会崩——
 * 只会把满屏乱码灌进编辑器，用户还以为是文件坏了。所以规则得钉死：
 * BOM 优先、UTF-8 严格试、失败退 GB18030。
 *
 * 注意 JVM 与 Android 的字符集实现都覆盖 GB18030，这里测的是**选择逻辑**。
 */
class ImportTextTest {

    private val sample = "第一行：分数\n第二行：2/3\n第三行：根号 √2\n"

    @Test
    fun utf8Plain() {
        val bytes = sample.toByteArray(Charsets.UTF_8)
        assertEquals(sample, decodeText(bytes))
    }

    @Test
    fun asciiOnly() {
        val s = "# Title\n\n- a\n- b\n"
        assertEquals(s, decodeText(s.toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun gbkChineseFallsBack() {
        // 纯 GBK 编码的中文在严格 UTF-8 下必然解码失败，必须退到 GB18030
        val bytes = sample.toByteArray(Charset.forName("GB18030"))
        assertEquals(sample, decodeText(bytes))
    }

    @Test
    fun utf8BomIsStripped() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + sample.toByteArray(Charsets.UTF_8)
        assertEquals(sample, decodeText(bytes))
    }

    @Test
    fun utf16LeBom() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + "标题\n正文".toByteArray(Charsets.UTF_16LE)
        assertEquals("标题\n正文", decodeText(bytes))
    }

    @Test
    fun utf16BeBom() {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val bytes = bom + "标题\n正文".toByteArray(Charsets.UTF_16BE)
        assertEquals("标题\n正文", decodeText(bytes))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", decodeText(ByteArray(0)))
    }

    /** 超过上限就不该继续往内存里搬——这正是 readCapped 存在的理由。 */
    @Test
    fun readCappedStopsAtCap() {
        val big = ByteArray(300_000) { 'x'.code.toByte() }
        val out = readCapped(ByteArrayInputStream(big), 100_000)
        assertTrue("超过上限后应停止读取，实际 ${out.size}", out.size in 100_001..200_000)
    }

    @Test
    fun readCappedKeepsSmallFileWhole() {
        val small = "短文件".toByteArray(Charsets.UTF_8)
        assertArrayEquals(small, readCapped(ByteArrayInputStream(small), 4 * 1024 * 1024))
    }
}
