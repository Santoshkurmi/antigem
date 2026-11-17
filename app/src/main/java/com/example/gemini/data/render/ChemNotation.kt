package com.example.gemini.data.render

/**
 * mhchem-style chemistry in math, drawn natively: `\ce{...}` (and `\pu{...}`) is rewritten into plain LaTeX that
 * JLaTeXMath already draws (inline `$...$` and display math alike), with no WebView and no download.
 *
 * Follows mhchem's rules for the common cases:
 * - `2H2O` → coefficient 2, H₂O (digits after an element, `)` or `]` are subscripts)
 * - charges: a trailing `+`/`-` (`Na+`, `OH-`, `NO3-`), or `^2-` / `^{2-}` (`SO4^2-`, `Fe^{3+}`)
 * - states `(s) (l) (g) (aq)`, gas `^` and precipitate `v` as separate words
 * - arrows `->`, `<-`, `<->`, `<-->`, `<=>`, `<=>>`, `<<=>`, with `[above][below]` text (itself read as `\ce`)
 * - bonds inside a formula `-`, `=`, `#`, `~` and `\bond{...}`; addition dot `*` or `.` (`CuSO4*5H2O`)
 * - isotopes `^{14}_{6}C`, radicals `Cl.` / `^{.-}`, amounts `n H2O`, `nH2O`, `(1/2)O2`
 * - typed Unicode: `→ ⇌ ↔ ↑ ↓`, `H₂O`, `SO₄²⁻`
 * - `$...$` math and `{text}` kept as they are, `\Delta`-style commands passed through
 */
object ChemNotation {

    private val COMMAND = Regex("\\\\(ce|pu)\\s*\\{")

    fun hasChem(latex: String): Boolean = latex.contains("\\ce") || latex.contains("\\pu")

