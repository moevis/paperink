package com.paperang.mdprint.md

/** 渲染用的中间模型：与 Markdown 语法解耦，预览与打印两条渲染路径共用。 */
data class Doc(val blocks: List<Block>) {
    val isEmpty: Boolean get() = blocks.isEmpty()
}

sealed interface Block {
    data class Heading(val level: Int, val inl: List<Inl>) : Block
    data class Para(val inl: List<Inl>) : Block

    /** depth 从 0 起；marker 为 null 表示续行（不画项目符号）。 */
    data class Item(val inl: List<Inl>, val depth: Int, val marker: String?) : Block

    data class Quote(val inl: List<Inl>) : Block
    data class Code(val text: String) : Block

    /** 独立成行的公式（`$$…$$`），渲染时按分式/根号真排一遍。 */
    data class Math(val tex: String) : Block
    data object Rule : Block
    data class Table(
        val head: List<List<Inl>>,
        val aligns: List<Align>,
        val rows: List<List<List<Inl>>>,
    ) : Block
}

enum class Align { LEFT, CENTER, RIGHT }

sealed interface Inl {
    data class T(val s: String) : Inl
    data class B(val c: List<Inl>) : Inl
    data class I(val c: List<Inl>) : Inl
    data class S(val c: List<Inl>) : Inl
    data class C(val s: String) : Inl
    data class L(val c: List<Inl>, val href: String) : Inl

    /** 行内公式（`$…$`），上下标走字符样式，不占独立行。 */
    data class M(val tex: String) : Inl
}

/** 取纯文本（量宽度、算字数、无障碍朗读都用它）。 */
fun List<Inl>.plain(): String {
    val sb = StringBuilder()
    fun walk(l: List<Inl>) {
        for (i in l) when (i) {
            is Inl.T -> sb.append(i.s)
            is Inl.B -> walk(i.c)
            is Inl.I -> walk(i.c)
            is Inl.S -> walk(i.c)
            is Inl.C -> sb.append(i.s)
            is Inl.L -> walk(i.c)
            is Inl.M -> sb.append(MathTex.plain(MathTex.parse(i.tex)))
        }
    }
    walk(this)
    return sb.toString()
}
