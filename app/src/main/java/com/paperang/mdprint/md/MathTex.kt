package com.paperang.mdprint.md

/**
 * 极简 LaTeX 解析：只保留「把公式摊平成文字」这一件事——表格量宽度、字数
 * 统计、无障碍朗读都要把公式变成纯文本（见 [plain]）。
 *
 * 公式的**排版**（分式、根号、上下标、轴线、衬线斜体）已外包给
 * jlatexmath-android，见 render/JlmMath.kt；这里不再承担任何绘制职责，
 * 所以只求「别丢字符、分式给个斜杠」，不求排版正确。
 */
object MathTex {

    private val SYMBOL = mapOf(
        "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "ast" to "∗", "star" to "⋆", "circ" to "∘",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "ne" to "≠", "neq" to "≠", "approx" to "≈", "equiv" to "≡",
        "sim" to "∼", "cong" to "≅", "propto" to "∝",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "sqrt" to "√",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
        "Rightarrow" to "⇒", "leftrightarrow" to "↔", "mapsto" to "↦",
        "degree" to "°", "angle" to "∠", "perp" to "⊥", "parallel" to "∥",
        "triangle" to "△", "because" to "∵", "therefore" to "∴",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "cup" to "∪",
        "cap" to "∩", "emptyset" to "∅", "forall" to "∀", "exists" to "∃",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ",
        "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "lbrace" to "{", "rbrace" to "}", "vert" to "|", "Vert" to "‖",
        "ldots" to "…", "dots" to "…", "cdots" to "⋯",
        "quad" to " ", "qquad" to "  ", " " to " ",
    )

    /** 小写希腊字母按排版惯例走斜体（大写希腊、∑ ∫ ∂ 这些保持正体）。 */
    private val ITALIC_SYMBOL = setOf(
        "alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "zeta", "eta", "theta",
        "iota", "kappa", "lambda", "mu", "nu", "xi", "pi", "rho", "sigma", "tau", "upsilon",
        "phi", "varphi", "chi", "psi", "omega",
    )

    /** 吃掉命令但直接丢掉的（间距类）。 */
    private val DROP = setOf(",", ";", ":", "!", "displaystyle", "textstyle", "limits", "nolimits")

    /** 内容原样当文字的（\text{中文}、\mathrm{d}）。 */
    private val RAW = setOf("text", "textrm", "mathrm", "mathbf", "mathit", "operatorname")

    private val SPACE = mapOf("quad" to 1f, "qquad" to 2f, "," to 0.17f, ";" to 0.28f, ":" to 0.22f)

    fun parse(tex: String): MNode = Parser(tex.trim()).top()

    /** 摊平成「文字 + 上下标层级」，供文本渲染（打印用 Spannable，预览用 AnnotatedString）。 */
    fun runs(node: MNode): List<MRun> {
        val out = ArrayList<MRun>()
        fun walk(n: MNode, script: Int) {
            when (n) {
                is MNode.Row -> n.items.forEach { walk(it, script) }
                is MNode.Atom -> if (n.text.isNotEmpty()) out += MRun(n.text, script, n.italic)
                is MNode.Space -> out += MRun(" ", script, false)
                is MNode.Script -> {
                    walk(n.base, script)
                    n.sup?.let { walk(it, up(script)) }
                    n.sub?.let { walk(it, down(script)) }
                }
                is MNode.Frac -> {
                    // 行内排版没有基线可叠，只能退回斜杠形式；复杂分子分母加括号免得歧义
                    val num = n.num is MNode.Row && (n.num as MNode.Row).items.size > 1
                    val den = n.den is MNode.Row && (n.den as MNode.Row).items.size > 1
                    if (num) walk(MNode.Atom("("), script)
                    walk(n.num, script)
                    if (num) walk(MNode.Atom(")"), script)
                    walk(MNode.Atom("/"), script)
                    if (den) walk(MNode.Atom("("), script)
                    walk(n.den, script)
                    if (den) walk(MNode.Atom(")"), script)
                }
                is MNode.Sqrt -> {
                    n.index?.let { walk(it, up(script)) }
                    walk(MNode.Atom("√"), script)
                    val body = n.body
                    val simple = body is MNode.Atom || (body is MNode.Row && (body as MNode.Row).items.size == 1)
                    if (simple) walk(body, script) else {
                        walk(MNode.Atom("("), script); walk(body, script); walk(MNode.Atom(")"), script)
                    }
                }
            }
        }
        walk(node, 0)
        return out
    }

    private fun up(s: Int) = if (s >= 1) 1 else 1
    private fun down(s: Int) = if (s <= -1) -1 else -1

    /** 纯文字（表格量宽度、无障碍朗读用）。 */
    fun plain(node: MNode): String = runs(node).joinToString("") { it.text }

    private class Parser(private val s: String) {
        private var i = 0

        fun top(): MNode = list(null)

