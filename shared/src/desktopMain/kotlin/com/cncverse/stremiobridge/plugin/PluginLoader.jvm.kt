package com.cncverse.stremiobridge.plugin

import com.cncverse.stremiobridge.model.SitePlugin
import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.model.StreamBehaviorHints
import com.cncverse.stremiobridge.model.ProxyHeaders
import com.cncverse.stremiobridge.model.toLoadedPluginInfo
import com.cncverse.stremiobridge.server.MainApiWrapper
import com.cncverse.stremiobridge.server.MediaInfo
import com.cncverse.stremiobridge.server.MediaInfoEpisode
import com.cncverse.stremiobridge.server.SearchResult
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.state.LoadedPluginInfo
import com.cncverse.stremiobridge.state.ServerState
import com.googlecode.dex2jar.tools.Dex2jarCmd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStreamReader
import java.net.URLClassLoader
import java.util.zip.ZipFile

private val manifestJson = Json { ignoreUnknownKeys = true }

/**
 * JVM/Desktop actual implementation of [PluginLoader].
 *
 * Since .cs3 files contain DEX bytecode (Android-only), we use dex2jar to
 * convert them to standard JVM .jar files, then load via [URLClassLoader].
 *
 * Flow:
 *  1. Input: .cs3 file (DEX format, also a ZIP with manifest.json inside)
 *  2. Dex2jarCmd: .cs3 → converted_dex2jar.jar
 *  3. Extract manifest.json from the ORIGINAL .cs3 (ZIP entry)
 *  4. URLClassLoader(convertedJar, parentClassLoader)
 *  5. Load pluginClass → instantiate → call load()
 *  6. Collect MainAPI registrations via reflection
 */
