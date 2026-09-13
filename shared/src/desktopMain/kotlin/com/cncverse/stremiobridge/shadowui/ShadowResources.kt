package com.cncverse.stremiobridge.shadowui

/**
 * Resolves resource ids through the plugin that is currently executing (the
 * stack-walking attribution in PluginCallContext finds it even from inside
 * listeners). Registered by the desktop PluginLoader for every plugin that
 * ships resources.
 */
object ShadowResourceRegistry {
    private val pluginResources = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val lastResort = java.util.concurrent.atomic.AtomicReference<Any?>(null)

    fun register(pluginId: String, resources: Any) {
        pluginResources[pluginId] = resources
        lastResort.set(resources)
    }

    fun unregister(pluginId: String) {
        pluginResources.remove(pluginId)
    }

    fun current(): Any? {
        val caller = com.cncverse.stremiobridge.plugin.PluginCallContext.getCallingPluginName()
        return caller?.let { pluginResources[it] } ?: lastResort.get()
    }

    fun openLayout(resourceId: Int): org.xmlpull.v1.XmlPullParser? {
        val res = current() ?: return null
        return try {
            val method = res.javaClass.getMethod("openLayout", Int::class.java)
            method.invoke(res, resourceId) as? org.xmlpull.v1.XmlPullParser
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveString(resourceId: Int): String? {
        val res = current() ?: return null
        return try {
            val method = res.javaClass.getMethod("resolveString", Int::class.java)
            method.invoke(res, resourceId) as? String
        } catch (_: Throwable) {
            null
        }
    }
}
