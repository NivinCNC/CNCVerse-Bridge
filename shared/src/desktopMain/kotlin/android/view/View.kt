package android.view

import android.content.Context
import android.graphics.drawable.Drawable
import com.cncverse.stremiobridge.shadowui.ShadowUi
import org.xmlpull.v1.XmlPullParser as PullParser

/**
 * android.view stubs — RECORDING implementation.
 *
 * Plugins build their settings UI with the Android view API; on desktop every
 * mutation is recorded on the stub objects (this is the "shadow" UI model the
 * Compose renderer walks). Calls the stub doesn't implement are still neutered
 * by PluginBytecodeTransformer, so a missing member degrades that one feature
 * instead of crashing the plugin.
 */
/** Parent chain used by animateExpand-style code. */
interface ViewParent {
    fun getParent(): ViewParent?
}

open class View {
    constructor()
    constructor(context: Context?)
    constructor(context: Context?, attrs: Any?)

    interface OnClickListener {
        fun onClick(v: View?)
    }

    interface OnLongClickListener {
        fun onLongClick(v: View?): Boolean
    }

    interface OnKeyListener {
        fun onKey(v: View?, keyCode: Int, event: Any?): Boolean
    }

    interface OnTouchListener {
        fun onTouch(v: View?, event: Any?): Boolean
    }

    interface OnFocusChangeListener {
        fun onFocusChange(v: View?, hasFocus: Boolean)
    }

    @JvmField var id: Int = 0

    /** Whether the view participates in layout (GONE views are skipped by the renderer). */
    var isEnabled: Boolean = true
    var visibility: Int = VISIBLE
        set(value) { field = value; ShadowUi.bump() }

    @JvmField var layoutParams: ViewGroup.LayoutParams? = null

    fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        layoutParams = params
        ShadowUi.bump()
    }

    fun getLayoutParams(): ViewGroup.LayoutParams? = layoutParams

    var clickListener: OnClickListener? = null
        private set
    var longClickListener: OnLongClickListener? = null
        private set
    var focusChangeListener: OnFocusChangeListener? = null
        private set

    // ── Recorded visual properties ───────────────────────────────────────
    @JvmField var background: Drawable? = null

    @JvmField var paddingLeft: Int = 0
    @JvmField var paddingTop: Int = 0
    @JvmField var paddingRight: Int = 0
    @JvmField var paddingBottom: Int = 0

    @JvmField var alpha: Float = 1f
    @JvmField var translationY: Float = 0f
    @JvmField var translationX: Float = 0f
    @JvmField var minHeight: Int = 0
    @JvmField var minWidth: Int = 0

    /** Walks this view (and children for groups) for a matching id. */
    fun findViewById(id: Int): View? {
        if (this.id == id) return this
        if (this is ViewGroup) {
            for (i in 0 until this.getChildCount()) {
                this.getChildAt(i)?.findViewById(id)?.let { return it }
            }
        }
        return null
    }

    fun setOnClickListener(listener: OnClickListener?) {
        clickListener = listener
    }

    fun setOnLongClickListener(listener: OnLongClickListener?) {
        longClickListener = listener
    }

    fun setOnFocusChangeListener(listener: OnFocusChangeListener?) {
        focusChangeListener = listener
    }

    fun setOnKeyListener(listener: OnKeyListener?) {}
    fun setOnTouchListener(listener: OnTouchListener?) {}

    fun setId(id: Int) {
        this.id = id
    }

    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        paddingLeft = left; paddingTop = top; paddingRight = right; paddingBottom = bottom
    }

    fun getPaddingLeft(): Int = paddingLeft
    fun getPaddingTop(): Int = paddingTop
    fun getPaddingRight(): Int = paddingRight
    fun getPaddingBottom(): Int = paddingBottom

    fun setBackgroundColor(color: Int) {
        background = android.graphics.drawable.ColorDrawable(color)
    }

    fun setBackground(background: Drawable?) {
        this.background = background
    }

    fun setBackgroundDrawable(background: Drawable?) {
        this.background = background
    }

    fun getBackground(): Drawable? = background

    fun setBackgroundTintList(tint: android.content.res.ColorStateList?) {}

    fun setAlpha(alpha: Float) { this.alpha = alpha }
    fun getAlpha(): Float = alpha

    fun setTranslationY(translationY: Float) { this.translationY = translationY }
    fun getTranslationY(): Float = translationY

    fun setTranslationX(translationX: Float) { this.translationX = translationX }
    fun getTranslationX(): Float = translationX

    fun setMinHeight(minHeight: Int) { this.minHeight = minHeight }
    fun setMinWidth(minWidth: Int) { this.minWidth = minWidth }

    fun invalidate() {}
    fun requestLayout() {}

    /** Android runs the listener; plugins rely on that for programmatic clicks. */
    fun performClick(): Boolean {
        clickListener?.onClick(this)
        return true
    }

    fun performLongClick(): Boolean {
        return longClickListener?.onLongClick(this) ?: false
    }

    fun bringToFront() {}
    fun requestFocus(): Boolean = true
    fun clearFocus() {}
    fun isFocused(): Boolean = false
    fun setClickable(clickable: Boolean) {}
    fun setFocusable(focusable: Boolean) {}
    fun setFocusableInTouchMode(focusable: Boolean) {}
    fun setSelected(selected: Boolean) {}
    fun isSelected(): Boolean = false

    /**
     * Plugins animate button presses etc. The shadow animator executes end
     * actions immediately so dependent logic (click handlers chained after a
     * scale-down) still runs.
     */
    fun animate(): ViewPropertyAnimator = ViewPropertyAnimator(this)

    fun getWidth(): Int = 0
    fun getHeight(): Int = 0
    fun setTag(tag: Any?) {}
    fun getTag(): Any? = null

    private var parentRef: ViewParent? = null

    fun getParent(): ViewParent? = parentRef

    fun setParent(parent: ViewParent?) {
        parentRef = parent
    }

    /** Views added to a group get their parent set — mirrors Android. */
    fun onAddedToParent(parent: ViewGroup?) {
        parentRef = parent
    }

    fun post(action: Runnable?): Boolean {
        action ?: return false
        // Keep inline semantics for synchronous chains but tolerate plugins
        // that post repeatedly (polling) by going through the main executor.
        android.os.Handler().post(action)
        return true
    }

    fun postDelayed(action: Runnable?, delayMillis: Long): Boolean {
        action ?: return false
        android.os.Handler().postDelayed(action, delayMillis)
        return true
    }

    fun removeCallbacks(action: Runnable?) {
        android.os.Handler().removeCallbacks(action)
    }

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8

        // Generated ids stay well below the 0x7f...... resource-id range
        private val nextGeneratedId = java.util.concurrent.atomic.AtomicInteger(0x100000)

        @JvmStatic
        fun generateViewId(): Int = nextGeneratedId.incrementAndGet()
    }
}

/** Records nothing; executes [withEndAction] chains on [start]. */
class ViewPropertyAnimator(@JvmField val view: View) {
    private var pending: MutableList<Runnable> = mutableListOf()

    fun scaleX(value: Float): ViewPropertyAnimator = this
    fun scaleY(value: Float): ViewPropertyAnimator = this
    fun translationY(value: Float): ViewPropertyAnimator = this
    fun translationX(value: Float): ViewPropertyAnimator = this
    fun alpha(value: Float): ViewPropertyAnimator = this
    fun setDuration(duration: Long): ViewPropertyAnimator = this
    fun setInterpolator(interpolator: android.animation.TimeInterpolator?): ViewPropertyAnimator = this
    fun withEndAction(action: Runnable?): ViewPropertyAnimator = apply { action?.let { pending.add(it) } }
    fun withStartAction(action: Runnable?): ViewPropertyAnimator = apply { action?.let { pending.add(it) } }

    fun start() {
        val actions = pending.toList()
        pending.clear()
        actions.forEach { it.run() }
    }

    fun cancel() {
        pending.clear()
    }
}

open class ViewGroup : View(), ViewParent {
    open class LayoutParams {
        @JvmField var width: Int = MATCH_PARENT
        @JvmField var height: Int = WRAP_CONTENT

        constructor(width: Int, height: Int) {
            this.width = width
            this.height = height
        }
        constructor(source: LayoutParams) {
            this.width = source.width
            this.height = source.height
        }
        constructor(c: Context?, attrs: Any?) {}

        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }

    /**
     * Margin params as on Android: public fields so plugin bytecode's PUTFIELD
     * accesses (topMargin = …) resolve, plus the start/end property setters.
     */
    open class MarginLayoutParams : LayoutParams {
        @JvmField var topMargin: Int = 0
        @JvmField var bottomMargin: Int = 0
        @JvmField var leftMargin: Int = 0
        @JvmField var rightMargin: Int = 0
        @JvmField var marginStart: Int = 0
        @JvmField var marginEnd: Int = 0

        constructor(width: Int, height: Int) : super(width, height)
        constructor(source: LayoutParams) : super(source)
        constructor(c: Context?, attrs: Any?) : super(c, attrs)

        fun setMargins(left: Int, top: Int, right: Int, bottom: Int) {
            leftMargin = left; topMargin = top; rightMargin = right; bottomMargin = bottom
        }

        fun setMarginStart(start: Int) { marginStart = start }
        fun setMarginEnd(end: Int) { marginEnd = end }
        fun getMarginStart(): Int = marginStart
        fun getMarginEnd(): Int = marginEnd
        fun getTopMargin(): Int = topMargin
    }

    @JvmField
    val children = mutableListOf<View>()

    fun addView(child: View?) {
        child?.let {
            children.add(it)
            it.onAddedToParent(this)
        }
        ShadowUi.bump()
    }

    fun addView(child: View?, index: Int) {
        child?.let {
            children.add(index.coerceIn(0, children.size), it)
            it.onAddedToParent(this)
        }
        ShadowUi.bump()
    }

    fun addView(child: View?, params: LayoutParams?) {
        child?.let {
            children.add(it)
            it.layoutParams = params
            it.onAddedToParent(this)
        }
        ShadowUi.bump()
    }

    fun removeView(view: View?) {
        children.remove(view)
        ShadowUi.bump()
    }

    fun removeAllViews() {
        children.clear()
        ShadowUi.bump()
    }

    fun getChildCount(): Int = children.size
    fun getChildAt(index: Int): View? = children.getOrNull(index)
}

class Window {
    fun setBackgroundDrawable(background: Drawable?) {}
    fun setLayout(width: Int, height: Int) {}
    fun setGravity(gravity: Int) {}
    fun setDimAmount(dim: Float) {}
    fun setSoftInputMode(mode: Int) {}
    fun requestFeature(featureId: Int): Boolean = false
    fun getAttributes(): Any? = null
    fun getDecorView(): View = View()
}

class LayoutInflater private constructor() {
    /** Resource-id → string, used to resolve @string refs inside AXML layouts. */
    var stringResolver: (Int) -> String? = { id ->
        com.cncverse.stremiobridge.shadowui.ShadowResourceRegistry.resolveString(id)
    }

    fun inflate(resource: Int, root: ViewGroup?): View = inflate(resource, root, false)

    fun inflate(resource: Int, root: ViewGroup?, attachToRoot: Boolean): View {
        // Resource-id inflation: resolve through the CALLING plugin's resources
        // (registered by PluginLoader when attaching PluginResources).
        val parser = com.cncverse.stremiobridge.shadowui.ShadowResourceRegistry.openLayout(resource) ?: return View()
        return inflate(parser as org.xmlpull.v1.XmlPullParser, root, attachToRoot) ?: View()
    }

    /**
     * Functional inflation over an AXML parser: builds the stub widget tree
     * with android:id values AND presentation attributes (text, textColor,
     * checked, orientation, padding…) attached, so plugin binding code can
     * findViewById and the renderer shows a faithful UI.
     */
    fun inflate(parser: org.xmlpull.v1.XmlPullParser?, root: ViewGroup?, attachToRoot: Boolean): View? {
        if (parser == null) return root
        return try {
            var current: View? = null
            // Balanced element stack: every START pushes, every END pops —
            // keeps groups in place while leaf views come and go.
            val stack = ArrayDeque<View>()
            var event = parser.eventType
            while (event != PullParser.END_DOCUMENT) {
                when (event) {
                    PullParser.START_TAG -> {
                        val view = createView(parser.name ?: "View", parser) ?: View()
                        applyAttributes(view, parser)
                        val parent = stack.lastOrNull() as? ViewGroup
                        if (parent != null) {
                            parent.addView(view)
                        } else if (current == null) {
                            current = view
                        }
                        stack.addLast(view)
                    }
                    PullParser.END_TAG -> {
                        if (stack.isNotEmpty()) stack.removeLast()
                    }
                }
                event = parser.next()
            }
            // Attach to root like Android does when requested
            val result: View? = if (attachToRoot && root != null && current != null) {
                root.addView(current)
                root
            } else {
                current
            }
            result ?: root
        } catch (t: Throwable) {
            root
        }
    }