actual class PluginLoader {

    private val loadedClassLoaders = mutableListOf<URLClassLoader>()

    actual suspend fun loadPlugins(
        plugins: List<SitePlugin>,
        cs3Files: Map<String, File>,
    ): List<LoadedPluginInfo> = withContext(Dispatchers.IO) {
        plugins.mapNotNull { plugin ->
            val cs3File = cs3Files[plugin.internalName] ?: run {
                ServerState.warn("No .cs3 for '${plugin.name}', skipping")
                return@mapNotNull plugin.toLoadedPluginInfo(apiRegistered = false)
            }
            val registered = loadSinglePlugin(cs3File, plugin)
            plugin.toLoadedPluginInfo(apiRegistered = registered)
        }
    }

    private fun loadSinglePlugin(cs3File: File, data: SitePlugin): Boolean {
        return try {
            // ── Step 1: Convert .cs3 (DEX) → .jar using dex2jar ─────────────
            val jarFile = convertToJar(cs3File) ?: run {
                ServerState.error("dex2jar conversion failed for '${data.name}'")
                // Still add metadata-only wrapper
                StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
                return false
            }

            // ── Step 2: Read manifest.json from inside the .cs3 (ZIP format) ─
            val manifest = readManifestFromCs3(cs3File) ?: run {
                ServerState.error("No manifest.json inside '${data.name}'")
                StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
                return false
            }
            val pluginClassName = manifest.pluginClassName ?: run {
                ServerState.error("No pluginClassName in manifest for '${data.name}'")
                StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
                return false
            }

            // ── Step 3: Build URLClassLoader with converted JAR ───────────────
            // The parent classloader carries Ktor, coroutines, serialization, etc.
            val urls = arrayOf(jarFile.toURI().toURL())
            val classLoader = URLClassLoader(urls, Thread.currentThread().contextClassLoader)
            loadedClassLoaders.add(classLoader)

            // ── Step 4: Instantiate plugin class ─────────────────────────────
            val pluginClass = classLoader.loadClass(pluginClassName)
            val pluginInstance = pluginClass.getDeclaredConstructor().newInstance()

            // Set filename field via reflection (BasePlugin.filename property)
            setFieldViaReflection(pluginInstance, "filename", cs3File.absolutePath)

            // ── Step 5: Call load() — no-arg cross-platform version ───────────
            // We deliberately skip load(Context) since Android Context isn't
            // available on the JVM. Most HTTP-based plugins only need load().
            val noArgLoad = try {
                pluginClass.getMethod("load")
            } catch (_: NoSuchMethodException) {
                // Try superclass chain
                findMethodInHierarchy(pluginClass, "load")
            }
            noArgLoad?.invoke(pluginInstance)

            // ── Step 6: Collect registered APIs via APIHolder reflection ───────
            // APIHolder.allProviders is a MutableList<MainAPI> populated by
            // registerMainAPI() calls inside load(). We read it reflectively
            // since MainAPI lives in the cloudstream library compiled for JVM.
            val apisBefore = getApiHolderProviders(classLoader)
            val newApis = getApiHolderProviders(classLoader)
                .filterNot { apisBefore.contains(it) }
                .ifEmpty {
                    // If pre/post diff doesn't work, take all providers
                    getApiHolderProviders(classLoader)
                }

            if (newApis.isNotEmpty()) {
                newApis.forEach { api ->
                    StremioServer.loadedApis.add(JvmMainApiWrapper(api, data))
                }
                ServerState.info("✓ Loaded '${data.name}' via dex2jar (${newApis.size} API(s))")
                true
            } else {
                ServerState.warn("'${data.name}' loaded but registered no APIs — using metadata stub")
                StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
                false
            }
        } catch (e: Throwable) {
            ServerState.error("✗ Failed to load '${data.name}': ${e.message}")
            StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
            false
        }
    }

    // ── dex2jar conversion ────────────────────────────────────────────────────

    private fun convertToJar(cs3File: File): File? {
        val jarFile = File(cs3File.parentFile, "${cs3File.nameWithoutExtension}-jvm.jar")

        // Use cached JAR if it's newer than the .cs3 source
        if (jarFile.exists() && jarFile.length() > 0L && jarFile.lastModified() >= cs3File.lastModified()) {
            ServerState.info("Using cached JAR: ${jarFile.name}")
            return jarFile
        }

        return try {
            ServerState.info("Converting '${cs3File.name}' → JAR via dex2jar…")

            // Step 1: extract classes.dex from the .cs3 ZIP to a temp file
            val dexFile = File(cs3File.parentFile, "${cs3File.nameWithoutExtension}.dex")
            ZipFile(cs3File).use { zip ->
                val dexEntry = zip.getEntry("classes.dex")
                    ?: run {
                        ServerState.error("No classes.dex inside ${cs3File.name}")
                        return null
                    }
                java.nio.file.Files.copy(
                    zip.getInputStream(dexEntry),
                    dexFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }

            // Step 2: convert .dex → .jar using dex2jar
            try {
                // Try instance method first (matches reference repo)
                Dex2jarCmd().doMain("-f", dexFile.absolutePath, "-o", jarFile.absolutePath)
            } catch (_: Exception) {
                // Fallback to static main
                Dex2jarCmd.main("-f", dexFile.absolutePath, "-o", jarFile.absolutePath)
            }

            dexFile.delete() // clean up temp file

            if (jarFile.exists() && jarFile.length() > 0L) {
                ServerState.info("✓ dex2jar done: ${jarFile.name} (${jarFile.length() / 1024} KB)")
                jarFile
            } else {
                ServerState.error("dex2jar produced empty/missing output for ${cs3File.name}")
                null
            }
        } catch (e: Exception) {
            ServerState.error("dex2jar exception for ${cs3File.name}: ${e.message}")
            null
        }
    }

    // ── manifest.json reader ──────────────────────────────────────────────────

    private fun readManifestFromCs3(cs3File: File): PluginManifest? {
        return try {
            ZipFile(cs3File).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                InputStreamReader(zip.getInputStream(entry)).use { reader ->
                    manifestJson.decodeFromString<PluginManifest>(reader.readText())
                }
            }
        } catch (e: Exception) {
            ServerState.warn("Could not read manifest from ${cs3File.name}: ${e.message}")
            null
        }
    }

    // ── APIHolder reflection ──────────────────────────────────────────────────

    private fun getApiHolderProviders(loader: URLClassLoader): List<Any> {
        return try {
            // Try loading APIHolder from the plugin's classloader first,
            // then fall back to the shared classloader.
            val holderClass = try {
                loader.loadClass("com.lagradost.cloudstream3.APIHolder")
            } catch (_: ClassNotFoundException) {
                Class.forName("com.lagradost.cloudstream3.APIHolder")
            }
            val companionField = holderClass.getDeclaredField("INSTANCE")
                .also { it.isAccessible = true }
            val companion = companionField.get(null)
            val allProvidersMethod = companion.javaClass.getMethod("getAllProviders")
            @Suppress("UNCHECKED_CAST")
            allProvidersMethod.invoke(companion) as? List<Any> ?: emptyList()
        } catch (_: Exception) {
            // APIHolder not available — plugin registered nothing we can read
            emptyList()
        }
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    private fun setFieldViaReflection(obj: Any, fieldName: String, value: Any) {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                cls.getDeclaredField(fieldName).also {
                    it.isAccessible = true
                    it.set(obj, value)
                }
                return
            } catch (_: NoSuchFieldException) { cls = cls.superclass }
        }
    }

    private fun findMethodInHierarchy(cls: Class<*>, methodName: String): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        while (c != null) {
            try { return c.getDeclaredMethod(methodName) } catch (_: NoSuchMethodException) { c = c.superclass }
        }
        return null
    }

    actual fun getRegisteredApis(): List<Any> = StremioServer.loadedApis.toList()

    actual fun openPluginSettings(internalName: String, activityContext: Any?) {
        // No-op on JVM/Desktop, settings UI is Android-only
    }

    actual fun unloadAll() {
        StremioServer.loadedApis.clear()
        loadedClassLoaders.forEach { runCatching { it.close() } }
        loadedClassLoaders.clear()
        ServerState.info("All plugins unloaded")
    }
}