        /** 读到结尾或 stop（花括号）。 */
        private fun list(stop: Char?): MNode {
            val items = ArrayList<MNode>()
            while (i < s.length) {
                val c = s[i]
                if (stop != null && c == stop) { i++; break }
                when {
                    c == '{' -> { i++; items += list('}') }
                    c == '}' -> { i++ }                       // 多余的右括号，忽略
                    c == '\\' -> items += command()
                    c == '^' || c == '_' -> {
                        i++
                        val arg = arg()
                        val base = items.removeLastOrNull() ?: MNode.Row(emptyList())
                        val prev = base as? MNode.Script
                        items += if (c == '^') {
                            prev?.copy(sup = arg) ?: MNode.Script(base, arg, null)
                        } else {
                            prev?.copy(sub = arg) ?: MNode.Script(base, null, arg)
                        }
                    }
                    c.isWhitespace() -> { i++ }
                    else -> items += atom()
                }
            }
            return if (items.size == 1) items[0] else MNode.Row(items)
        }

        /** 一个操作数：{…} 或单个字符 / 命令。 */
        private fun arg(): MNode {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) return MNode.Row(emptyList())
            return when (s[i]) {
                '{' -> { i++; list('}') }
                '\\' -> command()
                else -> atom()
            }
        }

        /** 连续的数字/字母拼成一个原子；单个拉丁字母按数学惯例走斜体。 */
        private fun atom(): MNode {
            val start = i
            val c = s[i]
            if (c.isLetterOrDigit()) {
                while (i < s.length && s[i].isLetterOrDigit()) {
                    // 字母后紧跟数字（如 x2）仍然分开，避免把 2x 读成一个词
                    if (s[i].isDigit() != c.isDigit()) break
                    i++
                }
                val t = s.substring(start, i)
                // 字母串一律当变量走斜体。`ac` 是两个变量相乘，不能因为拼成一个
                // token 就退化成正体——否则同一行里单个 a 斜着、ac 里的 a 立着。
                // 数字串保持正体，非 ASCII（中文之类）也保持正体。
                return MNode.Atom(t, italic = t.all { it.isLetter() && it.code < 128 })
            }
            i++
            return MNode.Atom(c.toString())
        }

        private fun command(): MNode {
            i++                                            // 跳过反斜杠
            val st = i
            while (i < s.length && s[i].isLetter()) i++
            val name = if (i > st) s.substring(st, i) else {
                // \{ \; \, 这类单字符命令
                if (i < s.length) { val ch = s[i].toString(); i++; ch } else ""
            }
            return when {
                name.isEmpty() -> MNode.Atom("")
                name == "frac" || name == "dfrac" || name == "tfrac" -> {
                    val a = arg(); val b = arg()
                    MNode.Frac(a, b)
                }
                name == "sqrt" -> {
                    var index: MNode? = null
                    if (i < s.length && s[i] == '[') {
                        i++
                        val st2 = i
                        while (i < s.length && s[i] != ']') i++
                        index = Parser(s.substring(st2, i)).top()
                        if (i < s.length) i++
                    }
                    MNode.Sqrt(index, arg())
                }
                name in RAW -> {
                    while (i < s.length && s[i].isWhitespace()) i++
                    if (i < s.length && s[i] == '{') { i++; MNode.Atom(rawText('}')) }
                    else MNode.Atom("")
                }
                name == "left" || name == "right" -> {
                    while (i < s.length && s[i].isWhitespace()) i++
                    if (i < s.length) { val d = s[i]; i++; MNode.Atom(if (d == '.') "" else d.toString()) }
                    else MNode.Atom("")
                }
                name == "\\" -> MNode.Space(0.3f)
                name in DROP -> MNode.Space(0f)
                SPACE[name] != null -> MNode.Space(SPACE[name]!!)
                SYMBOL[name] != null -> MNode.Atom(SYMBOL[name]!!, italic = name in ITALIC_SYMBOL)
                name.length == 1 -> MNode.Atom(name)
                else -> MNode.Atom(name)                       // 未知命令：原样当文字，别吞掉
            }
        }

        /** 取出一段原始文字（\text{sin}），里面允许嵌套花括号。 */
        private fun rawText(stop: Char): String {
            val sb = StringBuilder()
            var depth = 1
            while (i < s.length) {
                val c = s[i]
                if (c == '{') depth++
                if (c == stop) {
                    depth--
                    if (depth == 0) { i++; break }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }
}

/** 一段文字 + 上下标层级（-1 下标 / 0 正常 / +1 上标）。 */
data class MRun(val text: String, val script: Int, val italic: Boolean)

sealed interface MNode {
    data class Atom(val text: String, val italic: Boolean = false) : MNode
    data class Space(val em: Float) : MNode
    data class Row(val items: List<MNode>) : MNode
    data class Script(val base: MNode, val sup: MNode?, val sub: MNode?) : MNode
    data class Frac(val num: MNode, val den: MNode) : MNode
    data class Sqrt(val index: MNode?, val body: MNode) : MNode
}
