package io.github.moevis.paperink.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 导入文件时认哪些 MIME。
 *
 * text 那一支覆盖 .txt 和认得 .md 的机型；`application/octet-stream` 是给那些把 .md
 * 报成「二进制未知」的文件管理器的——只留 text 的话，这类机器上 .md 会灰掉选不中。
 * 注意：Kotlin 的块注释可以嵌套，注释里别写带斜杠星号的通配符，会把后面整段代码吃掉。
 */
internal val IMPORT_MIME_TYPES = arrayOf(
    "text/*",
    "application/octet-stream",
    "application/x-markdown",
)

/** 4 MB 纯文本约两百万字，一页 100cm 也印不完；再大只会把排版卡死。 */
internal const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

/** 读到上限就停手——不能先 `readBytes()` 再判大小，那会儿内存已经吃进去了。 */
internal fun readCapped(input: InputStream, cap: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        out.write(chunk, 0, n)
        if (out.size() > cap) break
    }
    return out.toByteArray()
}

/**
 * 纯文本解码：BOM → 严格 UTF-8 → GB18030 三级。
 *
 * 中文 .txt 十有八九是 Windows 上存的 GBK，直接按 UTF-8 读会满屏「锟斤拷」；
 * 而 UTF-8 又必须先**严格**试一遍才知道该不该退——宽松解码永不报错，只会默默塞 U+FFFD。
 */
internal fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    val strict = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
    strict.getOrNull()?.let { return it }
    return runCatching { String(bytes, Charset.forName("GB18030")) }
        .getOrDefault(String(bytes, Charsets.UTF_8))
}

/** 问 SAF 要文件名。有的 provider 不给 DISPLAY_NAME，退回路径尾段。 */
internal fun displayName(ctx: Context, uri: Uri): String? =
    runCatching {
        ctx.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment
