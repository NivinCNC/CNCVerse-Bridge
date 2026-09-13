package android.widget

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import com.cncverse.stremiobridge.shadowui.ShadowUi

/**
 * android.widget stubs — RECORDING implementation.
 *
 * Every property a plugin sets (text, colors, checked state, adapters,
 * listeners) is stored on the stub object. The desktop Compose renderer walks
 * these live objects; plugin-side mutations bump [ShadowUi.version] so the
 * renderer refreshes. Methods the stub doesn't have are neutered by the
 * bytecode transformer, so unknown APIs degrade gracefully.
 */
open class TextView : View {
    constructor() : super()
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    @JvmField var text: CharSequence = ""

    fun getText(): CharSequence = text

    fun setText(text: CharSequence?) {
        this.text = text ?: ""
        ShadowUi.bump()
    }

    fun setText(resId: Int) {
        this.text = ShadowStrings.resolve(resId)
    }

    @JvmField var hint: CharSequence = ""

    fun setHint(hint: CharSequence?) {
        this.hint = hint ?: ""
    }

    fun getHint(): CharSequence = hint

    @JvmField var textColor: Int = android.graphics.Color.WHITE
    @JvmField var hintTextColor: Int = 0xFF9E9E9E.toInt()
    @JvmField var textSizePx: Float = 14f * 2f
    @JvmField var bold: Boolean = false
    @JvmField var italic: Boolean = false
    @JvmField var allCaps: Boolean = false
    @JvmField var gravity: Int = 0
    @JvmField var maxLines: Int = Int.MAX_VALUE
    @JvmField var singleLine: Boolean = false
    @JvmField var letterSpacing: Float = 0f

    fun setTextColor(color: Int) { textColor = color }
    fun setHintTextColor(color: Int) { hintTextColor = color }
    fun getTextColor(): Int = textColor
    fun getHintTextColor(): Int = hintTextColor

    fun setTextSize(size: Float) {
        // Android setTextSize takes SP; record as px with density 2.0
        textSizePx = size * 2.0f
    }

    fun getTextSize(): Float = textSizePx / 2.0f

    fun setTypeface(tf: Typeface?, style: Int) {
        bold = style and 1 != 0
        italic = style and 2 != 0
    }

    fun setTypeface(tf: Typeface?) {
        if (tf?.isBold == true) bold = true
    }

    fun setGravity(gravity: Int) { this.gravity = gravity }
    fun getGravity(): Int = gravity
    fun setSingleLine() { singleLine = true; maxLines = 1 }
    fun setMaxLines(maxLines: Int) { this.maxLines = maxLines }
    fun setAllCaps(allCaps: Boolean) { this.allCaps = allCaps }
    fun setLetterSpacing(letterSpacing: Float) { this.letterSpacing = letterSpacing }
    fun setEllipsize(where: Any?) { maxLines = 1 }
    fun setLineSpacing(add: Float, mult: Float) {}
    fun setTextAlignment(alignment: Int) {}
}

open class EditText : TextView {
    constructor() : super()
    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: Any?) : super(context, attrs)

    @JvmField var inputType: Int = 0

    fun setRawInputType(type: Int) { inputType = type }
    fun setInputType(type: Int) { inputType = type }
    fun getInputType(): Int = inputType
    fun setFilters(filters: Array<Any>) {}

    // ── TextWatcher support (plugins validate/save on text change) ───────
    @JvmField
    val watchers = mutableListOf<TextWatcher>()

    fun addTextChangedListener(watcher: TextWatcher?) {
        watcher?.let { watchers.add(it) }
    }

    fun removeTextChangedListener(watcher: TextWatcher?) {
        watchers.remove(watcher)
    }

    /** Sets the text AND fires watchers — mirrors Android's setText behavior. */
    fun programmaticText(value: CharSequence?) {
        val old = text
        text = value ?: ""
        fireWatchers(old, text)
    }

    fun fireWatchers(old: CharSequence, next: CharSequence) {
        val editable = ShadowEditable(next)
        watchers.forEach { w ->
            runCatching {
                w.beforeTextChanged(old, 0, old.length, next.length)
                w.onTextChanged(next, 0, next.length, old.length)
                w.afterTextChanged(editable)
            }
        }
    }

    fun setSelection(index: Int) {}
    fun setSelection(start: Int, stop: Int) {}
    fun selectAll() {}
    fun setSelectAllOnFocus(value: Boolean) {}
    fun getTextValue(): String = text.toString()
}