    private fun createView(name: String, parser: org.xmlpull.v1.XmlPullParser): View? {
        // Custom views are fully-qualified (androidx.core.widget.NestedScrollView)
        // while framework widgets use short names (LinearLayout).
        val tag = name.substringAfterLast('.')
        val view: View = when (tag) {
            "TextView" -> android.widget.TextView()
            "EditText", "AutoCompleteTextView" -> android.widget.EditText()
            "CheckBox" -> android.widget.CheckBox(null)
            "Switch", "SwitchCompat" -> android.widget.Switch(null)
            "RadioButton" -> android.widget.RadioButton(null)
            "RadioGroup" -> android.widget.RadioGroup(null)
            "Button" -> android.widget.Button(null)
            "ImageButton" -> android.widget.ImageButton(null)
            "ImageView" -> android.widget.ImageView(null)
            "ProgressBar" -> android.widget.ProgressBar(null)
            "Space" -> android.widget.Space(null)
            "LinearLayout" -> android.widget.LinearLayout()
            "RelativeLayout" -> android.widget.RelativeLayout()
            "ScrollView", "NestedScrollView", "HorizontalScrollView" -> android.widget.ScrollView(null)
            "FrameLayout" -> android.widget.FrameLayout()
            "ListView", "RecyclerView" -> android.widget.ListView(null)
            else -> View()
        }
        return view
    }

    /** Applies presentation attributes recorded from the AXML tag. */
    private fun applyAttributes(view: View, parser: org.xmlpull.v1.XmlPullParser) {
        val attrs = parser as? android.util.AttributeSet ?: return
        val ns = "http://schemas.android.com/apk/res/android"

        // android:id is a resource reference (0x7f......) matching R.id constants
        view.id = attrs.getAttributeResourceValue(ns, "id", 0)
        if (view.id == 0) {
            view.id = attrs.getAttributeResourceValue(null, "id", 0)
        }

        fun attrString(name: String): String? {
            val v = attrs.getAttributeValue(ns, name) ?: return null
            if (v.startsWith("0x") || v.startsWith("@")) {
                // resource reference — resolve through the string table
                val resId = runCatching { v.removePrefix("@").substringAfter("/").toIntOrNull() ?: v.substring(2).toIntOrNull(16) }.getOrNull()
                return resId?.let { stringResolver(it) }
            }
            return v
        }

        when (view) {
            is android.widget.TextView -> {
                attrString("text")?.let { view.setText(it) }
                attrString("hint")?.let { view.setHint(it) }
                attrString("textColor")?.let { c ->
                    runCatching { view.setTextColor(android.graphics.Color.parseColor(c)) }
                }
                attrs.getAttributeValue(ns, "textSize")?.let { s ->
                    s.toFloatOrNull()?.let { view.setTextSize(it) }
                }
                if (attrs.getAttributeBooleanValue(ns, "textStyleBold", false) ||
                    attrs.getAttributeValue(ns, "textStyle") == "1"
                ) {
                    view.setTypeface(null, android.graphics.Typeface.BOLD)
                }
            }
            is android.widget.CompoundButton -> {
                view.isChecked = attrs.getAttributeBooleanValue(ns, "checked", false)
            }
            is android.widget.LinearLayout -> {
                view.orientation = attrs.getAttributeValue(ns, "orientation")?.toIntOrNull()
                    ?: android.widget.LinearLayout.VERTICAL
            }
        }

        // common geometry
        attrString("minHeight")?.let { it.toFloatOrNull()?.let { h -> view.minHeight = h.toInt() } }
        attrString("background")?.let { bg ->
            runCatching { view.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor(bg)) }
        }
    }

    companion object {
        @JvmStatic
        fun from(context: Context?): LayoutInflater = LayoutInflater()
    }
}