// ── Plugin manifest data class ────────────────────────────────────────────────

@Serializable
private data class PluginManifest(
    val name: String? = null,
    val pluginClassName: String? = null,
    val requiresResources: Boolean = false,
    val version: Int? = null,
)

// ── JVM MainApiWrapper (reflection-based) ─────────────────────────────────────

/**
 * Wraps a MainAPI instance loaded via reflection on the JVM.
 * Uses reflection to invoke search(), getMainPage(), load(), loadLinks().
 */
private class JvmMainApiWrapper(
    private val api: Any,
    private val plugin: SitePlugin,
) : MainApiWrapper {

    private val apiClass: Class<*> = api.javaClass

    override val name: String
        get() = reflectString("getName") ?: reflectString("name") ?: plugin.name
    override val internalName: String
        get() = plugin.internalName
    override val supportedTypes: List<String>
        get() = plugin.tvTypes ?: listOf("movie", "series")
    override suspend fun getMainPageSections(): List<String> = emptyList()

    override suspend fun search(query: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                val method = apiClass.getMethod("search", String::class.java)
                @Suppress("UNCHECKED_CAST")
                val results = method.invoke(api, query) as? List<*> ?: return@withContext emptyList()
                results.mapNotNull { it?.reflectToSearchResult() }
            } catch (e: Exception) {
                ServerState.warn("JVM search error (${plugin.name}): ${e.message}")
                emptyList()
            }
        }

    override suspend fun getMainPage(page: Int, type: String, sectionName: String?): List<SearchResult> =
        withContext(Dispatchers.IO) {
            // Simplified fallback: first-page equals an empty search
            if (page == 1) search("") else emptyList()
        }

    override suspend fun load(url: String): MediaInfo? =
        withContext(Dispatchers.IO) {
            try {
                val method = apiClass.getMethod("load", String::class.java)
                val resp = method.invoke(api, url) ?: return@withContext null
                resp.reflectToMediaInfo(url)
            } catch (e: Throwable) {
                ServerState.warn("Load error (${plugin.name}): ${e.message}")
                null
            }
        }

    override suspend fun loadLinks(dataUrl: String): List<StremioStream> =
        withContext(Dispatchers.IO) {
            val streams = mutableListOf<StremioStream>()
            try {
                kotlinx.coroutines.withTimeoutOrNull(20_000) {
                    // loadLinks signature: (data: String, isCasting: Boolean, subtitleCallback, callback)
                    val method = apiClass.methods.firstOrNull { it.name == "loadLinks" }
                        ?: return@withTimeoutOrNull
                    val paramCount = method.parameterCount

                    when (paramCount) {
                        4 -> {
                            val subtitleCallback: (Any) -> Unit = { }
                            val linkCallback: (Any) -> Unit = { link ->
                                streams.addAll(link.reflectToStreams(plugin.name))
                            }
                            method.invoke(
                                api,
                                dataUrl,
                                false,
                                subtitleCallback,
                                linkCallback,
                            )
                        }
                        2 -> {
                            val linkCallback: (Any) -> Unit = { link ->
                                streams.addAll(link.reflectToStreams(plugin.name))
                            }
                            method.invoke(
                                api,
                                dataUrl,
                                linkCallback,
                            )
                        }
                        else -> { /* unsupported signature */ }
                    }
                }
            } catch (e: Throwable) {
                val cause = e.cause?.message ?: e.message
                ServerState.warn("LoadLinks error (${plugin.name}): $cause | dataUrl=$dataUrl")
            }
            streams
        }

    private fun reflectString(methodOrField: String): String? {
        return try {
            apiClass.getMethod(methodOrField).invoke(api) as? String
        } catch (_: Exception) {
            try { apiClass.getDeclaredField(methodOrField).also { it.isAccessible = true }.get(api) as? String }
            catch (_: Exception) { null }
        }
    }
}