/** Simple Editable implementation passed to afterTextChanged. */
class ShadowEditable(private var value: CharSequence) : Editable {
    override val length: Int get() = value.length
    override fun get(index: Int): Char = value[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = value.subSequence(startIndex, endIndex)
    override fun toString(): String = value.toString()
    override fun replace(st: Int, en: Int, source: CharSequence?, start: Int, end: Int): Editable = this
    override fun insert(where: Int, text: CharSequence?): Editable = this
    override fun delete(st: Int, en: Int): Editable = this
    override fun append(text: CharSequence?): Editable = this
    override fun clear() { value = "" }
}

open class CompoundButton : TextView() {
    var isChecked: Boolean = false
        set(value) {
            field = value
            ShadowUi.bump()
            listener?.onCheckedChanged(this, value)
        }

    private var listener: OnCheckedChangeListener? = null

    fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) { this.listener = listener }
    fun getOnCheckedChangeListener(): OnCheckedChangeListener? = listener

    /** Sets state WITHOUT firing the listener (for renderer-driven display). */
    fun setCheckedSilently(checked: Boolean) {
        val l = listener
        listener = null
        try {
            isChecked = checked
        } finally {
            listener = l
        }
    }

    fun toggle() { isChecked = !isChecked }

    fun setButtonTintList(tint: android.content.res.ColorStateList?) {}
    fun setTrackTintList(tint: android.content.res.ColorStateList?) {}
    fun setThumbTintList(tint: android.content.res.ColorStateList?) {}
    fun setThumbResource(resId: Int) {}

    interface OnCheckedChangeListener {
        fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean)
    }
}

class CheckBox : CompoundButton {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()
}

class RadioButton : CompoundButton {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()
}

class Switch : CompoundButton {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()
}

open class Button : TextView {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()
}

class ImageButton : View {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    @JvmField var imageDrawable: android.graphics.drawable.Drawable? = null
    @JvmField var imageResource: Int = 0

    fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        imageDrawable = drawable
    }

    fun setImageResource(resId: Int) { imageResource = resId }
}

open class ImageView : View {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    @JvmField var imageDrawable: android.graphics.drawable.Drawable? = null
    @JvmField var imageResource: Int = 0
    @JvmField var scaleType: String? = null

    fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        imageDrawable = drawable
    }

    fun setImageResource(resId: Int) { imageResource = resId }
    fun setScaleType(scaleType: Any?) {}
    fun setImageTintList(tint: android.content.res.ColorStateList?) {}
}

open class ProgressBar : View {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    @JvmField var progress: Int = 0
    @JvmField var max: Int = 100

    fun setProgress(progress: Int) { this.progress = progress }
    fun getProgress(): Int = progress
    fun setMax(max: Int) { this.max = max }
    fun getMax(): Int = max
    fun setProgressTintList(tint: android.content.res.ColorStateList?) {}
    fun isIndeterminate(): Boolean = false
    fun setIndeterminate(indeterminate: Boolean) {}
}

open class LinearLayout : ViewGroup {
    constructor() : super()
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    @JvmField var orientation: Int = HORIZONTAL

    fun setOrientation(orientation: Int) {
        this.orientation = orientation
        ShadowUi.bump()
    }

    fun getOrientation(): Int = orientation

    fun setWeightSum(weightSum: Float) {}

    class LayoutParams : ViewGroup.MarginLayoutParams {
        @JvmField var weight: Float = 0f
        @JvmField var gravity: Int = 0

