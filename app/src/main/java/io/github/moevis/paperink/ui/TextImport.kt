package io.github.moevis.paperink.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.moevis.paperink.file.IMPORT_MIME_TYPES
import io.github.moevis.paperink.file.MAX_IMPORT_BYTES
import io.github.moevis.paperink.file.decodeText
import io.github.moevis.paperink.file.displayName
import io.github.moevis.paperink.file.readCapped

/** 导入文件的结果。 */
internal sealed interface ImportResult {

    /** 读到了。[name] 是文件名，问不到就是 null。 */
    data class Ok(val text: String, val name: String?) : ImportResult

    /** 打不开，或读出来一个字节都没有。 */
    data object ReadFailed : ImportResult

    /** 超过大小上限。 */
    data object TooBig : ImportResult

    /** 读到了，但全是空白。 */
    data object Empty : ImportResult
}

/**
 * 打开系统文件选择器读一个 .txt/.md。
 *
 * 一个入口吃两种格式——它们本质是同一种东西，分两个按钮只会让人先想「我这是哪种」。
 * 读文件的脏活全在这里，调用方只拿到一个 [ImportResult]。
 *
 * 返回的 lambda 是「打开选择器」，拿到结果后回调 [onResult]。
 */
@Composable
internal fun rememberTextImporter(onResult: (ImportResult) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(ctx, uri)
        val bytes = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { readCapped(it, MAX_IMPORT_BYTES) }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            onResult(ImportResult.ReadFailed)
            return@rememberLauncherForActivityResult
        }
        if (bytes.size > MAX_IMPORT_BYTES) {
            onResult(ImportResult.TooBig)
            return@rememberLauncherForActivityResult
        }
        val text = decodeText(bytes)
        if (text.isBlank()) {
            onResult(ImportResult.Empty)
            return@rememberLauncherForActivityResult
        }
        onResult(ImportResult.Ok(text, name))
    }
    return remember(launcher) { { launcher.launch(IMPORT_MIME_TYPES) } }
}