// ── Reflection helpers for CS3 response types ─────────────────────────────────

private fun Any.reflectToSearchResult(): SearchResult? {
    return try {
        val cls = this.javaClass
        val name = cls.getMethod("getName").invoke(this) as? String ?: return null
        val url  = cls.getMethod("getUrl").invoke(this) as? String ?: return null
        val poster = runCatching { cls.getMethod("getPosterUrl").invoke(this) as? String }.getOrNull()
        val type   = runCatching { cls.getMethod("getType").invoke(this)?.toString() }.getOrNull() ?: "movie"
        val year   = runCatching { cls.getMethod("getYear").invoke(this) as? Int }.getOrNull()
        SearchResult(name = name, url = url, posterUrl = poster, type = type, year = year)
    } catch (_: Exception) {
        null
    }
}

private fun Any.reflectToMediaInfo(originalUrl: String): MediaInfo? {
    return try {
        val cls = this.javaClass
        val fetchedDataUrl = runCatching { cls.getMethod("getDataUrl").invoke(this) as? String }
            .recoverCatching { cls.getField("dataUrl").get(this) as? String }
            .getOrNull()
            
        val name = cls.getMethod("getName").invoke(this) as? String ?: return null
        
        val rawEpisodes = runCatching { cls.getMethod("getEpisodes").invoke(this) }.getOrNull()
        val flatEpisodes = mutableListOf<Pair<Any, String?>>()
        when (rawEpisodes) {
            is List<*> -> rawEpisodes.filterNotNull().forEach { flatEpisodes.add(it to null) }
            is Map<*, *> -> rawEpisodes.forEach { (key, list) ->
                val dubName = key?.toString()
                if (list is List<*>) {
                    list.filterNotNull().forEach { flatEpisodes.add(it to dubName) }
                }
            }
        }

        val mappedEpisodes = flatEpisodes.takeIf { it.isNotEmpty() }?.mapNotNull { (ep, dubName) ->
            val epCls = ep.javaClass
            val epName = runCatching { epCls.getMethod("getName").invoke(ep) as? String }.getOrNull()
            val finalName = if (dubName != null && dubName != "None") {
                if (epName.isNullOrBlank()) dubName else "$epName ($dubName)"
            } else epName

            MediaInfoEpisode(
                name = finalName,
                season = runCatching { epCls.getMethod("getSeason").invoke(ep) as? Int }.getOrNull(),
                episode = runCatching { epCls.getMethod("getEpisode").invoke(ep) as? Int }.getOrNull(),
                dataUrl = runCatching { epCls.getMethod("getData").invoke(ep) as? String }
                    .recoverCatching { epCls.getMethod("getDataUrl").invoke(ep) as? String }
                    .getOrNull() ?: return@mapNotNull null,
                posterUrl = runCatching { epCls.getMethod("getPosterUrl").invoke(ep) as? String }.getOrNull()
            )
        }
            
        MediaInfo(
            name = name,
            url = originalUrl,
            posterUrl = runCatching { cls.getMethod("getPosterUrl").invoke(this) as? String }.getOrNull(),
            type = runCatching { cls.getMethod("getType").invoke(this)?.toString() }.getOrNull() ?: "movie",
            description = runCatching { cls.getMethod("getPlot").invoke(this) as? String }.getOrNull(),
            year = runCatching { cls.getMethod("getYear").invoke(this) as? Int }.getOrNull(),
            dataUrl = fetchedDataUrl ?: originalUrl,
            episodes = mappedEpisodes
        )
    } catch (_: Exception) { null }
}

