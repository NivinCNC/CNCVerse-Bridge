package com.cncverse.stremiobridge.plugin

import android.content.res.AxmlParser
import android.content.res.Resources
import com.cncverse.stremiobridge.state.ServerState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Resources implementation backed by a plugin's .cs3 archive. Serves
 * getIdentifier/getLayout/getXml so resource-based settings fragments work on
 * desktop: identifiers resolve against the zip's res/ entries and the plugin's
 * compiled-in R classes (loaded via its classloader).
 */
class PluginResources(
    private val cs3File: File,
    private val classLoader: ClassLoader?,
) : Resources() {

    private val entries: Set<String> by lazy {
        runCatching {
            ZipFile(cs3File).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        }.getOrDefault(emptySet())
    }

    /** R.<type>.<name> → id, collected from the plugin's R classes. */
    private val rIds: Map<String, Int> by lazy { loadRIds() }

    /**
     * Parsed resources.arsc — the authoritative id table. Converted plugin jars
     * carry no R classes, so name→id lookups must resolve here to match the ids
     * compiled into AXML layouts.
     */
    private val arsc: android.content.res.ArscParser? by lazy {
        runCatching {
            ZipFile(cs3File).use { zip ->
                val entry = zip.getEntry("resources.arsc") ?: return@use null
                android.content.res.ArscParser(zip.getInputStream(entry).readBytes())
            }
        }.getOrNull()
    }

    private val idToEntry: MutableMap<Int, String> = ConcurrentHashMap()
    private val syntheticIds: MutableMap<String, Int> = ConcurrentHashMap()

    private fun loadRIds(): Map<String, Int> {
        val loader = classLoader ?: return emptyMap()
        val result = mutableMapOf<String, Int>()
        // R classes live in the manifest's package: try the plugin's package and
        // common variants
        val packages = mutableListOf<String>()
        runCatching {
            ZipFile(cs3File).use { zip ->
                val entry = zip.getEntry("manifest.json")
                if (entry != null) {
                    val text = zip.getInputStream(entry).readBytes().decodeToString()
                    val cls = Regex("\"pluginClassName\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                    if (cls != null) packages.add(cls.substringBeforeLast('.'))
                }
            }
        }
        packages.add("com.cncverse")
        for (type in listOf("layout", "xml", "id", "string", "drawable")) {
            for (pkg in packages) {
                runCatching {
                    val rClass = loader.loadClass("$pkg.R\$$type")
                    for (field in rClass.fields) {
                        if (field.type == Int::class.javaPrimitiveType) {
                            runCatching {
                                result["${type}.${field.name}"] = field.getInt(null)
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int {
        val n = name ?: return 0
        val t = defType ?: return 0
        // 1. resources.arsc table — real ids matching the AXML files
        arsc?.resourceIds?.get("$t/$n")?.let { return it }
        // 2. Real R constant
        rIds["$t.$n"]?.let { return it }
        // 3. Zip entry exists → synthetic stable id
        if (entries.any { it == "res/$t/$n.xml" || it == "res/$t/$n" }) {
            return syntheticIds.getOrPut("$t/$n") { syntheticIdFor("res/$t/$n") }
        }
        return 0
    }

    override fun getLayout(id: Int): android.content.res.XmlResourceParser? = openLayout(id)

    override fun getXml(id: Int): android.content.res.XmlResourceParser? = openLayout(id)

    override fun getString(id: Int): String = arsc?.stringValues?.get(id) ?: super.getString(id)

    /** String resolution used by the AXML inflater for @string references. */
    fun resolveString(id: Int): String? = arsc?.stringValues?.get(id)

    override fun getDrawable(id: Int): android.graphics.drawable.Drawable? =
        arsc?.entryPaths?.get(id)?.let { entry ->
            runCatching {
                ZipFile(cs3File).use { zip ->
                    zip.getEntry(entry)?.let { android.graphics.drawable.NamedDrawable(entry.substringAfterLast('/')) }
                }
            }.getOrNull()
        } ?: super.getDrawable(id)

    private fun syntheticIdFor(entry: String): Int = 0x7f000000 or (entry.hashCode() and 0xFFFFFF)

    fun getLayoutFile(id: Int): String? {
        idToEntry[id]?.let { return it }
        // resources.arsc path table (layout/xml/drawable ids)
        arsc?.entryPaths?.get(id)?.let {
            if (it in entries) {
                idToEntry[id] = it
                return it
            }
        }
        // Reverse-lookup real R id
        rIds.entries.firstOrNull { it.value == id }?.let { (key, _) ->
            val (type, name) = key.split(".", limit = 2)
            val path = "res/$type/$name.xml"
            if (path in entries) {
                idToEntry[id] = path
                return path
            }
        }
        // Reverse-lookup synthetic id
        syntheticIds.entries.firstOrNull { it.value == id }?.let {
            val path = "res/${it.key}.xml"
            if (path in entries) {
                idToEntry[id] = path
                return path
            }
        }
        return null
    }

    fun openLayout(id: Int): AxmlParser? {
        val path = getLayoutFile(id) ?: return null
        return runCatching {
            ZipFile(cs3File).use { zip ->
                val entry = zip.getEntry(path) ?: return null
                AxmlParser(zip.getInputStream(entry).readBytes())
            }
        }.getOrNull()
    }

    fun hasResources(): Boolean = entries.isNotEmpty()

    companion object {
        fun attachIfPresent(cs3File: File, classLoader: ClassLoader?, pluginInstance: Any): Boolean {
            return try {
                val resources = PluginResources(cs3File, classLoader)
                if (!resources.hasResources()) return false
                var cls: Class<*>? = pluginInstance.javaClass
                while (cls != null) {
                    try {
                        val field = cls.getDeclaredField("resources")
                        field.isAccessible = true
                        field.set(pluginInstance, resources)
                        return true
                    } catch (_: NoSuchFieldException) {
                        cls = cls.superclass
                    }
                }
                ServerState.warn("Plugin has resources but no 'resources' field to attach")
                false
            } catch (t: Throwable) {
                ServerState.warn("Failed attaching plugin resources: ${t.message}")
                false
            }
        }
    }
}