        constructor(width: Int, height: Int) : super(width, height)
        constructor(width: Int, height: Int, weight: Float) : super(width, height) { this.weight = weight }
        constructor(source: LayoutParams) : super(source) { weight = source.weight; gravity = source.gravity }
        constructor(c: Context?, attrs: Any?) : super(c, attrs)
    }

    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}

open class RelativeLayout : ViewGroup {
    constructor() : super()
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    class LayoutParams : ViewGroup.MarginLayoutParams {
        constructor(width: Int, height: Int) : super(width, height)
        constructor(c: Context?, attrs: Any?) : super(c, attrs)
        fun addRule(rule: Int) {}
        fun addRule(verb: Int, subject: Int) {}
    }

    companion object {
        const val TRUE = -1
        const val ABOVE = 2
        const val BELOW = 3
        const val ALIGN_BASELINE = 4
        const val ALIGN_START = 17
        const val ALIGN_END = 18
        const val ALIGN_TOP = 6
        const val ALIGN_BOTTOM = 8
        const val ALIGN_PARENT_TOP = 10
        const val ALIGN_PARENT_BOTTOM = 12
        const val CENTER_IN_PARENT = 13
        const val CENTER_HORIZONTAL = 14
        const val CENTER_VERTICAL = 15
        const val START_OF = 16
        const val END_OF = 19
        const val ALIGN_PARENT_START = 20
        const val ALIGN_PARENT_END = 21
    }
}

open class ScrollView : ViewGroup {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    fun setFillViewport(fillViewport: Boolean) {}
    fun setScrollbarFadingEnabled(fading: Boolean) {}
    fun setSmoothScrollingEnabled(enabled: Boolean) {}
    fun smoothScrollTo(x: Int, y: Int) {}
    fun scrollTo(x: Int, y: Int) {}
    fun setDescendantFocusability(focusability: Int) {}
    fun requestChildFocus(child: View?, focused: View?) {}
}

/**
 * ListView records an adapter OR a plugin-supplied item list. The renderer
 * materializes rows by calling the adapter's getView (real plugin code), so
 * adapter-driven settings lists render exactly like on Android.
 */
open class ListView : AdapterView<Adapter> {
    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: Any?) : super(context, attrs)

    @JvmField var adapter: Adapter? = null
    @JvmField var itemClickListener: AdapterView.OnItemClickListener? = null
    @JvmField var choiceMode: Int = 0
    @JvmField var checkedItem: Int = -1

    override fun setAdapter(adapter: Adapter?) {
        this.adapter = adapter
        ShadowUi.bump()
    }

    override fun getAdapter(): Adapter? = adapter
    fun setDividerHeight(height: Int) {}
    fun setChoiceMode(choiceMode: Int) { this.choiceMode = choiceMode }
    fun setSelection(position: Int) { checkedItem = position }
    fun getCheckedItemPosition(): Int = checkedItem
    fun getCheckedItemIds(): LongArray = LongArray(0)

    fun setOnItemClickListener(listener: AdapterView.OnItemClickListener?) {
        itemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: AdapterView.OnItemLongClickListener?) {}
    fun setOnItemSelectedListener(listener: AdapterView.OnItemSelectedListener?) {}

    fun performItemClick(position: Int) {
        itemClickListener?.onItemClick(
            this,
            adapter?.getView(position, null, this),
            position,
            adapter?.getItemId(position) ?: position.toLong(),
        )
    }
}

/** Base adapter mirroring the Android contract plugins compile against. */
abstract class Adapter {
    abstract fun getCount(): Int
    abstract fun getItem(position: Int): Any?
    abstract fun getItemId(position: Int): Long
    abstract fun getView(position: Int, convertView: View?, parent: ViewGroup): View?

    open fun getItemViewType(position: Int): Int = 0
    open fun getViewTypeCount(): Int = 1
    open fun isEmpty(): Boolean = getCount() == 0
    open fun notifyDataSetChanged() {}
    open fun registerDataSetObserver(observer: Any?) {}
    open fun unregisterDataSetObserver(observer: Any?) {}
}

