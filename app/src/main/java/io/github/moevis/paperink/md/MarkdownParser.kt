package io.github.moevis.paperink.md

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

object MarkdownParser {

    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
            )
        )
        .build()

    fun parse(src: String): Doc {
        if (src.isBlank()) return Doc(emptyList())
        val root = parser.parse(src.replace("\r\n", "\n").replace('\r', '\n'))
        val out = ArrayList<Block>()
        var c = root.firstChild
        while (c != null) {
            blocks(c, out, 0)
            c = c.next
        }
        return Doc(out)
    }

    private fun blocks(n: Node, out: MutableList<Block>, depth: Int) {
        when (n) {
            is Heading -> out += Block.Heading(n.level.coerceIn(1, 6), inline(n))
            is Paragraph -> {
                // 整段就是一个 $$…$$：当独立公式，交给盒子排版（分式、根号才排得开）。
                // 注意必须用原始文本判断，inline() 已经把 $…$ 拆成 Inl.M 了。
                val t = rawText(n).trim()
                if (t.length > 4 && t.startsWith("$$") && t.endsWith("$$")) {
                    val tex = t.substring(2, t.length - 2).trim()
                    if (tex.isNotEmpty()) out += Block.Math(tex)
                } else {
                    val inl = inline(n)
                    if (inl.isNotEmpty()) out += Block.Para(inl)
                }
            }
            is BulletList -> eachItem(n, out, depth, ordered = false, start = 1)
            is OrderedList -> eachItem(n, out, depth, ordered = true, start = n.startNumber)
            is BlockQuote -> {
                var c = n.firstChild
                while (c != null) {
                    if (c is Paragraph) {
                        val inl = inline(c)
                        if (inl.isNotEmpty()) out += Block.Quote(inl)
                    } else {
                        blocks(c, out, depth)
                    }
                    c = c.next
                }
            }
            is FencedCodeBlock -> out += Block.Code(n.literal.trimEnd('\n'))
            is IndentedCodeBlock -> out += Block.Code(n.literal.trimEnd('\n'))
            is ThematicBreak -> out += Block.Rule
            is TableBlock -> table(n, out)
            is HtmlBlock -> {
                // HTML 块在热敏纸上没法渲染，退化成一行提示（空块就不占位）。
                val t = n.literal.lines().joinToString(" ") { it.trim() }.trim()
                if (t.isNotEmpty()) out += Block.Para(listOf(Inl.T(t)))
            }
            is ListItem -> {
                var c = n.firstChild
                while (c != null) {
                    blocks(c, out, depth)
                    c = c.next
                }
            }
            else -> {
                var c = n.firstChild
                while (c != null) {
                    blocks(c, out, depth)
                    c = c.next
                }
            }
        }
    }

    private fun eachItem(list: Node, out: MutableList<Block>, depth: Int, ordered: Boolean, start: Int) {
        var c = list.firstChild
        var idx = start
        while (c != null) {
            if (c is ListItem) {
                var first = true
                var cc = c.firstChild
                while (cc != null) {
                    if (cc is Paragraph && first) {
                        val inl = inline(cc)
                        val marker = if (ordered) "$idx." else "•"
                        if (inl.isNotEmpty()) out += Block.Item(inl, depth, marker)
                        first = false
                    } else if (cc is BulletList) {
                        eachItem(cc, out, depth + 1, ordered = false, start = 1)
                    } else if (cc is OrderedList) {
                        eachItem(cc, out, depth + 1, ordered = true, start = cc.startNumber)
                    } else if (cc is Paragraph) {
                        // 列表项内的第二段：缩进续行，不重复符号
                        val inl = inline(cc)
                        if (inl.isNotEmpty()) out += Block.Item(inl, depth, null)
                    } else {
                        blocks(cc, out, depth + 1)
                    }
                    cc = cc.next
                }
            }
            idx++
            c = c.next
        }
    }

    private fun table(n: Node, out: MutableList<Block>) {
        val head = ArrayList<List<Inl>>()
        val rows = ArrayList<List<List<Inl>>>()
        val aligns = ArrayList<Align>()
        var c = n.firstChild
        while (c != null) {
            if (c is TableHead) {
                var r = c.firstChild
                while (r != null) {
                    if (r is TableRow) cells(r, head, aligns)
                    r = r.next
                }
            } else if (c is org.commonmark.ext.gfm.tables.TableBody) {
                var r = c.firstChild
                while (r != null) {
                    if (r is TableRow) {
                        val row = ArrayList<List<Inl>>()
                        cells(r, row, null)
                        rows += row
                    }
                    r = r.next
                }
            }
            c = c.next
        }
        if (head.isEmpty() && rows.isEmpty()) return
        while (aligns.size < head.size) aligns += Align.LEFT
        out += Block.Table(head, aligns, rows)
    }

    private fun cells(row: TableRow, into: MutableList<List<Inl>>, aligns: MutableList<Align>?) {
        var c = row.firstChild
        while (c != null) {
            if (c is TableCell) {
                into += inline(c)
                aligns?.add(
                    when (c.alignment) {
                        TableCell.Alignment.CENTER -> Align.CENTER
                        TableCell.Alignment.RIGHT -> Align.RIGHT
                        else -> Align.LEFT
                    }
                )
            }
            c = c.next
        }
    }

    /**
     * 把一段文字里的 `$…$` 拆成行内公式。
     * 找不到配对的 `$` 就原样留着——价格、变量名这类单个美元符号不会被误当成公式。
     */
    private fun splitInlineMath(lit: String, out: MutableList<Inl>) {
        if (!lit.contains('$')) {
            if (lit.isNotEmpty()) out += Inl.T(lit)
            return
        }
        val sb = StringBuilder()
        var i = 0
        while (i < lit.length) {
            val c = lit[i]
            if (c == '$' && (i == 0 || lit[i - 1] != '\\')) {
                val end = lit.indexOf('$', i + 1)
                // 空公式（$$）和跨到段尾没闭合的都不当公式
                if (end > i + 1) {
                    if (sb.isNotEmpty()) {
                        out += Inl.T(sb.toString())
                        sb.clear()
                    }
                    out += Inl.M(lit.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        if (sb.isNotEmpty()) out += Inl.T(sb.toString())
    }

    /** 节点下的原始文字（不拆公式，用来判断整段是不是 $$…$$）。 */
    private fun rawText(n: Node): String {
        val sb = StringBuilder()
        var c = n.firstChild
        while (c != null) {
            when (c) {
                is Text -> sb.append(c.literal)
                is SoftLineBreak -> sb.append('\n')
                is HardLineBreak -> sb.append('\n')
                else -> sb.append(rawText(c))
            }
            c = c.next
        }
        return sb.toString()
    }

    private fun inline(n: Node): List<Inl> {
        val out = ArrayList<Inl>()
        var c = n.firstChild
        while (c != null) {
            appendInline(c, out)
            c = c.next
        }
        return out
    }

    private fun appendInline(n: Node, out: MutableList<Inl>) {
        when (n) {
            is Text -> splitInlineMath(n.literal, out)
            is StrongEmphasis -> out += Inl.B(inline(n))
            is Emphasis -> out += Inl.I(inline(n))
            is Strikethrough -> out += Inl.S(inline(n))
            is Code -> out += Inl.C(n.literal)
            is Link -> out += Inl.L(inline(n), n.destination)
            is SoftLineBreak -> out += Inl.T(" ")
            is HardLineBreak -> out += Inl.T("\n")
            is Image -> {
                val alt = if (n.firstChild != null) inline(n).plain() else ""
                out += Inl.T(if (alt.isBlank()) "[图]" else "[图: $alt]")
            }
            is HtmlInline -> Unit
            else -> {
                // 未知行内节点：把子节点摊平，别丢字
                var c = n.firstChild
                while (c != null) {
                    appendInline(c, out)
                    c = c.next
                }
            }
        }
    }
}
