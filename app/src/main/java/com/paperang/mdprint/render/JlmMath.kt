package com.paperang.mdprint.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXIcon
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Insets
import kotlin.math.max

/**
 * 公式排版外包给 jlatexmath-android（JLaTeXMath 的 Android 移植，GPL v2）。
 *
 * 分式、根号、上下标、数学轴线、衬线斜体全部由引擎排。原来手写的那套盒模型
 * （MathBoxes / RuleSpan）在「等号两横等粗」「行内乘号对齐轴线」上反复踩坑，
 * 根因就是这些排版规则（轴线在字母 baseline 上方约 0.35em、分数线即轴线、
 * 运算符字形中心天然在轴线上）得自己重新推一遍；引擎里它们是现成的。
 *
 * 单位：TeXIconBuilder.setSize 收的是像素——PIXELS_PER_POINT 默认 1.0，
 * 且 JLatexMathAndroid.init() 只存 ApplicationContext、不碰屏幕密度。所以这里
 * 一律传「内部像素」= 最终打印点 × 超采样倍数，与正文 TextPaint.textSize 同坐标系。
 *
 * 等号两横：字形取自 jlm_cmr10.ttf（TeXSymbols.xml 虽然把 0x3D 指向 cmsy10，
 * 但 cmsy10 的 equal 是个 0.716em 的大轮廓，与实测墨迹 0.665em×2×0.04em 不符），
 * 两横各 0.04em 厚、中心在基线上方 0.153/0.347em、间距 0.194em。正文 26 点时公式
 * 41 点，横条名义 1.65 点，被 2× 超采样 + 双线性降采样 + 阈值 148 二值化后落在
 * 「1 点还是 2 点」的临界上，实测「中」「大」两档多数情况是两横各 2 点等粗，
 * 偶尔一粗一细（差 1 点）。试过把字号取成 0.194em 的整数倍，反而把本来等粗的
 * 「中」档改坏，已撤销——这一点的差别在 300dpi 热敏纸上肉眼几乎看不出，不值得
 * 为它引入字号取整的复杂度。
 */

/** 同一字号下同一公式只解析排一次；位图是分带重绘的，缓存能省掉重复建盒。 */
private val iconCache = HashMap<String, TeXIcon>()

/**
 * 行内公式里的 `\frac{a}{b}` 改成 `a/b`。
 *
 * TeX 的行内分式（text style）会把分子分母降到 script 号 = 0.7em。拉丁数字的
 * 大写字高本来只有 0.62em，两层缩完只剩 0.43em——正文 26 点时那位数字印出来
 * 不到 12 点，比同一行汉字（字面 0.86em ≈ 22 点）矮一半，整句像被戳了个洞。
 * 分式盒子本身还有 2.3em 高，会把这一行的行高从 24 点顶到 34 点，跟上下行
 * 不匀（`fractions.md` 倒数那四条实测 34 / 24 / 24 / 25）。
 *
 * 斜杠形式与正文同号同高、不撑行距，也是教材里行内分数的通行排法。块级公式
 * （`$$…$$`）不动，那里叠排本来就正确。真想要行内大分数可以显式写 `\dfrac`，
 * 这里不拦它——显式要求优先。
 *
 * 括号规则：分子/分母含运算符（`+ - = < > / \times \div \pm …`）时整体括起来，
 * 否则 `\frac{1+2}{3+4}` 会变成有歧义的 `1+2/3+4`。分子前一个字符是数字时补
 * 一个空格——`2\frac{1}{3}` 是带分数，粘成 `21/3` 就错了一个数量级。
 */
