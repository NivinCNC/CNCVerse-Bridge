package android.content

import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal functional android.content.Context for the desktop JVM.
 *
 * Plugin bytecode references android classes; the JVM verifies whole classes
 * on load, so every android type a plugin touches must exist on the classpath.
 * This Context also backs a real file-based SharedPreferences so plugins that
 * persist tokens/settings via prefs keep working on desktop.
 */
open class Context {
    open fun getApplicationContext(): Context = this
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        DesktopPreferences.get(name)

    open fun startActivity(intent: Intent?) {}
    open fun startService(intent: Intent?): ComponentName? = null
    open fun getFilesDir(): File = File(System.getProperty("user.home"), ".cncverse_bridge")
    open fun getCacheDir(): File = File(getFilesDir(), "cache").apply { mkdirs() }
    open fun getPackageName(): String = "com.cncverse.stremiobridge.desktop"

    private val activityManagerInstance = lazy { android.app.ActivityManager() }

    open fun getSystemService(name: String): Any? = when (name) {
        CLIPBOARD_SERVICE -> clipboard
        LAYOUT_INFLATER_SERVICE -> android.view.LayoutInflater.from(this)
        INPUT_METHOD_SERVICE -> InputMethodManager
        ACTIVITY_SERVICE -> activityManagerInstance.value
        else -> null
    }

    private val clipboard = ClipboardManager()

    open fun getString(resId: Int, vararg formatArgs: Any): String = ""
    open fun getPackageManager(): android.content.pm.PackageManager = android.content.pm.PackageManager()
    open fun getResources(): android.content.res.Resources = android.content.res.Resources()
    fun getMainLooper(): android.os.Looper = android.os.Looper.getMainLooper()
    fun getSystemServiceName(serviceClass: Class<*>): String? = null
    open fun registerActivityLifecycleCallbacks(callbacks: Any) {}
    open fun unregisterActivityLifecycleCallbacks(callbacks: Any) {}

    companion object {
        const val MODE_PRIVATE = 0
        const val MODE_APPEND = 32768
        const val CLIPBOARD_SERVICE = "clipboard"
        const val LAYOUT_INFLATER_SERVICE = "layout_inflater"
        const val INPUT_METHOD_SERVICE = "input_method"
        const val ACTIVITY_SERVICE = "activity"
        const val AUDIO_SERVICE = "audio"
        const val NOTIFICATION_SERVICE = "notification"
        const val CONNECTIVITY_SERVICE = "connectivity"
    }
}

/** Soft-keyboard stub — desktop dialogs show focus without an IME. */
object InputMethodManager {
    @JvmStatic fun showSoftInput(view: android.view.View?, flags: Int): Boolean = true
    @JvmStatic fun hideSoftInputFromWindow(windowToken: Any?, flags: Int): Boolean = true
    fun toggleSoftInput(showFlags: Int, hideFlags: Int) {}
}

/**
 * Singleton context handed to plugins that request one. Extends the
 * FragmentActivity stub so plugins casting the context to an Activity or
 * using support fragments (settings dialogs) work on desktop.
 */
object DesktopContext : androidx.fragment.app.FragmentActivity()

interface SharedPreferences {
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }

    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValue: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor
    val all: Map<String, *>
}

/** File-backed SharedPreferences stored under ~/.cncverse_bridge/prefs/<name>. */
internal object DesktopPreferences {
    private val cache = ConcurrentHashMap<String, FilePreferences>()

    fun get(name: String): FilePreferences = cache.computeIfAbsent(name) { FilePreferences(it) }
}

internal class FilePreferences(name: String) : SharedPreferences {
    private val prefName = name

    /**
     * CloudStream plugins share the "rebuild_preference"/"cnc_ext_settings"
     * pref files on Android — those accesses are attributed to the CALLING
     * plugin so the settings dialog groups keys under the right gear icon.
     */
    private fun effectivePref(): String {
        if (prefName == "rebuild_preference" || prefName == "cnc_ext_settings" || prefName == "com.lagradost.cloudstream3") {
            val caller = com.cncverse.stremiobridge.plugin.PluginCallContext.getCallingPluginName()
            if (!caller.isNullOrBlank()) return caller
        }
        return prefName
    }

    private fun fullKey(key: String) = "${effectivePref()}_$key"

