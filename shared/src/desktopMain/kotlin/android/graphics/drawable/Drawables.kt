package android.graphics.drawable

/** android.graphics.drawable stubs — RECORDING implementation. */
open class Drawable {
    @JvmField var boundsLeft: Int = 0
    @JvmField var boundsTop: Int = 0
    @JvmField var boundsRight: Int = 0
    @JvmField var boundsBottom: Int = 0

    fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        boundsLeft = left; boundsTop = top; boundsRight = right; boundsBottom = bottom
    }

    open fun setAlpha(alpha: Int) {}
    fun invalidateSelf() {}
    fun setTint(tintColor: Int) {}
    open val intrinsicWidth: Int get() = 0
    open val intrinsicHeight: Int get() = 0
    fun setCallback(callback: Any?) {}
    fun setVisible(visible: Boolean, restart: Boolean): Boolean = true
}

class ColorDrawable : Drawable {
    var color: Int = 0

    constructor() : super()

    constructor(color: Int) : super() {
        this.color = color
    }
}

/**
 * Records gradient shape info (corner radius, stroke, colors) so the renderer
 * can paint plugin-styled cards and pills faithfully.
 */
class GradientDrawable : Drawable {
    @JvmField var color: Int = 0
    @JvmField var cornerRadius: Float = 0f
    @JvmField var strokeWidth: Int = 0
    @JvmField var strokeColor: Int = 0
    @JvmField var shape: Int = RECTANGLE
    @JvmField var orientation: Orientation = Orientation.TOP_BOTTOM
    @JvmField var gradientColors: IntArray? = null
    @JvmField var alphaValue: Int = 255

    constructor() : super()

    constructor(orientation: Orientation?, colors: IntArray?) : super() {
        this.orientation = orientation ?: Orientation.TOP_BOTTOM
        this.gradientColors = colors
    }

    fun setColor(color: Int) { this.color = color }
    fun setColors(colors: IntArray?) { this.gradientColors = colors }
    fun setCornerRadius(radius: Float) { this.cornerRadius = radius }
    fun setShape(shape: Int) { this.shape = shape }
    fun setStroke(width: Int, color: Int) {
        this.strokeWidth = width
        this.strokeColor = color
    }

    fun setOrientation(orientation: Orientation?) {
        this.orientation = orientation ?: Orientation.TOP_BOTTOM
    }

    fun setGradientType(type: Int) {}
    fun setGradientRadius(radius: Float) {}
    fun setUseLevel(useLevel: Boolean) {}
    fun setDither(dither: Boolean) {}
    override fun setAlpha(alpha: Int) { alphaValue = alpha }

    enum class Orientation {
        TOP_BOTTOM, TR_BL, RIGHT_LEFT, BR_TL, BOTTOM_TOP, BL_TR, LEFT_RIGHT, TL_BR
    }

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

/** State-based drawables (pressed/checked visuals) — first state wins for render. */
class StateListDrawable : Drawable() {
    private val states = mutableListOf<Pair<IntArray, Drawable?>>()

    fun addState(stateSet: IntArray?, drawable: Drawable?) {
        states.add((stateSet ?: IntArray(0)) to drawable)
    }

    val firstDrawable: Drawable? get() = states.firstOrNull()?.second
    val stateCount: Int get() = states.size
}

/** Layer-list stub (stacked backgrounds). */
class LayerDrawable(private val drawables: Array<Drawable?>?) : Drawable() {
    val numberOfLayers: Int get() = drawables?.size ?: 0
    fun setDrawable(index: Int, drawable: Drawable?) {}
    fun addLayer(drawable: Drawable?) {}
}

/** Inset drawable — records insets, delegates to the wrapped drawable. */
class InsetDrawable : Drawable {
    @JvmField var insetLeft: Int = 0
    @JvmField var insetTop: Int = 0
    @JvmField var insetRight: Int = 0
    @JvmField var insetBottom: Int = 0
    @JvmField var inner: Drawable? = null

    constructor(drawable: Drawable?, insetLeft: Int, insetTop: Int, insetRight: Int, insetBottom: Int) : super() {
        this.inner = drawable
        this.insetLeft = insetLeft
        this.insetTop = insetTop
        this.insetRight = insetRight
        this.insetBottom = insetBottom
    }
}

/** Drawable that knows its resource name (icons) — renderer maps to a glyph. */
class NamedDrawable(@JvmField val name: String) : Drawable()