private fun Any.reflectToStreams(pluginName: String): List<StremioStream> {
    return try {
        val cls = this.javaClass
        val rawUrl  = runCatching { cls.getMethod("getUrl").invoke(this) as? String }.getOrNull()
        val url     = rawUrl?.replace(Regex("[\\x00-\\x1F\\x7F]"), "")
        val quality = runCatching { cls.getMethod("getQuality").invoke(this)?.toString() }.getOrNull()
        val lName   = runCatching { cls.getMethod("getName").invoke(this) as? String }.getOrNull()
        val headers = runCatching {
            @Suppress("UNCHECKED_CAST")
            cls.getMethod("getHeaders").invoke(this) as? Map<String, String>
        }.getOrNull()
        if (url == null) return emptyList()
        listOf(
            StremioStream(
                name  = "$pluginName — ${quality ?: "stream"}",
                title = lName,
                url   = url,
                behaviorHints = if (!headers.isNullOrEmpty()) {
                    StreamBehaviorHints(proxyHeaders = ProxyHeaders(request = headers))
                } else null,
            )
        )
    } catch (_: Exception) {
        emptyList()
    }
}

// ── Metadata-only fallback ────────────────────────────────────────────────────

/**
 * Used when dex2jar conversion or plugin loading fails.
 * Shows the plugin in the UI but returns empty results from Stremio endpoints.
 */
private class MetadataOnlyWrapper(private val plugin: SitePlugin) : MainApiWrapper {
    override val name: String get() = plugin.name
    override val internalName: String get() = plugin.internalName
    override val supportedTypes: List<String> get() = plugin.tvTypes ?: listOf("movie")
    override val mainPageSections: List<String> get() = emptyList()
    override suspend fun search(query: String): List<SearchResult> = emptyList()
    override suspend fun getMainPage(page: Int, type: String, sectionName: String?): List<SearchResult> = emptyList()
    override suspend fun load(url: String): MediaInfo? = null
    override suspend fun loadLinks(dataUrl: String): List<StremioStream> = emptyList()
}

