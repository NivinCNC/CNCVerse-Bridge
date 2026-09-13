package android.text

/** TextUtils — the helpers settings UIs actually use. */
object TextUtils {
    @JvmStatic
    fun isEmpty(s: CharSequence?): Boolean = s == null || s.length == 0

    @JvmStatic
    fun isBlank(s: CharSequence?): Boolean = s == null || s.all { it.isWhitespace() }

    @JvmStatic
    fun isNotEmpty(s: CharSequence?): Boolean = !isEmpty(s)

    @JvmStatic
    fun equals(a: CharSequence?, b: CharSequence?): Boolean = a == b

    @JvmStatic
    fun join(delimiter: CharSequence, tokens: Iterable<*>): String =
        tokens.joinToString(delimiter.toString())

    @JvmStatic
    fun split(text: String, expression: String): Array<String> =
        text.split(expression.toRegex()).toTypedArray()

    @JvmStatic
    fun trim(s: CharSequence?): CharSequence = (s ?: "").trim()

    @JvmStatic
    fun substring(s: CharSequence, start: Int, end: Int): CharSequence = s.subSequence(start, end)
}
