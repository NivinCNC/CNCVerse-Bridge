package android.content.res

import android.util.AttributeSet
import android.util.DisplayMetrics
import org.xmlpull.v1.XmlPullParser

/** android.content.res.XmlResourceParser as on Android (pull parser + attrs). */
interface XmlResourceParser : XmlPullParser, AttributeSet {
    fun close()
}

/** android.content.res stubs — verification targets for plugin classes. */
open class Resources {
    fun getDisplayMetrics(): DisplayMetrics = DisplayMetrics()
    open fun getConfiguration(): Configuration = Configuration()

    /** Resource lookups resolve to "not found" (0) — plugins degrade gracefully. */
    open fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int = 0
    open fun getLayout(id: Int): XmlResourceParser? = null
    open fun getXml(id: Int): XmlResourceParser? = null
    open fun getString(id: Int): String = ""
    open fun getString(id: Int, vararg formatArgs: Any?): String = ""
    open fun getBoolean(id: Int): Boolean = false
    open fun getInteger(id: Int): Int = 0
    open fun getDimension(id: Int): Float = 0f
    open fun getDrawable(id: Int): android.graphics.drawable.Drawable? = null
    open fun getDrawable(id: Int, theme: Theme?): android.graphics.drawable.Drawable? = getDrawable(id)
    open fun getDrawableForDensity(id: Int, density: Int): android.graphics.drawable.Drawable? = getDrawable(id)
    open fun openRawResource(id: Int): java.io.InputStream? = null

    open class Configuration {
        var orientation: Int = ORIENTATION_UNDEFINED

        companion object {
            const val ORIENTATION_UNDEFINED = 0
            const val ORIENTATION_PORTRAIT = 1
            const val ORIENTATION_LANDSCAPE = 2
        }
    }

    class Theme
}

class ColorStateList {
    constructor()
    constructor(states: Array<IntArray>, colors: IntArray)

    companion object {
        @JvmStatic
        fun valueOf(color: Int): ColorStateList = ColorStateList()
    }
}

