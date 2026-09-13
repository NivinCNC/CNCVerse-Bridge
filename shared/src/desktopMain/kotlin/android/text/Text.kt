package android.text

/** android.text stubs needed by plugin settings text fields. */
interface TextWatcher {
    fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int)
    fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int)
    fun afterTextChanged(s: Editable?)
}

interface Editable : CharSequence {
    override fun toString(): String
    fun replace(st: Int, en: Int, source: CharSequence?, start: Int, end: Int): Editable
    fun insert(where: Int, text: CharSequence?): Editable
    fun delete(st: Int, en: Int): Editable
    fun append(text: CharSequence?): Editable
    fun clear()
}

/** Common input-type constants plugins use. */
object InputType {
    const val TYPE_NULL = 0
    const val TYPE_CLASS_TEXT = 1
    const val TYPE_TEXT_VARIATION_PASSWORD = 128
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 144
    const val TYPE_TEXT_VARIATION_URI = 16
    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 32
    const val TYPE_TEXT_FLAG_MULTI_LINE = 131072
    const val TYPE_CLASS_NUMBER = 2
    const val TYPE_NUMBER_VARIATION_NORMAL = 0
    const val TYPE_NUMBER_FLAG_DECIMAL = 8192
    const val TYPE_NUMBER_FLAG_SIGNED = 4096
    const val TYPE_CLASS_PHONE = 3

    @JvmStatic
    fun parseText() = 1
}

/** Editable factory helpers some plugins call. */
object Editables {
    @JvmStatic
    fun newEditable(source: CharSequence?): Editable =
        android.widget.ShadowEditable(source ?: "")
}
