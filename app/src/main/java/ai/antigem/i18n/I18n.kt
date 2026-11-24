package ai.antigem.i18n

/**
 * The lookups behind [L] (generated from assets/i18n/en.json, see GenerateStringsTask in app/build.gradle.kts).
 *
 * In English [table] is null and every accessor returns the English literal compiled into it: nothing is looked up.
 * In another language an accessor reads one slot of [table]; a slot the language leaves empty shows English.
 * [LanguageManager] fills [table] once (from a pre-indexed cache) and swaps it when the language or its texts change.
 */
object I18n {
    /**
     * The app language's texts in [L]'s order ([L.COUNT] slots), or null while the app is in English. A slot is a
     * String, an Array<String?> of plural forms ([PLURAL_FORMS] order), or null (not translated: English is shown).
     * Not volatile on purpose: a thread that sees an old table or an empty slot just shows English for a moment.
     */
    @JvmField var table: Array<Any?>? = null

    /** The plural form (index into [PLURAL_FORMS]) the app language uses for a count. */
    @JvmField var pluralOf: (Int) -> Int = { if (it == 1) ONE else OTHER }

    val PLURAL_FORMS = listOf("zero", "one", "two", "few", "many", "other")
    const val ONE = 1
    const val OTHER = 5

    @JvmStatic
    fun s(i: Int, en: String): String {
        val t = table ?: return en
        return t[i] as? String ?: en
    }

    @JvmStatic
    fun f(i: Int, en: String, n1: String, v1: Any?): String = fill(s(i, en), n1, v1, null, null, null, null)

    @JvmStatic
    fun f(i: Int, en: String, n1: String, v1: Any?, n2: String, v2: Any?): String = fill(s(i, en), n1, v1, n2, v2, null, null)

    @JvmStatic
    fun f(i: Int, en: String, n1: String, v1: Any?, n2: String, v2: Any?, n3: String, v3: Any?): String =
        fill(s(i, en), n1, v1, n2, v2, n3, v3)

    @JvmStatic
    fun fv(i: Int, en: String, names: Array<String>, values: Array<Any?>): String = fillAll(s(i, en), names, values)

    /** A plural text: the form the language uses for [count], with {count} and the other placeholders filled in. */
    @JvmStatic
    fun p(i: Int, count: Int, one: String, other: String, vararg pairs: Any?): String {
        val english = if (count == 1) one else other
        @Suppress("UNCHECKED_CAST")
        val forms = table?.get(i) as? Array<String?>
        val text = if (forms == null) english else forms[pluralOf(count)] ?: forms[OTHER] ?: english
        val names = Array(pairs.size / 2 + 1) { if (it == 0) "count" else pairs[it * 2 - 2] as String }
        val values = Array(pairs.size / 2 + 1) { if (it == 0) count else pairs[it * 2 - 1] }
        return fillAll(text, names, values)
    }

    /** {name} → its value; an unknown {name} stays as it is. */
    private fun fill(text: String, n1: String, v1: Any?, n2: String?, v2: Any?, n3: String?, v3: Any?): String {
        var open = text.indexOf('{')
        if (open < 0) return text
        val out = StringBuilder(text.length + 16)
        var from = 0
        while (open >= 0) {
            val close = text.indexOf('}', open + 1)
            if (close < 0) break
            val value: Any? = when {
                text.regionMatches(open + 1, n1, 0, n1.length) && close == open + 1 + n1.length -> v1 ?: "null"
                n2 != null && text.regionMatches(open + 1, n2, 0, n2.length) && close == open + 1 + n2.length -> v2 ?: "null"
                n3 != null && text.regionMatches(open + 1, n3, 0, n3.length) && close == open + 1 + n3.length -> v3 ?: "null"
                else -> null
            }
            if (value != null) {
                out.append(text, from, open).append(value)
                from = close + 1
            }
            open = text.indexOf('{', if (value != null) close + 1 else open + 1)
        }
        return out.append(text, from, text.length).toString()
    }

    private fun fillAll(text: String, names: Array<String>, values: Array<out Any?>): String {
        var open = text.indexOf('{')
        if (open < 0) return text
        val out = StringBuilder(text.length + 16)
        var from = 0
        while (open >= 0) {
            val close = text.indexOf('}', open + 1)
            if (close < 0) break
            var hit = -1
            for (k in names.indices) {
                val n = names[k]
                if (close == open + 1 + n.length && text.regionMatches(open + 1, n, 0, n.length)) { hit = k; break }
            }
            if (hit >= 0) {
                out.append(text, from, open).append(values[hit] ?: "null")
                from = close + 1
            }
            open = text.indexOf('{', if (hit >= 0) close + 1 else open + 1)
        }
        return out.append(text, from, text.length).toString()
    }
}
