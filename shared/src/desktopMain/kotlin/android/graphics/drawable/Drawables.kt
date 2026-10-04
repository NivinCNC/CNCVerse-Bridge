package android.graphics.drawable

/** android.graphics.drawable stubs — verification targets. */
open class Drawable {
    fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {}
    fun setAlpha(alpha: Int) {}
    fun invalidateSelf() {}
    fun setTint(tintColor: Int) {}
    val intrinsicWidth: Int get() = 0
    val intrinsicHeight: Int get() = 0
}

/** Retain the Android drawable hierarchy for plugin settings discovery. */
class StateListDrawable : Drawable()

class ColorDrawable : Drawable {
    var color: Int = 0
        private set

    constructor()
    constructor(color: Int) { this.color = color }
}

class GradientDrawable : Drawable() {
    fun setColor(color: Int) {}
    fun setCornerRadius(radius: Float) {}
    fun setShape(shape: Int) {}
    fun setOrientation(orientation: Int) {}
    fun setStroke(width: Int, color: Int) {}
    fun setGradientType(type: Int) {}
    fun setOrientationRadians(orientation: FloatArray?) {}

    companion object {
        const val RECTANGLE = 0
        const val OVAL = 1
        const val LINE = 2
        const val RING = 3

        const val LINEAR_GRADIENT = 0
        const val RADIAL_GRADIENT = 1
        const val SWEEP_GRADIENT = 2
    }
}
