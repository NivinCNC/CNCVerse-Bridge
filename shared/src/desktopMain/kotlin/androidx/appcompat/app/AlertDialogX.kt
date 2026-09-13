package androidx.appcompat.app

import android.content.Context
import android.content.DialogInterface
import android.view.View
import com.cncverse.stremiobridge.shadowui.ShadowDialog
import com.cncverse.stremiobridge.shadowui.ShadowUi

/**
 * androidx AlertDialog — RECORDING implementation (distinct class from
 * android.app.AlertDialog, same captured state). Plugins that build their
 * settings with the appcompat builder render identically on desktop.
 */
open class AlertDialog : DialogInterface {
    @JvmField var title: CharSequence? = null
    @JvmField var message: CharSequence? = null
    @JvmField var view: View? = null
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

    fun isShowing(): Boolean = isShowingState

    fun show() {
        if (isShowingState) return
        isShowingState = true
        ShadowUi.push(ShadowDialog(platform = this, view = view))
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

    fun setCancelable(cancelable: Boolean) { this.cancelable = cancelable }
    fun getWindow(): android.view.Window? = null
    fun getListView(): android.widget.ListView? = null

    class Builder {
        private val dialog = AlertDialog()

        constructor(context: Context?)
        constructor(context: Context?, themeResId: Int)

        fun setTitle(title: CharSequence?): Builder = apply { dialog.title = title }
        fun setTitle(titleId: Int): Builder = apply { dialog.title = null }
        fun setMessage(message: CharSequence?): Builder = apply { dialog.message = message }
        fun setView(view: View?): Builder = apply { dialog.view = view }
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

        fun setCancelable(cancelable: Boolean): Builder = apply { dialog.cancelable = cancelable }
        fun setOnDismissListener(listener: DialogInterface.OnDismissListener?): Builder = apply { dialog.dismissListener = listener }
        fun setOnCancelListener(listener: DialogInterface.OnCancelListener?): Builder = apply { dialog.cancelListener = listener }
        fun setOnShowListener(listener: DialogInterface.OnShowListener?): Builder = apply { dialog.showListener = listener }

        fun create(): AlertDialog = dialog
        fun show(): AlertDialog = dialog.also { it.show() }
    }
}