/** Concrete adapter plugins commonly subclass or use directly. */
open class BaseAdapter : Adapter() {
    override fun getCount(): Int = 0
    override fun getItem(position: Int): Any? = null
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View? = null
}

class ArrayAdapter<T>(
    private val context: Context?,
    private val resource: Int = 0,
    private val objects: List<T> = emptyList(),
) : BaseAdapter() {
    override fun getCount(): Int = objects.size
    override fun getItem(position: Int): T? = objects.getOrNull(position)
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View? =
        TextView(context).apply { text = getItem(position)?.toString() ?: "" }
}

/** Marker supertype for AdapterView hierarchy referenced in listener signatures. */
abstract class AdapterView<T : Adapter> : ViewGroup {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    interface OnItemClickListener {
        fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long)
    }

    interface OnItemLongClickListener {
        fun onItemLongClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long): Boolean
    }

    interface OnItemSelectedListener {
        fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long)
        fun onNothingSelected(parent: AdapterView<*>?)
    }

    abstract fun getAdapter(): T?
    abstract fun setAdapter(adapter: T?)
    fun getSelectedItem(): Any? = null
    fun getSelectedItemPosition(): Int = -1
    fun getCount(): Int = getAdapter()?.getCount() ?: 0
    fun setEmptyView(view: View?) {}
    fun getItemAtPosition(position: Int): Any? = getAdapter()?.getItem(position)
}

open class FrameLayout : ViewGroup {
    constructor() : super()
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    class LayoutParams : ViewGroup.MarginLayoutParams {
        @JvmField var gravity: Int = 0
        constructor(width: Int, height: Int) : super(width, height)
        constructor(source: LayoutParams) : super(source) { gravity = source.gravity }
        constructor(c: Context?, attrs: Any?) : super(c, attrs)
    }
}

/** Gaps/spacers used by layouts. Renders as flexible space. */
class Space : View {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()
}

/** Radio container; records the checked-change listener. */
class RadioGroup : LinearLayout {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {
        this.listener = listener
    }

    private var listener: OnCheckedChangeListener? = null

    fun getCheckedRadioButtonId(): Int =
        children.filterIsInstance<RadioButton>().firstOrNull { it.isChecked }?.id ?: -1

    fun check(id: Int) {
        children.filterIsInstance<RadioButton>().forEach { it.setCheckedSilently(it.id == id) }
        listener?.onCheckedChanged(this, id)
    }

    fun clearCheck() {
        children.filterIsInstance<RadioButton>().forEach { it.setCheckedSilently(false) }
        listener?.onCheckedChanged(this, -1)
    }

    interface OnCheckedChangeListener {
        fun onCheckedChanged(group: RadioGroup?, checkedId: Int)
    }
}

class Toast private constructor() {
    private var duration = LENGTH_SHORT
    private var text: CharSequence = ""

    fun setDuration(duration: Int) { this.duration = duration }
    fun setText(text: CharSequence) { this.text = text }
    fun setGravity(gravity: Int, xOffset: Int, yOffset: Int) {}
    fun show() {
        com.cncverse.stremiobridge.state.ServerState.info("[Toast] $text")
        ShadowUi.toast(text.toString())
    }
    fun cancel() {}

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence?, duration: Int): Toast =
            Toast().apply { this.text = text ?: ""; this.duration = duration }

        @JvmStatic
        fun makeText(context: Context?, resId: Int, duration: Int): Toast =
            Toast().apply { this.text = ShadowStrings.resolve(resId); this.duration = duration }
    }
}

/** Resolves android R.string references through the calling plugin's table. */
internal object ShadowStrings {
    fun resolve(resId: Int): String =
        com.cncverse.stremiobridge.shadowui.ShadowResourceRegistry.resolveString(resId) ?: ""
}

/** Editable factory used by android.text.Editables. */
internal object ShadowEditableFactory {
    fun create(source: CharSequence): Editable = ShadowEditable(source)
}
