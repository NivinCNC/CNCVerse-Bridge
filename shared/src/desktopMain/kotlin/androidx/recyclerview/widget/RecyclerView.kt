package androidx.recyclerview.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.cncverse.stremiobridge.shadowui.ShadowUi

/**
 * RecyclerView shadow — recording implementation.
 *
 * StreamPlay's settings use RecyclerView + Adapter for its language list and
 * saved-links list. The renderer materializes rows by calling the plugin's
 * own onCreateViewHolder/onBindViewHolder, so rows are built with the exact
 * plugin code that builds them on Android.
 */
open class RecyclerView : ViewGroup {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: Any?) : super()

    @JvmField var adapter: Adapter<*>? = null
    @JvmField var layoutManager: LayoutManager? = null

    fun setAdapter(adapter: Adapter<*>?) {
        this.adapter = adapter
        ShadowUi.bump()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : ViewHolder?> findViewHolderForAdapterPosition(position: Int): T? = null

    fun getAdapter(): Adapter<*>? = adapter

    fun setLayoutManager(manager: LayoutManager?) {
        layoutManager = manager
    }

    fun setHasFixedSize(hasFixedSize: Boolean) {}
    fun addItemDecoration(decoration: Any?) {}
    fun scrollToPosition(position: Int) {}
    fun smoothScrollToPosition(position: Int) {}
    fun setItemAnimator(animator: Any?) {}
    fun setNestedScrollingEnabled(enabled: Boolean) {}
    fun invalidateItemDecorations() {}

    abstract class Adapter<VH : ViewHolder> {
        abstract fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH
        abstract fun onBindViewHolder(holder: VH, position: Int)
        abstract fun getItemCount(): Int

        open fun getItemViewType(position: Int): Int = 0
        open fun getItemId(position: Int): Long = position.toLong()

        open fun notifyDataSetChanged() {
            ShadowUi.bump()
        }

        open fun notifyItemChanged(position: Int) {
            ShadowUi.bump()
        }

        open fun notifyItemInserted(position: Int) {
            ShadowUi.bump()
        }

        open fun notifyItemRemoved(position: Int) {
            ShadowUi.bump()
        }

        open fun registerAdapterDataObserver(observer: Any?) {}
        open fun unregisterAdapterDataObserver(observer: Any?) {}
        open fun onAttachedToRecyclerView(recyclerView: RecyclerView?) {}
        open fun onDetachedFromRecyclerView(recyclerView: RecyclerView?) {}
    }

    open class ViewHolder(@JvmField val itemView: View) {
        @JvmField var itemId: Long = -1
        @JvmField var itemViewType: Int = 0
        fun getAdapterPosition(): Int = -1
        fun getBindingAdapterPosition(): Int = -1
        fun setIsRecyclable(recyclable: Boolean) {}
    }

    abstract class LayoutManager {
        open fun onLayoutChildren(recycler: Any?, state: Any?) {}
    }
}

class LinearLayoutManager : RecyclerView.LayoutManager {
    constructor(context: Context?)
    constructor(context: Context?, orientation: Int, reverseLayout: Boolean)
    constructor()

    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}

class GridLayoutManager : RecyclerView.LayoutManager {
    constructor(context: Context?, spanCount: Int)
    constructor(context: Context?, spanCount: Int, orientation: Int, reverseLayout: Boolean)
}

class DividerItemDecoration(context: Context?, orientation: Int) {
    fun setDrawable(drawable: Any?) {}
}
