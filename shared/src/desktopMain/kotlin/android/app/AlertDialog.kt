package android.app

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import com.cncverse.stremiobridge.shadowui.ShadowDialog
import com.cncverse.stremiobridge.shadowui.ShadowUi

/**
 * android.app stubs — RECORDING implementation.
 *
 * AlertDialog.Builder captures everything a plugin sets (title, message,
 * custom view, buttons, item lists, listeners); show() pushes a [ShadowDialog]
 * that the Compose renderer displays. dismiss()/cancel() pop it. Plugin
 * listeners stored here are replayed by the renderer on user interaction.
 */
open class Activity : Context() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun isFinishing(): Boolean = false
    fun isDestroyed(): Boolean = false
    fun runOnUiThread(action: Runnable) { handler.post(action) }
    fun finish() {}
    fun getIntent(): android.content.Intent? = null
    override fun startActivity(intent: android.content.Intent?) {}
    fun getWindow(): android.view.Window? = null
    fun findViewById(id: Int): View? = null
    fun setContentView(view: View?) {}
    fun setRequestedOrientation(orientation: Int) {}
    fun recreate() {}
    fun invalidateOptionsMenu() {}
}

/** Base dialog — also the type DialogFragment.getDialog() returns. */
open class Dialog : DialogInterface {
    @JvmField var title: CharSequence? = null
    @JvmField var message: CharSequence? = null
    @JvmField var contentView: View? = null
    @JvmField var cancelable: Boolean = true

    @JvmField var positiveText: CharSequence? = null
    @JvmField var positiveListener: DialogInterface.OnClickListener? = null
    @JvmField var negativeText: CharSequence? = null
    @JvmField var negativeListener: DialogInterface.OnClickListener? = null
    @JvmField var neutralText: CharSequence? = null
    @JvmField var neutralListener: DialogInterface.OnClickListener? = null

    @JvmField var items: Array<CharSequence>? = null
    @JvmField var itemsListener: DialogInterface.OnClickListener? = null
    @JvmField var checkedItem: Int = -1
    @JvmField var isSingleChoice: Boolean = false
    @JvmField var isMultiChoice: Boolean = false
    @JvmField var checkedItems: BooleanArray? = null
    @JvmField var multiChoiceListener: DialogInterface.OnMultiChoiceClickListener? = null

    @JvmField var showListener: DialogInterface.OnShowListener? = null
    @JvmField var dismissListener: DialogInterface.OnDismissListener? = null
    @JvmField var cancelListener: DialogInterface.OnCancelListener? = null

    @JvmField var isShowingState: Boolean = false

    private val windowInstance = android.view.Window()

    fun getWindow(): android.view.Window = windowInstance

    fun isShowing(): Boolean = isShowingState

    fun setTitle(title: CharSequence?) { this.title = title }
    fun setTitle(titleId: Int) { this.title = null }
    fun setMessage(message: CharSequence?) { this.message = message }

    fun setContentView(view: View?) {
        contentView = view
    }

    fun setContentView(layoutResId: Int) {}
    fun setCancelable(cancelable: Boolean) { this.cancelable = cancelable }
    fun requestWindowFeature(featureId: Int): Boolean = false

    fun show() {
        if (isShowingState) return
        isShowingState = true
        ShadowUi.push(ShadowDialog(platform = this, view = contentView))
        showListener?.onShow(this)
    }

    override fun dismiss() {
        if (!isShowingState) return
        isShowingState = false
        ShadowUi.pop(this)
        dismissListener?.onDismiss(this)
    }

    override fun cancel() {
        if (!isShowingState) return
        isShowingState = false
        ShadowUi.pop(this)
        cancelListener?.onCancel(this)
        dismissListener?.onDismiss(this)
    }

    fun setOnShowListener(listener: DialogInterface.OnShowListener?) { showListener = listener }
    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) { dismissListener = listener }
    fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) { cancelListener = listener }

    private val buttonsById = mutableMapOf<Int, android.widget.Button>()

    /** Button lookup plugins use to restyle dialog buttons after show(). */
    fun getButton(whichButton: Int): android.widget.Button? =
        buttonsById.getOrPut(whichButton) { android.widget.Button(null) }

    fun setCanceledOnTouchOutside(cancel: Boolean) {}
}

class AlertDialog : Dialog() {
    private val listViewField = ListView(null)

    fun getListView(): ListView? = listViewField

    class Builder {
        private val dialog = AlertDialog()

        constructor(context: Context?)
        constructor(context: Context?, themeResId: Int)

        fun setTitle(title: CharSequence?): Builder = apply { dialog.title = title }
        fun setTitle(titleId: Int): Builder = apply { dialog.title = null }
        fun setMessage(message: CharSequence?): Builder = apply { dialog.message = message }
        fun setView(view: View?): Builder = apply { dialog.contentView = view }
        fun setView(layoutResId: Int): Builder = apply {}
        fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder =
            apply { dialog.positiveText = text; dialog.positiveListener = listener }

        fun setPositiveButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder =
            apply { dialog.positiveText = null; dialog.positiveListener = listener }

        fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder =
            apply { dialog.negativeText = text; dialog.negativeListener = listener }

        fun setNegativeButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder =
            apply { dialog.negativeText = null; dialog.negativeListener = listener }

        fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder =
            apply { dialog.neutralText = text; dialog.neutralListener = listener }

        fun setNeutralButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder =
            apply { dialog.neutralText = null; dialog.neutralListener = listener }

        fun setItems(items: Array<CharSequence>?, listener: DialogInterface.OnClickListener?): Builder =
            apply { dialog.items = items; dialog.itemsListener = listener }

        fun setItems(itemsId: Int, listener: DialogInterface.OnClickListener?): Builder = this

        fun setSingleChoiceItems(
            items: Array<CharSequence>?,
            checkedItem: Int,
            listener: DialogInterface.OnClickListener?,
        ): Builder = apply {
            dialog.items = items
            dialog.checkedItem = checkedItem
            dialog.isSingleChoice = true
            dialog.itemsListener = listener
        }

        fun setSingleChoiceItems(itemsId: Int, checkedItem: Int, listener: DialogInterface.OnClickListener?): Builder = this

        fun setMultiChoiceItems(
            items: Array<CharSequence>?,
            checkedItems: BooleanArray?,
            listener: DialogInterface.OnMultiChoiceClickListener?,
        ): Builder = apply {
            dialog.items = items
            dialog.checkedItems = checkedItems
            dialog.isMultiChoice = true
            dialog.multiChoiceListener = listener
        }

        fun setMultiChoiceItems(itemsId: Int, checkedItems: BooleanArray?, listener: DialogInterface.OnMultiChoiceClickListener?): Builder = this

        fun setCancelable(cancelable: Boolean): Builder = apply { dialog.cancelable = cancelable }
        fun setOnDismissListener(listener: DialogInterface.OnDismissListener?): Builder = apply { dialog.dismissListener = listener }
        fun setOnCancelListener(listener: DialogInterface.OnCancelListener?): Builder = apply { dialog.cancelListener = listener }
        fun setOnShowListener(listener: DialogInterface.OnShowListener?): Builder = apply { dialog.showListener = listener }

        fun create(): AlertDialog = dialog

        fun show(): AlertDialog = dialog.also { it.show() }
    }
}
