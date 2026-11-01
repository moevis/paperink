package io.github.moevis.paperink.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import io.github.moevis.paperink.md.Block
import io.github.moevis.paperink.md.Doc
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** 一条排好版的绘制指令：位置、高度、以及怎么画。 */
internal class Laid(val y: Int, val h: Int, val draw: (Canvas) -> Unit)

/**
 * 块级排版：把 Markdown 的块（标题/段落/列表/引用/代码/公式/表格）排成一条条绘制指令。
 *
 * 它不碰像素，只回答「谁画在哪儿、占多高」。真正画由 [PrintRenderer] 负责——
 * 那边要在 2 倍分辨率上排一遍再降采样，所以排版和绘制必须分开：
 * 排版结果是一串「画在某处的指令」，可以重复画到不同的离屏画布上。
 *
 * @param w 画布宽（超采样后的像素）
 * @param s 超采样倍数
 */
internal class LayoutBuilder(
    private val w: Int,
    internal val s: Int,
    private val opt: PrintOptions,
) {
    internal val m = opt.marginDots * s
    internal val contentW = w - 2 * m

    /**
     * 版式缩放系数。下面所有行距、块间距的常数都是按 203dpi 小票机（正文 16.5 点）
     * 调的；P3 MAX 是 300dpi，同样的点数只有 2/3 的物理高度，所以统一乘这个系数放大。
     * 字号不乘它——字号一律写成「正文的倍数」（见 [em]），这样调字号时整套比例不乱。
     */
    private val k = opt.bodySize / 16.5f

    /** 按正文倍率取字号，单位是打印点。 */
    internal fun em(mul: Float) = opt.bodySize * mul

    val items = ArrayList<Laid>()
    var height = 0
        private set

    /** 块间距：跟着版式缩放。 */
    internal fun space(dots: Float) {
        height += (dots * k * s).roundToInt()
    }

    /** 绝对间距：边距这类物理量，不参与版式缩放。 */
    private fun spaceAbs(dots: Float) {
        height += (dots * s).roundToInt()
    }

    internal fun add(h: Int, draw: (Canvas) -> Unit) {
        items += Laid(height, h, draw)
        height += h
    }

    internal fun paint(
        size: Float,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * s
        color = Color.BLACK
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            else -> Typeface.SANS_SERIF
        }
        isSubpixelText = true
    }

    internal fun layout(
        cs: CharSequence,
        p: TextPaint,
        width: Int,
        firstIndent: Int = 0,
        restIndent: Int = 0,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        lh: Float = 1.4f,
    ): StaticLayout {
        // 悬挂缩进只能靠 LeadingMarginSpan；Builder.setIndents 要逐行数组，这里用不上
        val text: CharSequence = if (firstIndent > 0 || restIndent > 0) {
            val sb = if (cs is android.text.Spannable) cs else SpannableStringBuilder(cs)
            sb.setSpan(
                LeadingMarginSpan.Standard(firstIndent, restIndent),
                0, sb.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb
        } else {
            cs
        }
        return StaticLayout.Builder
            .obtain(text, 0, text.length, p, max(1, width))
            .setIncludePad(false)
            // lh 是这一块自带的行高比（正文/标题/代码各不同），乘上用户的
            // 行距档位才是最终值。下限 1.02 是防手滑：行高比掉到 1 以下
            // 会让上下行叠在一起，字直接糊掉。
            .setLineSpacing(p.textSize * (max(1.02f, lh * opt.lineEm) - 1f), 1f)
            .setAlignment(align)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    private fun drawLayout(l: StaticLayout, x: Int, extra: Int = 0) {
        val yy = height
        add(l.height + extra) { c ->
            c.save()
            c.translate(x.toFloat(), yy.toFloat())
            l.draw(c)
            c.restore()
        }
    }

    fun build(doc: Doc) {
        spaceAbs(opt.marginDots.toFloat())
        for (blk in doc.blocks) {
            when (blk) {
                is Block.Heading -> heading(blk)
                is Block.Para -> {
                    val p = paint(opt.bodySize)
                    drawLayout(layout(inlineSpan(blk.inl, opt.bodySize, s), p, contentW), m)
                    space(7f)
                }
                is Block.Item -> item(blk)
                is Block.Quote -> quote(blk)
                is Block.Code -> code(blk)
                is Block.Math -> mathBlock(blk)
                is Block.Rule -> rule()
                is Block.Table -> table(blk)
            }
        }
        spaceAbs(opt.marginDots.toFloat())
    }

    private fun heading(h: Block.Heading) {
        // 倍率沿用 203dpi 时代的手调比例（26/22/19.5/17.5 对正文 16.5）
        val (size, before, after) = when (h.level) {
            1 -> Triple(em(1.58f), 6f, 8f)
            2 -> Triple(em(1.33f), 16f, 7f)
            3 -> Triple(em(1.18f), 14f, 5f)
            else -> Triple(em(1.06f), 12f, 4f)
        }
        space(before)
        val p = paint(size, bold = true)
        val l = layout(inlineSpan(h.inl, size, s, forceBold = true), p, contentW, lh = 1.3f)
        val yTop = height
        add(l.height) { c ->
            c.save()
            c.translate(m.toFloat(), yTop.toFloat())
            l.draw(c)
            c.restore()
        }
        // 只有一级标题画通栏线；二级及以下曾经画 1/3 宽的短线，长度跟标题
        // 文字对不上、每节都挂一条，纯噪声，已去掉。
        if (h.level == 1) {
            space(5f)
            val yy = height
            add(max(1, s)) { c ->
                val paint = Paint().apply { color = Color.BLACK; strokeWidth = 1.6f * k * s }
                c.drawLine(
                    m.toFloat(), yy.toFloat() + 0.8f * s,
                    (m + contentW).toFloat(), yy.toFloat() + 0.8f * s, paint
                )
            }
        }
        space(after)
    }

    private fun item(it: Block.Item) {
        val indent = m + it.depth * (15 * s)
        val p = paint(opt.bodySize)
        // 符号宽度按实际字宽算，否则「12.」这种两位数会被文字压住
        val markerW = if (it.marker == null) {
            10 * s
        } else {
            max(14 * s, (p.measureText(it.marker) + 4 * s).roundToInt())
        }
        val l = layout(
            inlineSpan(it.inl, opt.bodySize, s), p,
            contentW - (indent - m), markerW, markerW
        )
        val yy = height
        add(l.height) { c ->
            c.save()
            c.translate(indent.toFloat(), yy.toFloat())
            if (it.marker != null) {
                // 与布局首行基线对齐（includePad=false 时首行 top = baseline + fm.top）
                c.drawText(it.marker, 1f * s, -p.fontMetrics.top, p)
            }
            l.draw(c)
            c.restore()
        }
        space(3.5f)
    }

    private fun quote(q: Block.Quote) {
        val bar = max(2, 2 * s)
        val x = m + s
        val inner = contentW - 12 * s
        val p = paint(opt.bodySize)
        val l = layout(inlineSpan(q.inl, opt.bodySize, s), p, inner)
        val yTop = height
        add(l.height + 2) { c ->
            val pt = Paint().apply { color = Color.BLACK }
            c.drawRect(
                x.toFloat(), yTop.toFloat() + 1f,
                (x + bar).toFloat(), (yTop + l.height - 1f), pt
            )
            c.save()
            c.translate((x + 9 * s).toFloat(), yTop.toFloat())
            l.draw(c)
            c.restore()
        }
        space(7f)
    }

    private fun code(c: Block.Code) {
        space(4f)
        val size = em(0.84f)
        val p = paint(size, mono = true)
        val inner = contentW - 11 * s
        val text = SpannableStringBuilder(c.text.replace("\t", "    "))
        val l = layout(text, p, inner, lh = 1.3f)
        val yTop = height
        val bar = max(2, 2 * s)
        add(l.height + 4) { canv ->
            val pt = Paint().apply { color = Color.BLACK }
            canv.drawRect(
                m.toFloat(), yTop.toFloat() + 1f,
                (m + bar).toFloat(), (yTop + l.height - 1f), pt
            )
            canv.save()
            canv.translate((m + 9 * s).toFloat(), yTop.toFloat() + 1f)
            l.draw(canv)
            canv.restore()
        }
        space(7f)
    }

    /**
     * 独立公式：jlatexmath 排版（分式真叠、根号带横线、上下标按引擎的样式降级），
     * 轴线对齐由引擎的盒模型保证，不再手推。
     * 比正文略大一点，居中；宽度兜不住就整体等比缩，不让它出血。
     */
    private fun mathBlock(math: Block.Math) {
        space(8f)
        // 公式字号在预览页「公式」里三档可调（opt.mathEm），必须与 bodySize **线性**：
        // 曾经写成 em(1.05) * k * s，而 k = bodySize/16.5 本是笔画宽度单位，乘进来就成了
        // bodySize² —— 「大」档数学图标胀到正文 2 倍。
        // 另外：1.6546 是「大」档的老值，它让「中」正文（26 点）下还原成 86.04 内部像素，
        // 那一档等号两横等粗已在模拟器与真机逐像素验证过。改这三档数字后先看一眼等号：
        // 差 0.24 px 就够把相位翻掉，两横变一粗一细。
        val px = em(opt.mathEm) * s
        val icon = mathIcon(math.tex, px, display = true)
        if (icon == null) {
            // 解析失败别让整张纸打不出来：这段公式按纯文本排，至少看得见写了什么
            val p = paint(em(0.92f))
            drawLayout(layout(SpannableStringBuilder(math.tex), p, contentW), m)
            space(6f)
            return
        }
        val w = icon.iconWidth.toFloat()
        val h = icon.iconHeight.toFloat()
        val avail = contentW.toFloat()
        val sx = if (w > avail && w > 0f) avail / w else 1f
        val drawW = w * sx
        val x = m + (avail - drawW) / 2f
        val hh = ceil(h * sx).toInt()
        val yTop = height
        add(hh + 4) { c ->
            c.save()
            c.translate(x, yTop.toFloat() + 2f + (hh - h * sx) / 2f)
            if (sx < 1f) c.scale(sx, sx)
            paintIcon(c, icon, 0f, 0f)
            c.restore()
        }
        space(11f)
    }

    private fun rule() {
        space(6f)
        val yy = height
        val pt = Paint().apply { color = Color.BLACK; strokeWidth = 1.4f * k * s }
        add(max(1, s)) { c ->
            c.drawLine(m.toFloat(), yy.toFloat(), (w - m).toFloat(), yy.toFloat(), pt)
        }
        space(8f)
    }
}
