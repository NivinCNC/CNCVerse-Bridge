package android.content

/**
 * No-op android.content.Intent stub (verification target for plugin classes).
 * Stores action/extras so non-UI code paths that read them don't crash.
 */
class Intent {
    var action: String? = null
    var data: android.net.Uri? = null
    private val extras = mutableMapOf<String, Any?>()

    constructor()
    constructor(action: String?) { this.action = action }
    constructor(context: Context?, cls: Class<*>?)
    constructor(src: Intent) {
        action = src.action
        data = src.data
        extras.putAll(src.extras)
    }

    fun setAction(action: String?): Intent = this.also { it.action = action }
    fun setData(data: android.net.Uri?): Intent = this.also { it.data = data }
    fun getDataString(): String? = data?.toString()
    fun putExtra(name: String, value: String?): Intent = this.also { extras[name] = value }
    fun putExtra(name: String, value: Int): Intent = this.also { extras[name] = value }
    fun putExtra(name: String, value: Long): Intent = this.also { extras[name] = value }
    fun putExtra(name: String, value: Boolean): Intent = this.also { extras[name] = value }
    fun putExtra(name: String, value: CharSequence?): Intent = this.also { extras[name] = value }
    fun putExtra(name: String, value: android.os.Bundle?): Intent = this.also { extras[name] = value }
    fun getStringExtra(name: String): String? = extras[name] as? String
    fun getIntExtra(name: String, defaultValue: Int): Int = extras[name] as? Int ?: defaultValue
    fun getBooleanExtra(name: String, defaultValue: Boolean): Boolean = extras[name] as? Boolean ?: defaultValue
    fun hasExtra(name: String): Boolean = extras.containsKey(name)
    fun setFlags(flags: Int): Intent = this
    fun addFlags(flags: Int): Intent = this
    fun addCategory(category: String): Intent = this

    override fun toString(): String = "Intent{action=$action data=$data extras=${extras.keys}}"
}

class ComponentName(val pkg: String, val cls: String) {
    override fun equals(other: Any?): Boolean = other is ComponentName && other.pkg == pkg && other.cls == cls
    override fun hashCode(): Int = pkg.hashCode() * 31 + cls.hashCode()
    override fun toString(): String = "$pkg/$cls"
}

class ClipData(val label: CharSequence?) {
    class Item(@JvmField val text: CharSequence?, @JvmField val htmlText: String? = null) {
        fun coerceToText(context: Context?): CharSequence? = text
    }

    private val items = mutableListOf<Item>()
    val itemCount: Int get() = items.size

    fun addItem(item: Item) { items.add(item) }
    fun getItemAt(index: Int): Item = items[index]

    companion object {
        fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData =
            ClipData(label).apply { addItem(Item(text)) }
    }
}

/** Wired to the desktop AWT clipboard so plugins can copy tokens. */
class ClipboardManager {
    @JvmField var primaryClip: ClipData? = null

    fun setPrimaryClip(clip: ClipData?) {
        primaryClip = clip
        try {
            val text = clip?.getItemAt(0)?.text?.toString() ?: return
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                java.awt.datatransfer.StringSelection(text), null
            )
        } catch (_: Throwable) {
        }
    }

    fun hasPrimaryClip(): Boolean = primaryClip != null
    fun clearPrimaryClip() { primaryClip = null }
}
