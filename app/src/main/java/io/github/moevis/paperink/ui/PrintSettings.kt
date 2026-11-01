package io.github.moevis.paperink.ui

import io.github.moevis.paperink.Prefs
import io.github.moevis.paperink.render.PrintOptions

/**
 * 预览页那六个可调参数，收成一个值。
 *
 * 以前它们是六个独立的 State，从 [RootScreen] 一路手传到 [PrintPane] 再传到
 * [ParamBar]——光参数列表就十几行，加一格要改四个地方，还得记住每格的回调叫什么。
 * 现在只有一份 [PrintSettings]：界面往上抛「新的一份」，谁要读哪格自己读。
 *
 * 档位索引而不是实际数值：索引跟语言无关，重启后按索引恢复；
 * 真正的点数值在 [Presets] 里，改档位只改那一处。
 */
internal data class PrintSettings(
    val sizeIndex: Int = DEFAULT_SIZE,
    val widthIndex: Int = DEFAULT_WIDTH,
    val mathIndex: Int = DEFAULT_MATH,
    val lineIndex: Int = DEFAULT_LINE,
    val threshold: Int = DEFAULT_THRESHOLD,
    val dilate: Boolean = false,
) {

    /** 喂给排版器的那份参数。 */
    fun toOptions(): PrintOptions = PrintOptions(
        widthDots = Presets.widths[widthIndex],
        // 边距按纸宽等比缩放，宽纸不会显得贴边
        marginDots = Presets.widths[widthIndex] / 32,
        bodySize = Presets.bodySizes[sizeIndex],
        mathEm = Presets.mathEms[mathIndex],
        lineEm = Presets.lineEms[lineIndex],
        threshold = threshold,
        dilate = dilate,
    )

    companion object {
        const val DEFAULT_SIZE = 1
        const val DEFAULT_WIDTH = 2
        const val DEFAULT_MATH = 1
        const val DEFAULT_LINE = 1
        const val DEFAULT_THRESHOLD = 148
    }
}

/** 从持久化设置里读回来。 */
internal fun Prefs.printSettings(): PrintSettings = PrintSettings(
    sizeIndex = sizeIndex,
    widthIndex = widthIndex,
    mathIndex = mathIndex,
    lineIndex = lineIndex,
    threshold = threshold,
    dilate = dilate,
)

/** 写回去。改一格也要整份覆盖——反正只有六个数，省掉六条各自为政的写入。 */
internal fun Prefs.store(settings: PrintSettings) {
    sizeIndex = settings.sizeIndex
    widthIndex = settings.widthIndex
    mathIndex = settings.mathIndex
    lineIndex = settings.lineIndex
    threshold = settings.threshold
    dilate = settings.dilate
}
