package com.cncverse.stremiobridge.network

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Attributes HTTP traffic to the plugin that issued it (used by the slow-request log).
 *
 * Every plugin entry point (search/load/loadLinks/getMainPage) runs inside a
 * [PluginNetElement]; the element sets a thread-local on whichever thread the
 * coroutine (or any child coroutine the plugin launches) resumes on. OkHttp
 * creates a Call on that thread, so the call is tagged at creation
 * ([attributeCall]) and the tag survives into dispatcher threads where the
 * request actually executes.
 */
object NetContext {
    private val plugin = ThreadLocal<String?>()

    /** Fallback attribution (stack walk over plugin classloaders), installed by the desktop loader. */
    @Volatile
    var stackResolver: (() -> String?)? = null

    fun currentPlugin(): String? = plugin.get()

    /** Plugin for the current thread: coroutine context first, then a stack walk. */
    fun resolvePlugin(): String? = plugin.get() ?: runCatching { stackResolver?.invoke() }.getOrNull()

    data class Tag(val plugin: String?)

    private val callTags = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Tag>())

    /** Records the issuing plugin for an OkHttp call (called from EventListener.Factory.create). */
    fun attributeCall(call: Any) {
        resolvePlugin()?.let { callTags[call] = Tag(it) }
    }

    fun tagFor(call: Any): Tag? = callTags[call]

    fun forget(call: Any) { callTags.remove(call) }

    class PluginNetElement(val pluginName: String) :
        ThreadContextElement<String?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<PluginNetElement>

        override fun updateThreadContext(context: CoroutineContext): String? {
            val old = plugin.get(); plugin.set(pluginName); return old
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
            plugin.set(oldState)
        }
    }
}
