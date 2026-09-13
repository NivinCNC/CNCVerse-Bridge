package android.view

/** Test helper: walks the current ShadowUi dialog trees for clickables. */
object ShadowClickHelper {
    /**
     * Invokes [predicate] with every VISIBLE clickable view of every shown
     * dialog (depth-first). Stops when the predicate returns true.
     */
    fun findNthClickable(predicate: (View) -> Boolean) {
        fun walk(v: View): Boolean {
            if (v.visibility != View.VISIBLE) return false
            if (v.clickListener != null && predicate(v)) return true
            if (v is ViewGroup) {
                for (i in 0 until v.getChildCount()) {
                    if (v.getChildAt(i)?.let { walk(it) } == true) return true
                }
            }
            return false
        }
        for (dialog in com.cncverse.stremiobridge.shadowui.ShadowUi.dialogs.value) {
            val view = dialog.view ?: continue
            if (walk(view)) return
        }
    }
}