    /** Every typed read registers the key + default in the settings schema. */
    private fun register(key: String, type: String, def: Any?) {
        // Always attribute to the calling plugin's internalName so the settings
        // dialog finds the schema under the correct gear icon, even when the
        // plugin opens its own custom SharedPreferences file name.  The storage
        // key still uses the actual pref-file prefix so reads/writes hit the
        // right slot in the settings map.
        val callingPlugin = com.cncverse.stremiobridge.plugin.PluginCallContext.getCallingPluginName()
        val registrationName = if (!callingPlugin.isNullOrBlank()) callingPlugin else effectivePref()
        val actualStorageKey = fullKey(key)
        com.cncverse.stremiobridge.settings.PluginSettingsSchemaRegistry.register(
            registrationName, key, type, def, storageKey = actualStorageKey,
        )
    }

    private fun raw(key: String): String? {
        // Suppress SettingsHookMap.get() re-registration: the typed register()
        // call already ran above, so SettingsHookMap should not create a
        // duplicate String-typed entry for the prefixed storage key.
        val hook = com.cncverse.stremiobridge.plugin.PluginCallContext.suppressSettingsHook
        hook.set(true)
        try {
            return com.lagradost.cloudstream3.CloudStreamApp.getSettings()[fullKey(key)]
        } finally {
            hook.set(false)
        }
    }

    override fun getString(key: String, defValue: String?): String? {
        register(key, "String", defValue)
        return raw(key) ?: defValue
    }
    override fun getStringSet(key: String, defValue: Set<String>?): Set<String>? {
        register(key, "StringSet", defValue)
        return raw(key)?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: defValue
    }
    override fun getInt(key: String, defValue: Int): Int {
        register(key, "Int", defValue)
        return raw(key)?.toIntOrNull() ?: defValue
    }
    override fun getLong(key: String, defValue: Long): Long {
        register(key, "Long", defValue)
        return raw(key)?.toLongOrNull() ?: defValue
    }
    override fun getFloat(key: String, defValue: Float): Float {
        register(key, "Float", defValue)
        return raw(key)?.toFloatOrNull() ?: defValue
    }
    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        register(key, "Boolean", defValue)
        return raw(key)?.toBooleanStrictOrNull() ?: defValue
    }
    override fun contains(key: String): Boolean {
        // Plugins gate their typed reads behind contains() (absent = default):
        // register the probe so those keys still surface in the settings dialog.
        // "Boolean"/true mirrors the common "enabled unless disabled" pattern;
        // a later typed read refines type/default in the schema registry.
        register(key, "Boolean", true)
        return com.lagradost.cloudstream3.CloudStreamApp.getSettings().containsKey(fullKey(key))
    }
    override val all: Map<String, *>
        get() = com.lagradost.cloudstream3.CloudStreamApp.getSettings()
            .filterKeys { it.startsWith("${effectivePref()}_") }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = mutableListOf<() -> Unit>()

        private fun write(key: String, value: String?) {
            com.lagradost.cloudstream3.CloudStreamApp.setKey(fullKey(key), value)
        }

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            pending.add { register(key, "String", value); write(key, value) }
        }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply {
            pending.add { register(key, "StringSet", values); write(key, values?.joinToString("\n")) }
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            pending.add { register(key, "Int", value); write(key, value.toString()) }
        }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            pending.add { register(key, "Long", value); write(key, value.toString()) }
        }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            pending.add { register(key, "Float", value); write(key, value.toString()) }
        }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            pending.add { register(key, "Boolean", value); write(key, value.toString()) }
        }
        override fun remove(key: String): SharedPreferences.Editor = apply {
            pending.add { com.lagradost.cloudstream3.CloudStreamApp.setKey(fullKey(key), null) }
        }
        override fun clear(): SharedPreferences.Editor = apply {
            pending.add {
                com.lagradost.cloudstream3.CloudStreamApp.getSettings()
                    .keys.filter { it.startsWith("${effectivePref()}_") }
                    .forEach { com.lagradost.cloudstream3.CloudStreamApp.setKey(it, null) }
            }
        }
        override fun apply() {
            pending.forEach { it() }
        }
        override fun commit(): Boolean {
            return try { apply(); true } catch (_: Exception) { false }
        }
    }
}
