package io.github.moevis.paperink.file

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.atomic.AtomicLong

/**
 * 从外部进来的一份东西：分享来的文字，或分享/用文件管理器打开的文件。
 *
 * 公开是因为它出现在 [RootScreen][io.github.moevis.paperink.ui.RootScreen] 的签名里——
 * internal 的类型不能挂到 public 函数上。
 *
 * [id] 每次构造自动加一，界面靠它分辨「又是一份新的」——同一个文件被分享两次，
 * 内容一样，但用户期待编辑器重新灌一遍。
 */
sealed interface Incoming {
    val id: Long

    /** 读到了正文。[name] 是文件名，问不到就是 null。 */
    class Text(val text: String, val name: String?) : Incoming {
        override val id: Long = SEQ.incrementAndGet()
    }

    /** 外面确实送了文件过来，但打不开（没给读权限、网盘占位文件、路径读不到）。 */
    class Failed : Incoming {
        override val id: Long = SEQ.incrementAndGet()
    }

}

private val SEQ = AtomicLong(1)

/**
 * 把启动 Intent 翻成一份 [Incoming]；不是「送内容进来」的 Intent 就返回 null。
 *
 * 认三种：
 * - `ACTION_SEND` + `EXTRA_STREAM`（文件管理器/浏览器分享一个 .md/.txt）
 * - `ACTION_SEND` + `EXTRA_TEXT`（别处选中一段文字分享过来）
 * - `ACTION_VIEW` + `data`（双击文件、或用「打开方式」）
 *
 * 文件优先于文字：分享面板里有时两个都带，文件才是原件。
 */
internal fun readIncoming(ctx: Context, intent: Intent?): Incoming? {
    if (intent == null) return null
    val uri = when (intent.action) {
        Intent.ACTION_SEND -> streamOf(intent)
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }
    if (uri != null) return readFile(ctx, uri)
    if (intent.action != Intent.ACTION_SEND) return null
    val t = intent.getStringExtra(Intent.EXTRA_TEXT)
    return if (t.isNullOrBlank()) null else Incoming.Text(t, null)
}

@Suppress("DEPRECATION")
private fun streamOf(intent: Intent): Uri? =
    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

/**
 * 读一个 content:// 或 file://。
 *
 * 读不出来返回 [Incoming.Failed] 而不是抛——分享过来的东西五花八门，哪一种都不该让
 * App 起不来；但也不能装作什么都没发生，界面上要能说一句「读不到」。
 * 上限沿用导入那一套，超了同样算读不到。
 */
private fun readFile(ctx: Context, uri: Uri): Incoming {
    val bytes = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { readCapped(it, MAX_IMPORT_BYTES) }
    }.getOrNull() ?: return Incoming.Failed()
    if (bytes.isEmpty() || bytes.size > MAX_IMPORT_BYTES) return Incoming.Failed()
    val text = decodeText(bytes)
    if (text.isBlank()) return Incoming.Failed()
    return Incoming.Text(text, displayName(ctx, uri))
}
