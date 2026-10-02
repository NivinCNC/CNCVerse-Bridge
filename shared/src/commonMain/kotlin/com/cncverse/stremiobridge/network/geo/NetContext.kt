package com.cncverse.stremiobridge.network.geo

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** How a plugin call's HTTP traffic may be routed (set by health probes). */
enum class RouteMode { AUTO, FORCE_DIRECT, FORCE_PROXY }

/**
 * Attributes HTTP traffic to the plugin that issued it.
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
    private val mode = ThreadLocal<RouteMode?>()

    /** Fallback attribution (stack walk over plugin classloaders), installed by the desktop loader. */
    @Volatile
    var stackResolver: (() -> String?)? = null

    fun currentPlugin(): String? = plugin.get()
    fun currentMode(): RouteMode = mode.get() ?: RouteMode.AUTO

    /** Plugin for the current thread: coroutine context first, then a stack walk. */
    fun resolvePlugin(): String? = plugin.get() ?: runCatching { stackResolver?.invoke() }.getOrNull()

    data class Tag(val plugin: String?, val mode: RouteMode)

    private val callTags = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Tag>())

    /** Records the issuing plugin for an OkHttp call (called from EventListener.Factory.create). */
    fun attributeCall(call: Any) {
        val p = resolvePlugin()
        val m = currentMode()
        if (p != null || m != RouteMode.AUTO) callTags[call] = Tag(p, m)
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

    class RouteModeElement(val routeMode: RouteMode) :
        ThreadContextElement<RouteMode?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RouteModeElement>

        override fun updateThreadContext(context: CoroutineContext): RouteMode? {
            val old = mode.get(); mode.set(routeMode); return old
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: RouteMode?) {
            mode.set(oldState)
        }
    }
}