    /** [latex] with every `\ce{...}` / `\pu{...}` rewritten; unchanged when there is none. */
    fun expand(latex: String): String {
        if (!hasChem(latex)) return latex
        val out = StringBuilder()
        var i = 0
        while (i < latex.length) {
            val m = COMMAND.find(latex, i)
            if (m == null) {
                out.append(latex, i, latex.length)
                break
            }
            out.append(latex, i, m.range.first)
            val open = m.range.last
            val close = matchingBrace(latex, open)
            if (close < 0) { // unbalanced: leave the rest as it is
                out.append(latex, m.range.first, latex.length)
                break
            }
            val inner = latex.substring(open + 1, close)
            out.append('{').append(if (m.groupValues[1] == "ce") ce(inner) else pu(inner)).append('}')
            i = close + 1
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ \ce

    /** A word that is only an amount: n, x, 2n, n-1, (n+1) (not v / e: precipitate and electron). */
    private val AMOUNT_WORD = Regex("^(?:\\d*[a-df-uw-z]|\\d*[a-df-uw-z][+-]\\d+|\\(\\d*[a-df-uw-z][+-]\\d+\\))$")

    private val ARROWS = listOf("<=>>", "<<=>", "<-->", "<=>", "<->", "->", "<-")

    private sealed class Tok {
        data class Arrow(val kind: String, val above: String?, val below: String?) : Tok()
        data class Word(val text: String) : Tok()
    }

    private val SUB_DIGITS = "₀₁₂₃₄₅₆₇₈₉"
    private val SUP_CHARS = mapOf('⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6',
        '⁷' to '7', '⁸' to '8', '⁹' to '9', '⁺' to '+', '⁻' to '-')
    private val SUP_RUN = Regex("[⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻]+")
    private val UNICODE_ARROWS = listOf(
        "⟷" to "<->", "↔" to "<->", "⇌" to "<=>", "⇋" to "<=>", "⇄" to "<-->", "⟶" to "->", "→" to "->", "⟵" to "<-", "←" to "<-",
        "↑" to " ^ ", "↓" to " v "
    )

    /** H₂O → H2O, SO₄²⁻ → SO4^{2-}, − → -, · → *. */
    private fun normalizeUnicode(src: String): String {
        var t = src.replace('−', '-').replace('–', '-').replace('·', '*').replace('×', '*')
        t = buildString { t.forEach { c -> val d = SUB_DIGITS.indexOf(c); append(if (d >= 0) ('0' + d) else c) } }
        t = SUP_RUN.replace(t) { m -> "^{" + m.value.map { SUP_CHARS[it] }.joinToString("") + "}" }
        return t
    }

    private fun ce(src: String): String {
        var text = normalizeUnicode(src).replace(Regex("(\\d)\\s+°"), "$1°")
        UNICODE_ARROWS.forEach { (u, a) -> text = text.replace(u, " $a ") }
        val toks = tokenize(text)
        val out = StringBuilder()
        var prevWasFormula = false
        for (t in toks) {
            when (t) {
                is Tok.Arrow -> {
                    out.append(' ').append(arrow(t)).append(' ')
                    prevWasFormula = false
                }
                is Tok.Word -> {
                    val w = t.text
                    when {
                        w == "+" -> { out.append(" + "); prevWasFormula = false }
                        w == "^" -> { out.append("\\uparrow "); prevWasFormula = false }
                        w == "v" -> { out.append("\\downarrow "); prevWasFormula = false }
                        // an amount on its own: x Na2SO4, n H2O, 2n, (n-1)
                        AMOUNT_WORD.matches(w) -> {
                            if (prevWasFormula) out.append("\\ ")
                            out.append(w).append("\\,")
                            prevWasFormula = false
                        }
                        w.startsWith("$") && w.endsWith("$") && w.length >= 2 -> {
                            if (prevWasFormula) out.append("\\ ")
                            out.append(w.substring(1, w.length - 1)).append(' ')
                            prevWasFormula = true
                        }
                        else -> {
                            if (prevWasFormula) out.append("\\ ")
                            out.append(formula(w))
                            prevWasFormula = true
                        }
                    }
                }
            }
        }
        return out.toString().trim()
    }

    /** Splits on spaces outside {}, [], $...$; arrows (with their [..][..]) become their own tokens. */
    private fun tokenize(s: String): List<Tok> {
        val toks = mutableListOf<Tok>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) toks += Tok.Word(cur.toString())
            cur.setLength(0)
        }
        var i = 0
        var depth = 0
        var inMath = false
        while (i < s.length) {
            val c = s[i]
            if (inMath) {
                cur.append(c)
                if (c == '$') inMath = false
                i++
                continue
            }
            if (depth == 0) {
                val arrow = ARROWS.firstOrNull { s.startsWith(it, i) }
                // "-" right after a formula letter is a bond, but "->" is always an arrow
                if (arrow != null) {
                    flush()
                    i += arrow.length
                    val above = bracket(s, i)
                    if (above != null) i = above.second
                    val below = if (above != null) bracket(s, i) else null
                    if (below != null) i = below.second
                    toks += Tok.Arrow(arrow, above?.first, below?.first)
                    continue
                }
                if (c.isWhitespace()) {
                    flush(); i++; continue
                }
            }
            when (c) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
                '$' -> if (depth == 0) inMath = true
            }
            cur.append(c)
            i++
        }
        flush()
        return toks
    }

    /** `[text]` at [from] → (text, index after `]`), or null. */
    private fun bracket(s: String, from: Int): Pair<String, Int>? {
        if (from >= s.length || s[from] != '[') return null
        var depth = 0
        var i = from
        while (i < s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return s.substring(from + 1, i) to i + 1
                }
            }
            i++
        }
        return null
    }

    private fun arrow(a: Tok.Arrow): String {
        val above = a.above?.takeIf { it.isNotBlank() }?.let { ce(it) }
        val below = a.below?.takeIf { it.isNotBlank() }?.let { ce(it) }
        fun stacked(symbol: String): String = when {
            above != null && below != null -> "\\underset{$below}{\\overset{$above}{$symbol}}"
            above != null -> "\\overset{$above}{$symbol}"
            below != null -> "\\underset{$below}{$symbol}"
            else -> symbol
        }
        return when (a.kind) {
            "->" -> when {
                above == null && below == null -> "\\longrightarrow"
                below != null -> "\\xrightarrow[$below]{${above ?: ""}}"
                else -> "\\xrightarrow{$above}"
            }
            "<-" -> when {
                above == null && below == null -> "\\longleftarrow"
                below != null -> "\\xleftarrow[$below]{${above ?: ""}}"
                else -> "\\xleftarrow{$above}"
            }
            "<->" -> stacked("\\longleftrightarrow")
            "<-->" -> stacked("\\leftrightarrows")
            // unbalanced equilibria: the favoured direction gets the long arrow
            "<=>>" -> stacked("\\mathrel{\\underset{\\scriptstyle\\leftharpoondown}{\\longrightarrow}}")
            "<<=>" -> stacked("\\mathrel{\\overset{\\scriptstyle\\rightharpoonup}{\\longleftarrow}}")
            else -> stacked("\\rightleftharpoons") // <=>
        }
    }

    /** One formula word: coefficient, elements with subscripts, charges, states, bonds, dots. */
    private fun formula(word: String): String {
        val out = StringBuilder()
        var s = word

        // stoichiometric coefficient: 2, 0.5, 1/2, (1/2) (only before a formula, "(" or "[")
        val frac = Regex("^\\((\\d+)/(\\d+)\\)(?=[A-Z(\\[]|$)").find(s)
        val coef = Regex("^(\\d+/\\d+|\\d+(?:\\.\\d+)?)(?=[A-Ze(\\[\\{\\\\°]|[nxyz][A-Z(\\[]|$)").find(s)
        if (frac != null) {
            out.append("\\frac{${frac.groupValues[1]}}{${frac.groupValues[2]}}")
            s = s.substring(frac.value.length)
            if (s.isNotEmpty()) out.append("\\,")
        } else if (coef != null) {
            val c = coef.value
            out.append(if (c.contains('/')) "\\frac{${c.substringBefore('/')}}{${c.substringAfter('/')}}" else c)
            s = s.substring(c.length)
            if (s.isNotEmpty() && !s.startsWith("°") && !AMOUNT_LETTER.containsMatchIn(s)) out.append("\\,")
        }
        // an amount letter before the formula: nH2O, xH2O, 2nH2O (an element never starts lower-case)
        AMOUNT_LETTER.find(s)?.let { m ->
            out.append(m.groupValues[1]).append("\\,")
            s = s.substring(1)
        }

        val rm = StringBuilder()
        fun flush() {
            if (rm.isNotEmpty()) out.append("\\mathrm{").append(rm).append('}')
            rm.setLength(0)
        }
        // what the last thing was: a subscript may follow letters, ")" and "]"
        var afterAtom = false
        var i = 0
        if (s.startsWith("^") || s.startsWith("_")) out.append("{}") // isotope: ^{14}_{6}C
        while (i < s.length) {
            val c = s[i]
            val atEnd = i == s.length - 1
            when {
                c.isLetter() && c.code < 128 -> { rm.append(c); afterAtom = true; i++ }
                c.isDigit() && afterAtom -> {
                    var j = i
                    while (j < s.length && s[j].isDigit()) j++
                    // digits followed only by a charge at the very end are still subscripts (mhchem: NO3-)
                    flush(); out.append("_{").append(s, i, j).append('}')
                    i = j
                }
                c == '(' || c == ')' || c == '[' || c == ']' -> {
                    rm.append(c); afterAtom = c == ')' || c == ']'; i++
                }
                c == '^' -> {
                    flush()
                    val (sup, next) = script(s, i + 1, charge = true)
                    out.append("^{").append(radicalDots(sup)).append('}')
                    afterAtom = false
                    i = next
                }
                c == '_' -> {
                    flush()
                    val (sub, next) = script(s, i + 1, charge = false)
                    out.append("_{").append(sub).append('}')
                    afterAtom = false
                    i = next
                }
                (c == '+' || c == '-') && s.substring(i).all { it == '+' || it == '-' } -> {
                    // trailing charge: Na+, OH-, Fe++
                    flush()
                    out.append("^{").append(s.substring(i)).append('}')
                    i = s.length
                }
                c == '-' && !atEnd -> { flush(); out.append("{-}"); afterAtom = false; i++ }
                c == '=' -> { flush(); out.append("{=}"); afterAtom = false; i++ }
                c == '#' -> { flush(); out.append("{\\equiv}"); afterAtom = false; i++ }
                c == '~' -> { flush(); out.append("{\\sim}"); afterAtom = false; i++ }
                // radical: Cl. , OH. (a dot at the very end of a formula; "conc." stays a full stop)
                c == '.' && atEnd && afterAtom && s.any { it.isUpperCase() } -> { flush(); out.append("^{\\bullet}"); i++ }
                c == '*' || (c == '.' && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1].isUpperCase())) -> {
                    // addition compound: CuSO4*5H2O → the rest is a new formula with its own coefficient
                    flush()
                    out.append("\\cdot ").append(formula(s.substring(i + 1)))
                    return out.toString()
                }
                c == '°' -> { flush(); out.append("^{\\circ}"); afterAtom = false; i++ }
                c == '\\' && s.startsWith("\\bond{", i) && matchingBrace(s, i + 5) > 0 -> {
                    flush()
                    val close = matchingBrace(s, i + 5)
                    out.append(bond(s.substring(i + 6, close)))
                    afterAtom = false
                    i = close + 1
                }
                c == '\\' -> {
                    flush()
                    var j = i + 1
                    while (j < s.length && s[j].isLetter()) j++
                    if (j == i + 1 && j < s.length) j++ // \, \; \{ ...
                    out.append(s, i, j)
                    if (j < s.length && s[j] == '{') {
                        val close = matchingBrace(s, j)
                        if (close > 0) { out.append(s, j, close + 1); j = close + 1 }
                    }
                    out.append(' ')
                    afterAtom = true
                    i = j
                }
                c == '{' -> {
                    flush()
                    val close = matchingBrace(s, i)
                    if (close < 0) { rm.append(s.substring(i + 1)); i = s.length } else {
                        out.append("\\text{").append(s, i + 1, close).append('}')
                        i = close + 1
                    }
                    afterAtom = true
                }
                c == '$' -> {
                    flush()
                    val end = s.indexOf('$', i + 1)
                    if (end < 0) { out.append(s.substring(i + 1)); i = s.length } else {
                        out.append(s, i + 1, end); i = end + 1
                    }
                    afterAtom = false
                }
                else -> { rm.append(c); afterAtom = false; i++ }
            }
        }
        flush()
        return out.toString()
    }

    private val AMOUNT_LETTER = Regex("^([nxyz])(?=[A-Z(\\[])")

    /** \bond{...} → the bond sign (mhchem's names). */
    private fun bond(kind: String): String = when (kind.trim()) {
        "-", "1" -> "{-}"
        "=", "2" -> "{=}"
        "#", "3" -> "{\\equiv}"
        "~" -> "{\\sim}"
        "~-" -> "{\\simeq}"
        "~=", "~--" -> "{\\overset{\\sim}{=}}"
        "...", "...." -> "{\\cdots}"
        "->" -> "{\\rightarrow}"
        "<-" -> "{\\leftarrow}"
        else -> "{-}"
    }

    /** A charge superscript: "." is a radical dot (^{.-} → •−), Roman oxidation states are upright (^{II}). */
    private fun radicalDots(sup: String): String = when {
        ROMAN.matches(sup) -> "\\mathrm{$sup}"
        Regex("\\d\\.\\d").containsMatchIn(sup) -> sup
        else -> sup.replace(".", "\\bullet ")
    }
    private val ROMAN = Regex("[IVX]+|[+-][IVX]+|0")

    /** After `^` / `_`: `{...}` or a run like `2-`, `3+`, `II`, `x`. Returns (content, next index). */
    private fun script(s: String, from: Int, charge: Boolean): Pair<String, Int> {
        if (from >= s.length) return "" to from
        if (s[from] == '{') {
            val close = matchingBrace(s, from)
            if (close > 0) return s.substring(from + 1, close) to close + 1
        }
        if (s[from] == '\\') {
            var j = from + 1
            while (j < s.length && s[j].isLetter()) j++
            return s.substring(from, j) to j
        }
        var j = from
        if (charge) {
            // ^-1, ^+2 (sign first, then digits): an exponent
            if ((s[from] == '-' || s[from] == '+') && from + 1 < s.length && s[from + 1].isDigit()) {
                j = from + 1
                while (j < s.length && s[j].isDigit()) j++
                return s.substring(from, j) to j
            }
            // ^2-, ^3+, ^-, ^{...}; oxidation states ^II
            while (j < s.length && s[j].isDigit()) j++
            if (j < s.length && (s[j] == '+' || s[j] == '-')) {
                while (j < s.length && (s[j] == '+' || s[j] == '-')) j++
                return s.substring(from, j) to j
            }
            if (j > from) return s.substring(from, j) to j
            while (j < s.length && s[j].isLetter()) j++
            if (j > from) return s.substring(from, j) to j
        } else {
            while (j < s.length && s[j].isLetterOrDigit()) j++
            if (j > from) return s.substring(from, j) to j
        }
        return s.substring(from, from + 1) to from + 1
    }

    // ------------------------------------------------------------------ \pu

    /**
     * `\pu{123 kJ/mol}`, `\pu{8.314 J K-1 mol-1}`, `\pu{9.8 m s^-2}`, `\pu{1.2e3 J}`, `\pu{1.2 x 10^3 m}`, `\pu{25 °C}`:
     * the number, then upright units; a unit's trailing exponent may be written with or without `^`.
     */
    private fun pu(src: String): String {
        val s = normalizeUnicode(src).trim()
        val out = StringBuilder()
        var rest = s
        val num = PU_NUMBER.find(s)
        if (num != null) {
            val mantissa = num.groupValues[1]
            val e = mantissa.indexOfFirst { it == 'e' || it == 'E' }
            if (e > 0) out.append(mantissa.substring(0, e)).append("\\times 10^{").append(mantissa.substring(e + 1).removePrefix("+")).append('}')
            else out.append(mantissa)
            if (num.groupValues[2].isNotEmpty()) out.append("\\times 10^{").append(num.groupValues[2].removePrefix("+")).append('}')
            rest = s.substring(num.value.length).trim()
        }
        if (rest.isEmpty()) return out.toString()
        if (out.isNotEmpty() && !rest.startsWith("°")) out.append("\\,")
        // units, split on the separators (kept)
        var i = 0
        val part = StringBuilder()
        fun unit() {
            if (part.isEmpty()) return
            out.append(puUnit(part.toString()))
            part.setLength(0)
        }
        while (i < rest.length) {
            val c = rest[i]
            when {
                c == ' ' || (c == '.' && !(i > 0 && rest[i - 1].isDigit() && i + 1 < rest.length && rest[i + 1].isDigit())) -> {
                    unit()
                    if (out.isNotEmpty() && !out.endsWith("\\,")) out.append("\\,")
                }
                c == '*' -> { unit(); out.append("\\cdot ") }
                c == '/' -> { unit(); out.append('/') }
                c == '\\' -> {
                    unit()
                    var j = i + 1
                    while (j < rest.length && rest[j].isLetter()) j++
                    out.append(rest, i, j).append(' ')
                    i = j
                    continue
                }
                else -> part.append(c)
            }
            i++
        }
        unit()
        return out.toString()
    }

    /** 1.5, -92, 1.2e3, 1.2 x 10^3, 1.2 \times 10^{-3} → (mantissa, power of ten or ""). */
    private val PU_NUMBER = Regex("""^([-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)(?:\s*(?:\*|x|\\times|\\cdot)\s*10\^\{?([-+]?\d+)\}?)?""")
    /** A unit with its exponent: K-1, mol^-1, m2, s^{-2}, cm^3. */
    private val PU_UNIT = Regex("""^(.*?[A-Za-zΩµμ%])\^?\{?([-+]?\d+)\}?$""")

    private fun puUnit(raw: String): String {
        if (raw.startsWith("°")) return "{}^{\\circ}" + if (raw.length > 1) "\\mathrm{${raw.substring(1)}}" else ""
        val m = PU_UNIT.find(raw)
        val base = m?.groupValues?.get(1) ?: raw
        val exp = m?.groupValues?.get(2)
        val name = base.replace("Ω", "\\Omega ").replace("µ", "\\mu ").replace("μ", "\\mu ")
        val tex = "\\mathrm{$name}"
        return if (exp != null) "$tex^{${exp.removePrefix("+")}}" else tex
    }

    // ------------------------------------------------------------------ helpers

    /** Index of the `}` closing the `{` at [open], or -1. */
    private fun matchingBrace(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++ // skip escaped char
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}
