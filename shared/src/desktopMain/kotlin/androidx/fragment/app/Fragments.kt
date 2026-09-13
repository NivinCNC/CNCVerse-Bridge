package androidx.fragment.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.cncverse.stremiobridge.shadowui.ShadowDialog
import com.cncverse.stremiobridge.shadowui.ShadowUi

/**
 * androidx.fragment stubs — RECORDING implementation.
 *
 * DialogFragment.show() executes the fragment lifecycle (onCreate →
 * onCreateView → onViewCreated → onStart) and records the resulting view as a
 * [ShadowDialog]. StreamPlay-style settings (fragment + AXML layouts) fully
 * render on desktop through this path.
 */
open class Fragment {
    private var viewField: View? = null

    open fun onCreateView(
        inflater: LayoutInflater?,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = null

    open fun onViewCreated(view: View?, savedInstanceState: Bundle?) {}
    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onStart() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onStop() {}
    open fun onDestroyView() {}
    open fun onDestroy() {}

    fun getView(): View? = viewField
    fun requireView(): View = viewField ?: View()

    /** Runs the fragment lifecycle without recording (used by plain fragments). */
    protected fun runLifecycle(record: Boolean, tag: String?) {
        runCatching {
            onCreate(null)
            val view = onCreateView(LayoutInflater.from(null), null, null)
            viewField = view
            com.cncverse.stremiobridge.state.ServerState.info(
                "Fragment lifecycle: ${this::class.simpleName} inflated ${view?.javaClass?.simpleName ?: "no view"}"
            )
            onViewCreated(view, null)
            if (record) {
                ShadowUi.push(ShadowDialog(platform = this, view = view, fromFragment = true, fragmentTag = tag))
            }
            onStart()
            onResume()
        }.onFailure {
            com.cncverse.stremiobridge.state.ServerState.warn(
                "Fragment lifecycle ended early: ${it::class.simpleName}: ${it.message}"
            )
        }
    }

    // ── DialogFragment-style members (bottom-sheet subclasses use show too) ──
    open fun show(manager: FragmentManager?, tag: String?) {
        runLifecycle(record = true, tag = tag)
    }

    fun show(transaction: FragmentTransaction?, tag: String?): Int {
        show(null as FragmentManager?, tag)
        return 0
    }

    open fun dismissAllowingStateLoss() {
        ShadowUi.pop(this)
    }

    open fun dismiss() {
        ShadowUi.pop(this)
    }

    fun requireActivity(): FragmentActivity = hostActivity
    fun requireContext(): android.content.Context = android.content.DesktopContext
    fun getActivity(): FragmentActivity? = hostActivity
    fun getContext(): android.content.Context? = android.content.DesktopContext
    fun getChildFragmentManager(): FragmentManager = FragmentManager()
    fun getParentFragmentManager(): FragmentManager = FragmentManager()
    fun isAdded(): Boolean = true

    fun getResources(): android.content.res.Resources =
        com.cncverse.stremiobridge.shadowui.ShadowResourceRegistry.current() as? android.content.res.Resources
            ?: android.content.res.Resources()

    fun getString(resId: Int): String =
        com.cncverse.stremiobridge.shadowui.ShadowResourceRegistry.resolveString(resId) ?: ""

    companion object {
        /** The single host activity handed to plugins via getActivity(). */
        @JvmField
        val hostActivity: FragmentActivity = FragmentActivity()
    }
}

/**
 * DialogFragment: show() runs the lifecycle AND records the dialog; getDialog()
 * returns a real android.app.Dialog so onStart window-sizing code works.
 */
open class DialogFragment : Fragment() {
    @JvmField
    protected var dialogField: android.app.Dialog? = null

    fun getDialog(): android.app.Dialog? = dialogField

    override fun show(manager: FragmentManager?, tag: String?) {
        dialogField = android.app.Dialog()
        super.show(manager, tag)
    }

    override fun dismiss() {
        dialogField?.dismiss()
        ShadowUi.pop(this)
    }

    override fun dismissAllowingStateLoss() {
        dialogField?.dismiss()
        ShadowUi.pop(this)
    }

    open fun onCancel(dialog: android.content.DialogInterface?) {}
}

/** Bottom sheet flavor — renders as a sheet in the Compose renderer. */
open class BottomSheetDialogFragment : DialogFragment() {
    fun getBottomSheetBehavior(): Any? = null
}

/** androidx.fragment.app.FragmentActivity (extends AppCompatActivity). */
open class FragmentActivity : androidx.appcompat.app.AppCompatActivity() {
    fun getSupportFragmentManagerCompat(): FragmentManager = FragmentManager()
    fun supportFragmentManager(): FragmentManager = FragmentManager()
}

open class FragmentManager {
    fun beginTransaction(): FragmentTransaction = FragmentTransaction()
    fun executePendingTransactions(): Boolean = false
    fun findFragmentById(id: Int): Fragment? = null
    fun findFragmentByTag(tag: String?): Fragment? = null
}

open class FragmentTransaction {
    fun add(id: Int, fragment: Fragment?): FragmentTransaction = this
    fun add(fragment: Fragment?, tag: String?): FragmentTransaction = this
    fun replace(id: Int, fragment: Fragment?): FragmentTransaction = this
    fun remove(fragment: Fragment?): FragmentTransaction = this
    fun show(fragment: Fragment?): FragmentTransaction = this
    fun hide(fragment: Fragment?): FragmentTransaction = this
    fun addToBackStack(name: String?): FragmentTransaction = this
    fun commit(): Int = 0
    fun commitAllowingStateLoss(): Int = 0
    fun commitNow() {}
}