internal fun inlineFracAsSlash(tex: String): String {
    if (!tex.contains("\\frac") && !tex.contains("\\tfrac")) return tex
    val out = StringBuilder(tex.length + 8)
    var i = 0
    while (i < tex.length) {
        val c = tex[i]
        if (c != '\\') {
            out.append(c)
            i++
            continue
        }
        var j = i + 1
        while (j < tex.length && tex[j].isLetter()) j++
        val name = tex.substring(i + 1, j)
        if (name == "frac" || name == "tfrac") {
            var p = j
            while (p < tex.length && tex[p] == ' ') p++
            val num = readGroup(tex, p)
            if (num != null) {
                var q = num.second
                while (q < tex.length && tex[q] == ' ') q++
                val den = readGroup(tex, q)
                if (den != null) {
                    if (out.isNotEmpty() && out.last().isDigit()) out.append(' ')
                    out.append(paren(inlineFracAsSlash(num.first)))
                    out.append('/')
                    out.append(paren(inlineFracAsSlash(den.first)))
                    i = den.second
                    continue
                }
            }
        }
        out.append(tex, i, j)
        i = j
    }
    return out.toString()
}

/** 读一个 `{…}` 参数，返回（内容, 结束后下标）；不是分组返回 null。 */
private fun readGroup(s: String, start: Int): Pair<String, Int>? {
    if (start >= s.length || s[start] != '{') return null
    var depth = 0
    var k = start
    while (k < s.length) {
        when (s[k]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return s.substring(start + 1, k) to k + 1
            }
            // `\{` `\}` 是字面花括号，不参与配对
            '\\' -> k++
        }
        k++
    }
    return null
}

private val FRAC_PAREN = Regex("""\\(times|div|pm|mp|cdot|over|le|ge|ne|approx)""")

private fun paren(s: String): String {
    val need = s.isEmpty() ||
        s.any { it == '+' || it == '-' || it == '=' || it == '<' || it == '>' || it == '/' } ||
        FRAC_PAREN.containsMatchIn(s)
    return if (need) "($s)" else s
}

/**
 * 解析并排版一条公式。解析失败返回 null，调用方退回纯文本——别让一个错公式
 * 把整张纸打不出来。
 *
 * @param display true = 独立公式块（DISPLAY 样式）；false = 行内（TEXT 样式）
 */
internal fun mathIcon(tex: String, px: Float, display: Boolean): TeXIcon? {
    // 行内分式先改斜杠再排版，见 [inlineFracAsSlash]。
    val src = if (display) tex else inlineFracAsSlash(tex)
    val key = (if (display) "d" else "t") + px.toInt() + '\u0000' + src
    synchronized(iconCache) { iconCache[key] }?.let { return it }
    val icon = try {
        TeXFormula(src).createTeXIcon(
            if (display) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT,
            px,
        )
    } catch (e: Exception) {
        return null
    }
    // 引擎默认给四边各加 0.18×size 的内边距：独立公式块留着当呼吸空间；
    // 行内清掉，不然公式跟前后文字之间会多出约半个字的缝。
    if (!display) icon.setInsets(Insets(0, 0, 0, 0))
    synchronized(iconCache) { iconCache[key] = icon }
    return icon
}

/** 把公式画到 canvas 上，[x]/[y] 是图标左上角；canvas 当前的矩阵变换有效。 */
internal fun paintIcon(canvas: Canvas, icon: TeXIcon, x: Float, y: Float) {
    val g2 = AndroidGraphics2D()
    g2.setCanvas(canvas)
    // 公式无 \color 时 icon.isColored 为 false，paintIcon 不会解引用 component，传 null 安全
    icon.paintIcon(null, g2, x.toInt(), y.toInt())
}

