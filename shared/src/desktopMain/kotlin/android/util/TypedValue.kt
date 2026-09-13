package android.util

/** TypedValue with the functional applyDimension (plugins use it for dp→px). */
class TypedValue {
    @JvmField var type: Int = 0
    @JvmField var data: Int = 0
    @JvmField var assetCookie: Int = 0
    @JvmField var resourceId: Int = 0
    @JvmField var string: CharSequence? = null
    @JvmField var float: Float = 0f
    @JvmField var dimension: Float = 0f
    @JvmField var complex: Boolean = false

    companion object {
        const val COMPLEX_UNIT_DIP = 1
        const val COMPLEX_UNIT_SP = 2
        const val COMPLEX_UNIT_PX = 0
        const val COMPLEX_UNIT_PT = 3
        const val COMPLEX_UNIT_MM = 5
        const val COMPLEX_UNIT_IN = 4

        @JvmStatic
        fun applyDimension(unit: Int, value: Float, metrics: DisplayMetrics?): Float {
            val density = metrics?.density ?: 2.0f
            val scaled = metrics?.scaledDensity ?: density
            return when (unit) {
                COMPLEX_UNIT_DIP, COMPLEX_UNIT_PT -> value * density
                COMPLEX_UNIT_SP -> value * scaled
                else -> value
            }
        }

        @JvmStatic
        fun complexToDimension(data: Int, metrics: DisplayMetrics?): Float = 0f
    }
}
