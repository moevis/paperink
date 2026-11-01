package io.github.moevis.paperink.render

import android.os.Build
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.graphics.Typeface
import io.github.moevis.paperink.md.Inl

/**
 * 行内内容 → 带样式的一段文字。
 *
 * Markdown 的行内标记（粗/斜/删/代码/链接/公式）在这里变成 Android 的 span，
 * 交给 StaticLayout 去断行。公式不是文字而是一张图，用 [MathSpan] 顶上去。
 *
 * @param scale 超采样倍数。公式图标要按同样的倍数放大，尺寸才对得上正文。
 */
internal fun inlineSpan(
    inl: List<Inl>,
    size: Float,
    scale: Int,
    forceBold: Boolean = false,
): CharSequence {
    val sb = SpannableStringBuilder()
    appendInline(sb, inl, size, scale, forceBold)
    return sb
}

private fun appendInline(
    sb: SpannableStringBuilder,
    inl: List<Inl>,
    size: Float,
    scale: Int,
    bold: Boolean,
) {
    for (i in inl) {
        val start = sb.length
        when (i) {
            is Inl.T -> sb.append(i.s)
            is Inl.B -> appendInline(sb, i.c, size, scale, true)
            is Inl.I -> {
                appendInline(sb, i.c, size, scale, bold)
                sb.setSpan(
                    StyleSpan(Typeface.ITALIC), start, sb.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            is Inl.S -> {
                appendInline(sb, i.c, size, scale, bold)
                sb.setSpan(
                    StrikethroughSpan(), start, sb.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            is Inl.C -> {
                sb.append(i.s)
                sb.setSpan(
                    monoSpan(), start, sb.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    RelativeSizeSpan(0.92f), start, sb.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            is Inl.L -> appendInline(sb, i.c, size, scale, bold)
            is Inl.M -> {
                // 公式排版外包给 jlatexmath：MathSpan 把公式基线对到文本
                // 基线（跟 TeX 的行内公式一致）；解析失败退回原文，至少看得见。
                val icon = mathIcon(i.tex, size * scale, display = false)
                if (icon == null) {
                    sb.append(i.tex)
                } else {
                    val s0 = sb.length
                    // 占位**不能**用空格。StaticLayout 会把行尾空格当可裁的
                    // 尾部空白，公式一旦正好落在折行点，那一格连同公式一起被
                    // 裁掉——打印稿上「……说法——$a^b = N$ 等价于」整条公式凭空
                    // 消失过，同一句话换个长度折行位置就正常，极难肉眼定位。
                    // 改用 TeX 原文占位：不是空白就不会被当作尾部空白裁掉，
                    // 而 ReplacementSpan 本身保证折行不会切进公式内部。
                    sb.append(i.tex)
                    sb.setSpan(
                        MathSpan(icon), s0, sb.length,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        if (bold) {
            sb.setSpan(
                StyleSpan(Typeface.BOLD), start, sb.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}

private fun monoSpan(): Any =
    if (Build.VERSION.SDK_INT >= 28) TypefaceSpan(Typeface.MONOSPACE)
    else @Suppress("DEPRECATION") TypefaceSpan("monospace")