/**
 * 行内公式专用：先画到小位图，做一次 3×3 膨胀再贴回去，让细笔画粗半个打印点。
 *
 * 为什么必须这么做：cmr10 的横竖笔只有 0.04em。行内公式与正文同号（26 点），
 * 上标再缩到 0.7×，笔宽约 1.5 内部像素，2× 超采样降采样后不足 0.8 打印点——
 * 低于热敏阈值一档的分辨极限，于是：
 *   · `2^{3+2}` 的加号只剩横杠，印出来就是 `2^{3-2}`（把加法读成减法）；
 *   · `a^{-n}` 的负号时有时无（同一份文档里两处同样的公式，一处有一处没有）。
 * 这两条都在真机上复现过。膨胀一圈后笔宽翻倍，横竖都稳定留下。
 *
 * 只对行内用：块级公式字号约 41 点，笔宽本来就够（加号在块级公式里从没丢过），
 * 再膨胀会显得比正文重一代。位图按图标缓存，分带重绘不会重复算。
 */
internal fun paintIconBold(canvas: Canvas, icon: TeXIcon, x: Float, y: Float) {
    val bmp = boldCache[icon] ?: buildBold(icon).also { boldCache[icon] = it }
    // 位图比图标四周各多 1px（膨胀要地方），贴回去时抵消掉
    canvas.drawBitmap(bmp, x - 1f, y - 1f, null)
}

private val boldCache = HashMap<TeXIcon, Bitmap>()

private fun buildBold(icon: TeXIcon): Bitmap {
    val w = max(1, icon.iconWidth)
    val h = max(1, icon.iconHeight)
    val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    paintIcon(Canvas(src), icon, 0f, 0f)
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    src.recycle()

    val ow = w + 2
    val oh = h + 2
    val dst = IntArray(ow * oh)
    for (y in 0 until h) {
        for (x in 0 until w) {
            // 边缘抗锯齿的淡边不算墨，不然膨胀后字形会糊成一团
            if ((px[y * w + x] ushr 24) and 0xFF < 96) continue
            val o = (y + 1) * ow + x + 1
            dst[o - ow - 1] = OPAQUE; dst[o - ow] = OPAQUE; dst[o - ow + 1] = OPAQUE
            dst[o - 1] = OPAQUE; dst[o] = OPAQUE; dst[o + 1] = OPAQUE
            dst[o + ow - 1] = OPAQUE; dst[o + ow] = OPAQUE; dst[o + ow + 1] = OPAQUE
        }
    }
    return Bitmap.createBitmap(dst, ow, oh, Bitmap.Config.ARGB_8888)
}

private const val OPAQUE = 0xFF000000.toInt()

/**
 * 公式基线（墨迹基线，字母坐着的那条线）距图标顶的像素。
 *
 * `getBaseLine()` 返回的是**比例**（基线像素 / 浮点 iconHeight），不是像素。分母是
 * `trueIconHeight + insets.top + insets.bottom`——直接用整数 `iconHeight` 会有最多
 * 1px 的截断误差（实测 90.72→90，带错 0.6px，行内公式基线会整体沉到文字下方）。
 * 另外度量公式里的 `+0.99` fudge 要减掉：paintIcon 实际按 `insets.top + height×size`
 * 摆盒，没有它。
 */
internal fun mathBaseY(icon: TeXIcon): Float {
    val ins = icon.insets
    return icon.baseLine * (icon.trueIconHeight + ins.top + ins.bottom) - 0.99f
}

/**
 * 行内公式：把公式基线对到文本基线上（TeX 行内公式的排法）。getSize 里把
 * FontMetrics 撑到图标高度，上标伸出文字行上方时行高才兜得住。
 */
internal class MathSpan(private val icon: TeXIcon) : ReplacementSpan() {

    private val base: Float get() = mathBaseY(icon)

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?,
    ): Int {
        if (fm != null) {
            val b = base
            val top = -b.toInt()                        // 基线上方，负值
            val bottom = (icon.iconHeight - b).toInt()
            if (top < fm.top) fm.top = top
            if (top < fm.ascent) fm.ascent = top
            if (bottom > fm.bottom) fm.bottom = bottom
            if (bottom > fm.descent) fm.descent = bottom
        }
        return icon.iconWidth
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        paintIconBold(canvas, icon, x, y - base)
    }
}
