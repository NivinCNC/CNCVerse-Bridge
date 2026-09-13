package android.graphics

/** android.graphics stubs. Color is functional (parseColor is used at runtime). */
object Color {
    @JvmField val BLACK: Int = -0x1000000
    @JvmField val WHITE: Int = -0x1
    @JvmField val RED: Int = -0x10000
    @JvmField val GREEN: Int = -0xff0100
    @JvmField val BLUE: Int = -0xffff01
    @JvmField val TRANSPARENT: Int = 0
    @JvmField val GRAY: Int = -0x777778

    @JvmStatic
    fun parseColor(colorString: String): Int {
        val s = colorString.trim()
        return when {
            s.startsWith("#") -> {
                val hex = s.removePrefix("#")
                when (hex.length) {
                    6 -> 0xFF000000.toInt() or hex.toLong(16).toInt()
                    8 -> hex.toLong(16).toInt()
                    3 -> {
                        val r = hex[0].digitToIntOrNull(16) ?: return BLACK
                        val g = hex[1].digitToIntOrNull(16) ?: return BLACK
                        val b = hex[2].digitToIntOrNull(16) ?: return BLACK
                        0xFF000000.toInt() or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
                    }
                    else -> throw IllegalArgumentException("Unknown color: $colorString")
                }
            }
            s.startsWith("rgb(") -> {
                val parts = s.removePrefix("rgb(").removeSuffix(")").split(',').map { it.trim().toInt() }
                rgb(parts[0], parts[1], parts[2])
            }
            else -> throw IllegalArgumentException("Unknown color: $colorString")
        }
    }

    @JvmStatic
    fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue

    @JvmStatic
    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    @JvmStatic
    fun alpha(color: Int): Int = color ushr 24
    @JvmStatic
    fun red(color: Int): Int = (color shr 16) and 0xFF
    @JvmStatic
    fun green(color: Int): Int = (color shr 8) and 0xFF
    @JvmStatic
    fun blue(color: Int): Int = color and 0xFF
}

class Typeface private constructor() {
    var isBold: Boolean = false
        private set

    companion object {
        @JvmField val DEFAULT: Typeface = Typeface()
        @JvmField val DEFAULT_BOLD: Typeface = Typeface().apply { isBold = true }
        @JvmField val MONOSPACE: Typeface = Typeface()
        @JvmField val SANS_SERIF: Typeface = Typeface()
        @JvmField val SERIF: Typeface = Typeface()

        // Style constants (plugins reference these as static fields)
        @JvmField val NORMAL: Int = 0
        @JvmField val BOLD: Int = 1
        @JvmField val ITALIC: Int = 2
        @JvmField val BOLD_ITALIC: Int = 3

        @JvmStatic
        fun create(family: Typeface?, style: Int): Typeface =
            if (style == 1) DEFAULT_BOLD else DEFAULT

        @JvmStatic
        fun defaultFromStyle(style: Int): Typeface =
            if (style == 1) DEFAULT_BOLD else DEFAULT
    }
}

open class Point(@JvmField var x: Int = 0, @JvmField var y: Int = 0) {
    fun set(x: Int, y: Int) {
        this.x = x
        this.y = y
    }
}
