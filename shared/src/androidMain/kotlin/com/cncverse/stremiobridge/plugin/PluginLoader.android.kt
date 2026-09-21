package com.cncverse.stremiobridge.plugin

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.plugins.Plugin
import com.cncverse.stremiobridge.model.SitePlugin
import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.model.StreamBehaviorHints
import com.cncverse.stremiobridge.model.ProxyHeaders
import com.cncverse.stremiobridge.model.StremioSubtitle
import com.cncverse.stremiobridge.model.toLoadedPluginInfo
import com.cncverse.stremiobridge.model.cs3TvTypeToStremio
import com.cncverse.stremiobridge.server.MainApiWrapper
import com.cncverse.stremiobridge.server.MediaInfo
import com.cncverse.stremiobridge.server.MediaInfoEpisode
import com.cncverse.stremiobridge.server.SearchResult
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.state.LoadedPluginInfo
import com.cncverse.stremiobridge.state.ServerState
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader

private const val TAG = "PluginLoaderAndroid"

private val manifestJson = Json { ignoreUnknownKeys = true }

/**
 * Android implementation of [PluginLoader].
 * Uses PathClassLoader and Reflection (like JVM) since we don't have compile-time deps.
 */
actual class PluginLoader(private val context: Context) {

    private val loadedPlugins: MutableMap<String, Any> = mutableMapOf()
    private val registeredApis: MutableList<Any> = mutableListOf()

    actual suspend fun loadPlugins(
        plugins: List<SitePlugin>,
        cs3Files: Map<String, File>,
    ): List<LoadedPluginInfo> = withContext(Dispatchers.IO) {
        configureAppClientNetwork()
        val pluginContext = awaitForegroundActivity()
        val loadedList = plugins.mapNotNull { plugin ->
            val file = cs3Files[plugin.internalName] ?: run {
                ServerState.warn("No .cs3 file for '${plugin.name}', skipping")
                return@mapNotNull plugin.toLoadedPluginInfo(apiRegistered = false)
            }
            loadSinglePlugin(file, plugin, pluginContext)
        }
        preWarmPluginHosts()
        loadedList
    }

    private fun configureAppClientNetwork() {
        try {
            val appClass = runCatching { Class.forName("com.lagradost.cloudstream3.app") }.getOrNull() ?: return
            val clientField = appClass.fields.firstOrNull { it.name == "client" } ?: return
            val currentNiceClient = clientField.get(null) ?: return

            val okClientField = currentNiceClient.javaClass.declaredFields.firstOrNull {
                it.type.name.contains("OkHttpClient")
            } ?: return
            okClientField.isAccessible = true
            val existingOk = okClientField.get(currentNiceClient) as? okhttp3.OkHttpClient ?: return

            val newOk = existingOk.newBuilder()
                .dns(com.cncverse.stremiobridge.network.AdaptiveHostDns)
                .fastFallback(true)
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            okClientField.set(currentNiceClient, newOk)
            ServerState.info("Configured AdaptiveHostDns Engine with DoH Fallback Chain on app.client")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to configure AdaptiveHostDns network on app.client: ${t.message}")
        }
    }

    private suspend fun preWarmPluginHosts() {
        withContext(Dispatchers.IO) {
            try {
                registeredApis.forEach { api ->
                    val mainUrlField = api.javaClass.methods.firstOrNull { it.name == "getMainUrl" }
                    val mainUrl = mainUrlField?.invoke(api) as? String
                    if (!mainUrl.isNullOrBlank()) {
                        val host = runCatching { java.net.URI(mainUrl).host }.getOrNull()
                        if (!host.isNullOrBlank()) {
                            com.cncverse.stremiobridge.network.AdaptiveHostDns.preWarmHost(host)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Pre-warming plugin hosts encountered an error: ${t.message}")
            }
        }
    }

    private fun loadSinglePlugin(file: File, data: SitePlugin, pluginContext: Context): LoadedPluginInfo {
        return try {
            // Extract classes.dex from .cs3 (matches desktop approach)
            val dexFile = File(file.parentFile, "${file.nameWithoutExtension}.dex")
            if (!dexFile.exists() || dexFile.lastModified() < file.lastModified()) {
                java.util.zip.ZipFile(file).use { zip ->
                    val dexEntry = zip.getEntry("classes.dex") ?: run {
                        ServerState.error("No classes.dex inside ${file.name}")
                        StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
                        return data.toLoadedPluginInfo(apiRegistered = false)
                    }
                    java.nio.file.Files.copy(
                        zip.getInputStream(dexEntry),
                        dexFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }

            try {
                if (!dexFile.setReadOnly()) {
                    Log.w(TAG, "Could not set read-only on ${dexFile.name}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "setReadOnly failed: ${t.message}")
            }

            // Load the extracted .dex file instead of the .cs3
            val loader = PathClassLoader(dexFile.absolutePath, context.classLoader)

            val manifest = try {
                java.util.zip.ZipFile(file).use { zip ->
                    val entry = zip.getEntry("manifest.json") ?: return@use null
                    InputStreamReader(zip.getInputStream(entry)).use { reader ->
                        manifestJson.decodeFromString<PluginManifest>(reader.readText())
                    }
                }
            } catch (e: Throwable) {
                null
            } ?: run {
                ServerState.error("No manifest.json in '${data.name}'")
                StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
                return data.toLoadedPluginInfo(apiRegistered = false)
            }

            val pluginClassName = manifest.pluginClassName ?: run {
                ServerState.error("No pluginClassName in manifest for '${data.name}'")
                StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
                return data.toLoadedPluginInfo(apiRegistered = false)
            }

            val apisBefore = getApiHolderProviders(loader).toSet()

            val pluginClass = loader.loadClass(pluginClassName)
            val pluginInstance = pluginClass.getDeclaredConstructor().newInstance()

            // Set filename field
            setFieldViaReflection(pluginInstance, "filename", file.absolutePath)
            if (manifest.requiresResources) {
                val res = loadPluginResources(file, context)
                if (pluginInstance is Plugin) {
                    pluginInstance.resources = res
                } else {
                    try {
                        pluginInstance.javaClass.getMethod("setResources", Resources::class.java).invoke(pluginInstance, res)
                    } catch (e: Exception) {
                        ServerState.warn("Failed to set resources for ${manifest.name} via reflection: ${e.message}")
                    }
                }
            }

            // load(Context) or load()
            val loadMethodWithContext = runCatching { pluginClass.getMethod("load", Context::class.java) }.getOrNull()
                ?: findMethodInHierarchy(pluginClass, "load", Context::class.java)
            val noArgLoad = runCatching { pluginClass.getMethod("load") }.getOrNull()
                ?: findMethodInHierarchy(pluginClass, "load")

            if (loadMethodWithContext != null) {
                loadMethodWithContext.invoke(pluginInstance, pluginContext)
            } else if (noArgLoad != null) {
                noArgLoad.invoke(pluginInstance)
            }

            loadedPlugins[data.internalName] = pluginInstance

            val hasSettings = getOpenSettings(pluginInstance) != null

            val newApis = getApiHolderProviders(loader)
                .filterNot { apisBefore.contains(it) }

            if (newApis.isEmpty()) {
                ServerState.warn("Plugin '${data.name}' loaded but registered no providers")
                StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
                return data.toLoadedPluginInfo(apiRegistered = false, hasSettings = hasSettings)
            }

            newApis.forEach { api ->
                registeredApis.add(api)
                val wrapper = if (api is com.lagradost.cloudstream3.MainAPI) {
                    DirectMainApiWrapper(api, data)
                } else {
                    ReflectionMainApiWrapper(api, data)
                }
                StremioServer.loadedApis.add(wrapper)
            }

            ServerState.info("Loaded '${data.name}' on Android (${newApis.size} API(s))")
            data.toLoadedPluginInfo(apiRegistered = true, hasSettings = hasSettings)
        } catch (e: Throwable) {
            var actualException = e
            while (actualException is java.lang.reflect.InvocationTargetException && actualException.cause != null) {
                actualException = actualException.cause!!
            }
            val trace = Log.getStackTraceString(actualException).take(150).replace("\n", " ")
            ServerState.error("Failed to load '${data.name}': ${actualException::class.simpleName} - ${actualException.message} | $trace")
            Log.e(TAG, "Plugin load failed for ${data.name}", actualException)
            StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
            data.toLoadedPluginInfo(apiRegistered = false)
        }
    }

    private fun loadPluginResources(file: File, context: Context): Resources {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            .invoke(assets, file.absolutePath)
        @Suppress("DEPRECATION")
        return Resources(assets, context.resources.displayMetrics, context.resources.configuration)
    }
    private suspend fun awaitForegroundActivity(): Context {
        if (PluginUIContext.currentActivity == null) {
            ServerState.info("Waiting for the app screen before loading UI-dependent plugins")
        }
        return PluginUIContext.awaitActivity()
    }

    private fun getApiHolderProviders(loader: ClassLoader): List<Any> {
        return try {
            val holderClass = try {
                loader.loadClass("com.lagradost.cloudstream3.APIHolder")
            } catch (_: ClassNotFoundException) {
                Class.forName("com.lagradost.cloudstream3.APIHolder")
            }
            val companionField = holderClass.getDeclaredField("INSTANCE").also { it.isAccessible = true }
            val companion = companionField.get(null)
            val method = companion.javaClass.getMethod("getAllProviders")
            @Suppress("UNCHECKED_CAST")
            method.invoke(companion) as? List<Any> ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

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

    private fun findMethodInHierarchy(cls: Class<*>, methodName: String, vararg paramTypes: Class<*>): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        while (c != null) {
            try { return c.getDeclaredMethod(methodName, *paramTypes) } catch (_: NoSuchMethodException) { c = c.superclass }
        }
        return null
    }

    actual fun getRegisteredApis(): List<Any> = registeredApis

    actual fun openPluginSettings(internalName: String, activityContext: Any?) {
        val pluginInstance = loadedPlugins[internalName]
        if (pluginInstance == null) {
            ServerState.warn("Settings unavailable: '$internalName' is not loaded")
            return
        }

        val openSettings = getOpenSettings(pluginInstance)
        
        ServerState.warn("Plugin methods for ${pluginInstance.javaClass.name}: ${pluginInstance.javaClass.methods.map { it.name }}")

        if (openSettings == null) {
            ServerState.warn("Settings unavailable for $internalName")
            return
        }

        val settingsContext = activityContext as? Context ?: PluginUIContext.currentActivity ?: context
        Handler(Looper.getMainLooper()).post {
            runCatching { openSettings(settingsContext) }
                .onSuccess { ServerState.info("Opened settings for $internalName") }
                .onFailure { error ->
                    Log.e(TAG, "Failed to open settings for $internalName", error)
                    ServerState.warn(
                        "Failed to open settings for $internalName: " +
                            (error.message ?: error::class.simpleName)
                    )
                }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getOpenSettings(plugin: Any): ((Context) -> Unit)? {
        if (plugin is Plugin) return plugin.openSettings

        return runCatching {
            plugin.javaClass.getMethod("getOpenSettings").invoke(plugin) as? (Context) -> Unit
        }.getOrNull()
    }

    actual fun unloadAll() {
        StremioServer.loadedApis.clear()
        registeredApis.clear()
        loadedPlugins.clear()
        ServerState.info("All plugins unloaded")
    }
}

@Serializable
private data class PluginManifest(
    val name: String? = null,
    val pluginClassName: String? = null,
    val requiresResources: Boolean = false,
    val version: Int? = null,
)

private class ReflectionMainApiWrapper(
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

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val method = apiClass.getMethod("search", String::class.java)
            @Suppress("UNCHECKED_CAST")
            val results = method.invoke(api, query) as? List<*> ?: return@withContext emptyList()
            results.mapNotNull { it?.reflectToSearchResult() }
        } catch (e: Throwable) {
            ServerState.warn("Search error (${plugin.name}): ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, type: String, sectionName: String?): List<SearchResult> = withContext(Dispatchers.IO) {
        if (page == 1) search("") else emptyList()
    }

    override suspend fun load(url: String): MediaInfo? = withContext(Dispatchers.IO) {
        try {
            val method = apiClass.getMethod("load", String::class.java)
            val resp = method.invoke(api, url) ?: return@withContext null
            resp.reflectToMediaInfo(url)
        } catch (e: Throwable) {
            ServerState.warn("Load error (${plugin.name}): ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(dataUrl: String): List<StremioStream> = withContext(Dispatchers.IO) {
        val streams = mutableListOf<StremioStream>()
        val subtitles = mutableListOf<StremioSubtitle>()
        val seenSubtitleUrls = mutableSetOf<String>()
        val seenStreamUrls = mutableSetOf<String>()
        try {
            val method = apiClass.methods.firstOrNull { it.name == "loadLinks" } ?: return@withContext emptyList()
            when (method.parameterCount) {
                4 -> {
                    val subtitleCallback: (Any) -> Unit = { sub ->
                        val stremioSub = sub.reflectToSubtitle(plugin.name, api)
                        if (stremioSub != null && seenSubtitleUrls.add(stremioSub.url)) {
                            ServerState.info("subtitle plugin=${plugin.name} lang=${stremioSub.lang} url=${stremioSub.url}")
                            subtitles.add(stremioSub)
                        }
                    }
                    val linkCallback: (Any) -> Unit = { link ->
                        val newStreams = (link as Any).reflectToStreams(plugin.name, api)
                        newStreams.forEach { st ->
                            val u = st.url ?: st.hashCode().toString()
                            if (seenStreamUrls.add(u)) {
                                ServerState.info("stream plugin=${plugin.name} name=${st.name} url=${st.url}")
                                streams.add(st)
                            }
                        }
                    }
                    method.invoke(api, dataUrl, false, subtitleCallback, linkCallback)
                }
                2 -> {
                    val linkCallback: (Any) -> Unit = { link ->
                        val newStreams = (link as Any).reflectToStreams(plugin.name, api)
                        newStreams.forEach { st ->
                            val u = st.url ?: st.hashCode().toString()
                            if (seenStreamUrls.add(u)) {
                                ServerState.info("stream plugin=${plugin.name} name=${st.name} url=${st.url}")
                                streams.add(st)
                            }
                        }
                    }
                    method.invoke(api, dataUrl, linkCallback)
                }
            }
        } catch (e: Throwable) {
            ServerState.warn("LoadLinks error (${plugin.name}): ${e.message}")
        }
        
        val uniqueSubtitles = subtitles.distinctBy { it.url }
        val uniqueStreams = streams.distinctBy { it.url ?: it.hashCode().toString() }
        if (uniqueSubtitles.isNotEmpty()) {
            uniqueStreams.map { it.copy(subtitles = uniqueSubtitles.toList()) }
        } else {
            uniqueStreams
        }
    }

    private fun reflectString(name: String): String? = try {
        apiClass.getMethod(name).invoke(api) as? String
    } catch (_: Exception) {
        try { apiClass.getDeclaredField(name).also { it.isAccessible = true }.get(api) as? String } catch (_: Exception) { null }
    }
}

/** Reads the plugin response's own getType() (a CS3 TvType enum) and converts it to Stremio's vocabulary. */
private fun Any.reflectStremioType(): String? = runCatching {
    val typeObj = javaClass.getMethod("getType").invoke(this) ?: return@runCatching null
    val typeName = runCatching {
        typeObj.javaClass.getMethod("name").invoke(typeObj) as? String
    }.getOrNull() ?: typeObj.toString()
    cs3TvTypeToStremio(typeName)
}.getOrNull()

private fun inferTypeFromClass(cls: Class<*>): String? {
    val simpleName = cls.simpleName
    return when {
        simpleName == "MovieLoadResponse" || simpleName == "MovieSearchResponse" ->
            cs3TvTypeToStremio("Movie")
        simpleName == "TvSeriesLoadResponse" || simpleName == "TvSeriesSearchResponse" ->
            cs3TvTypeToStremio("TvSeries")
        simpleName.contains("Live", ignoreCase = true) ->
            cs3TvTypeToStremio("Live")
        // AnimeSearchResponse/AnimeLoadResponse etc are genuinely ambiguous — fall through
        else -> null
    }
}

private fun Any.reflectToSearchResult(): SearchResult? {
    return try {
        val cls = this.javaClass
        val name = cls.getMethod("getName").invoke(this) as? String
        val url = cls.getMethod("getUrl").invoke(this) as? String

        if (name.isNullOrBlank() || url.isNullOrBlank()) {
            // Plugin returned an item without a name or url (lazy-loaded title pattern like Netflix)
            // Try to extract a meaningful name from the url data (if it's JSON, try "title" or "id")
            val derivedName = if (!url.isNullOrBlank()) {
                runCatching {
                    val json = JSONObject(url)
                    json.optString("title").takeIf { it.isNotBlank() }
                        ?: json.optString("t").takeIf { it.isNotBlank() }
                }.getOrNull() ?: "_"
            } else null

            if (url.isNullOrBlank()) return null

            SearchResult(
                name = derivedName ?: " ",
                url = url,
                posterUrl = runCatching { cls.getMethod("getPosterUrl").invoke(this) as? String }.getOrNull(),
                type = inferTypeFromClass(cls) ?: this.reflectStremioType() ?: "movie",
                year = runCatching { cls.getMethod("getYear").invoke(this) as? Int }.getOrNull()
            )
        } else {
            SearchResult(
                name = name,
                url = url,
                posterUrl = runCatching { cls.getMethod("getPosterUrl").invoke(this) as? String }.getOrNull(),
                type = inferTypeFromClass(cls) ?: this.reflectStremioType() ?: "movie",
                year = runCatching { cls.getMethod("getYear").invoke(this) as? Int }.getOrNull()
            )
        }
    } catch (_: Exception) { null }
}

private fun Any.reflectToMediaInfo(originalUrl: String): MediaInfo? {
    return try {
        val cls = this.javaClass
        val fetchedDataUrl = runCatching { cls.getMethod("getDataUrl").invoke(this) as? String }
            .recoverCatching { cls.getField("dataUrl").get(this) as? String }
            .getOrNull()
            
        val name = cls.getMethod("getName").invoke(this) as? String
        if (name == null) {
            ServerState.warn("reflectToMediaInfo: name is null for class=${cls.name}")
            return null
        }
        
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
       var finalDataUrl = fetchedDataUrl ?: originalUrl
        val classType = inferTypeFromClass(cls)
        val reflectedType = this.reflectStremioType()

        var finalMappedEpisodes = mappedEpisodes
        val type = if (finalMappedEpisodes?.size == 1) {
            // Single-episode responses collapse to direct-play, even if the plugin
            // wrapped it in a TvSeriesLoadResponse.
            finalDataUrl = finalMappedEpisodes[0].dataUrl
            finalMappedEpisodes = null
            "movie"
        } else {
            val hasSeries = !finalMappedEpisodes.isNullOrEmpty()
            when {
                // Multiple episodes = definitely a series, overriding whatever the
                // plugin's own getType()/class name claims.
                hasSeries -> "series"
                classType != null -> classType
                reflectedType != null -> reflectedType
                else -> "movie"
            }
        }
            
        MediaInfo(
            name = name,
            url = originalUrl,
            posterUrl = runCatching { cls.getMethod("getPosterUrl").invoke(this) as? String }.getOrNull(),
            type = type,
            description = runCatching { cls.getMethod("getPlot").invoke(this) as? String }.getOrNull(),
            year = runCatching { cls.getMethod("getYear").invoke(this) as? Int }.getOrNull(),
            dataUrl = finalDataUrl,
            episodes = finalMappedEpisodes
        )
    } catch (e: Exception) {
        ServerState.warn("reflectToMediaInfo error: ${e.message}")
        null 
    }
}

private fun Any.reflectToStreams(pluginName: String, api: Any? = null): List<StremioStream> {
    return try {
        val cls = this.javaClass
        val rawUrl = runCatching { cls.getMethod("getUrl").invoke(this) as? String }
            .recoverCatching { cls.getField("url").get(this) as? String }
            .getOrNull()
            
        val url = rawUrl?.replace(Regex("[\\x00-\\x1F\\x7F]"), "")
        
        if (url == null) {
            val methods = cls.methods.joinToString { it.name }
            val fields = cls.fields.joinToString { it.name }
            ServerState.warn("reflectToStreams failed: url is null. Class: ${cls.name}. Methods: $methods, Fields: $fields")
            return emptyList()
        }
        
        val qStr = runCatching { cls.getMethod("getQuality").invoke(this)?.toString() }
            .recoverCatching { cls.getField("quality").get(this)?.toString() }
            .getOrNull()
            
        // 400 is Qualities.Unknown.value in CS3
        val q = if (qStr == "400" || qStr == "0") null else qStr
            
        val n = runCatching { cls.getMethod("getName").invoke(this) as? String }
            .recoverCatching { cls.getField("name").get(this) as? String }
            .getOrNull()
            
        val h = runCatching { @Suppress("UNCHECKED_CAST") (cls.getMethod("getHeaders").invoke(this) as? Map<String, String>) }
            .recoverCatching { @Suppress("UNCHECKED_CAST") (cls.getField("headers").get(this) as? Map<String, String>) }
            .getOrNull()
        
        // Merge standalone `referer` field into headers (plugins often set referer separately)
        val referer = runCatching { cls.getMethod("getReferer").invoke(this) as? String }
            .recoverCatching { cls.getField("referer").get(this) as? String }
            .getOrNull()
        
        val mergedHeaders: Map<String, String>? = when {
            !referer.isNullOrBlank() && !h.isNullOrEmpty() -> h + ("Referer" to referer)
            !referer.isNullOrBlank() -> mapOf("Referer" to referer)
            else -> h
        }
        
        var finalHeaders = mergedHeaders?.toMutableMap() ?: mutableMapOf()

        val refKey = finalHeaders.keys.firstOrNull { it.equals("referer", ignoreCase = true) }
        if (refKey != null) {
            val refVal = finalHeaders[refKey]
            if (refVal != null && refVal.startsWith("http") && refVal.count { it == '/' } == 2) {
                finalHeaders[refKey] = "$refVal/"
            }
        }
        
        if (api != null) {
            try {
                val interceptorMethod = api.javaClass.methods.firstOrNull { it.name == "getVideoInterceptor" && it.parameterCount == 1 }
                val interceptor = interceptorMethod?.invoke(api, this)
                if (interceptor != null) {
                    val okhttpReqBuilderClass = Class.forName("okhttp3.Request\$Builder")
                    val reqBuilder = okhttpReqBuilderClass.getDeclaredConstructor().newInstance()
                    okhttpReqBuilderClass.getMethod("url", String::class.java).invoke(reqBuilder, url)
                    val fakeRequest = okhttpReqBuilderClass.getMethod("build").invoke(reqBuilder)
                    
                    val chainClass = Class.forName("okhttp3.Interceptor\$Chain")
                    val fakeChain = java.lang.reflect.Proxy.newProxyInstance(
                        chainClass.classLoader,
                        arrayOf(chainClass)
                    ) { _, method, args ->
                        when (method.name) {
                            "request" -> fakeRequest
                            "proceed" -> {
                                val modifiedReq = args[0]
                                val okhttpReqClass = Class.forName("okhttp3.Request")
                                val headersObj = okhttpReqClass.getMethod("headers").invoke(modifiedReq)
                                val size = headersObj.javaClass.getMethod("size").invoke(headersObj) as Int
                                for (i in 0 until size) {
                                    val k = headersObj.javaClass.getMethod("name", Int::class.java).invoke(headersObj, i) as String
                                    val v = headersObj.javaClass.getMethod("value", Int::class.java).invoke(headersObj, i) as String
                                    finalHeaders[k] = v
                                }
                                throw RuntimeException("Fake chain abort")
                            }
                            else -> null
                        }
                    }
                    
                    try {
                        interceptor.javaClass.getMethod("intercept", chainClass).invoke(interceptor, fakeChain)
                    } catch (e: Exception) {
                        if (e.cause?.message != "Fake chain abort") {
                            ServerState.warn("getVideoInterceptor threw unexpected error: ${e.cause?.message ?: e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                ServerState.warn("Failed to apply getVideoInterceptor: ${e.message}")
            }
        }
        
        val streamName = if (q != null) "$pluginName - ${q.removeSuffix("p")}p" else pluginName

        val kidB64 = runCatching { cls.getMethod("getKid").invoke(this) as? String }
            .recoverCatching { cls.getField("kid").get(this) as? String }
            .getOrNull()
        
        val keyB64 = runCatching { cls.getMethod("getKey").invoke(this) as? String }
            .recoverCatching { cls.getField("key").get(this) as? String }
            .getOrNull()
            
        var clearkeyHex: String? = null
        if (!kidB64.isNullOrBlank() && !keyB64.isNullOrBlank()) {
            try {
                // CS3 stores kid/key as Base64Url WITHOUT padding — add padding before decode.
                // Also handles already-hex strings (e.g. 32 hex chars = 16 bytes).
                // Using android.util.Base64 (no opt-in needed).
                fun toHex(input: String): String {
                    val stripped = input.trim().replace("-", "")
                    // If it looks like a plain hex string already, use it directly
                    if (stripped.length % 2 == 0 && stripped.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                        return stripped.lowercase()
                    }
                    // Decode as Base64Url (NO_PADDING + URL_SAFE flags)
                    val bytes = android.util.Base64.decode(
                        input.replace('-', '+').replace('_', '/'),
                        android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                    )
                    return bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
                }
                clearkeyHex = toHex(kidB64) + ":" + toHex(keyB64)
                ServerState.info("Clearkey decoded for $pluginName: ${clearkeyHex?.take(20)}...")
            } catch (e: Exception) {
                ServerState.warn("Failed to decode clearkey for $pluginName: ${e.message}")
            }
        }
        
        var finalUrl = url
        if (url.contains(".mpd") && !clearkeyHex.isNullOrBlank()) {
            val encodedMpdUrl = java.net.URLEncoder.encode(url, "UTF-8")
            val hostIp = try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces().asSequence().toList()
                val preferred = interfaces.filter { iface ->
                    iface.isUp && !iface.isLoopback && !iface.isPointToPoint &&
                    (iface.name.contains("wlan", ignoreCase = true) ||
                     iface.name.contains("eth", ignoreCase = true) ||
                     iface.name.contains("en", ignoreCase = true))
                }
                val candidates = if (preferred.isNotEmpty()) preferred else interfaces.filter { iface ->
                    iface.isUp && !iface.isLoopback && !iface.isPointToPoint &&
                    !iface.name.contains("p2p", ignoreCase = true) &&
                    !iface.name.contains("dummy", ignoreCase = true) &&
                    !iface.name.contains("tun", ignoreCase = true) &&
                    !iface.name.contains("rmnet", ignoreCase = true)
                }
                candidates
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterIsInstance<java.net.Inet4Address>()
                    .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    .map { it.hostAddress }
                    .sorted()
                    .firstOrNull() ?: "127.0.0.1"
            } catch (e: Exception) { "127.0.0.1" }
            
            var proxyBase = "http://$hostIp:" + com.cncverse.stremiobridge.state.ServerState.serverPort
            val activeTunnel = com.cncverse.stremiobridge.state.ServerState.activeTunnelUrl.value
            if (com.cncverse.stremiobridge.state.ServerState.isStremioMode.value && !activeTunnel.isNullOrBlank()) {
                proxyBase = activeTunnel
            }
            val headerParams = finalHeaders.entries.joinToString("") { (key, value) ->
                "&h_${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
            }
            val clearKeyParam = if (!clearkeyHex.isNullOrBlank()) "&clearkey=$clearkeyHex" else ""
            finalUrl = "$proxyBase/proxy/mpd/manifest.m3u8?d=$encodedMpdUrl$clearKeyParam$headerParams"
            ServerState.info("Rewrote MPD to Proxy: $finalUrl")
        }

        // If we rewrote the URL (e.g. for MPD), our own proxy handles the headers, so don't ask Stremio to proxy it again.
        val passProxyHeaders = finalHeaders.isNotEmpty() && url == finalUrl
        
        listOf(StremioStream(
            name = streamName, title = n, url = finalUrl,
            behaviorHints = if (passProxyHeaders) StreamBehaviorHints(proxyHeaders = ProxyHeaders(finalHeaders)) else null,
            clearkey = clearkeyHex
        ))
    } catch (e: Exception) {
        ServerState.warn("reflectToStreams exception plugin=$pluginName class=${this.javaClass.name}: ${e.message}")
        emptyList()
    }
}

private fun Any.reflectToSubtitle(pluginName: String, api: Any? = null): StremioSubtitle? {
    return try {
        val cls = this.javaClass
        val lang = runCatching { cls.getMethod("getLang").invoke(this) as? String }
            .recoverCatching { cls.getField("lang").get(this) as? String }
            .getOrNull() ?: "Unknown"

        val rawUrl = runCatching { cls.getMethod("getUrl").invoke(this) as? String }
            .recoverCatching { cls.getField("url").get(this) as? String }
            .getOrNull() ?: return null

        val h = runCatching { @Suppress("UNCHECKED_CAST") (cls.getMethod("getHeaders").invoke(this) as? Map<String, String>) }
            .recoverCatching { @Suppress("UNCHECKED_CAST") (cls.getField("headers").get(this) as? Map<String, String>) }
            .getOrNull()
            
        val hostIp = try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().asSequence().toList()
            val preferred = interfaces.filter { iface ->
                iface.isUp && !iface.isLoopback && !iface.isPointToPoint &&
                (iface.name.contains("wlan", ignoreCase = true) ||
                 iface.name.contains("eth", ignoreCase = true) ||
                 iface.name.contains("en", ignoreCase = true))
            }
            val candidates = if (preferred.isNotEmpty()) preferred else interfaces.filter { iface ->
                iface.isUp && !iface.isLoopback && !iface.isPointToPoint &&
                !iface.name.contains("p2p", ignoreCase = true) &&
                !iface.name.contains("dummy", ignoreCase = true) &&
                !iface.name.contains("tun", ignoreCase = true) &&
                !iface.name.contains("rmnet", ignoreCase = true)
            }
            candidates
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                .map { it.hostAddress }
                .sorted()
                .firstOrNull() ?: "127.0.0.1"
        } catch (e: Exception) { "127.0.0.1" }
        
        var proxyBase = "http://$hostIp:" + com.cncverse.stremiobridge.state.ServerState.serverPort
        val activeTunnel = com.cncverse.stremiobridge.state.ServerState.activeTunnelUrl.value
        if (com.cncverse.stremiobridge.state.ServerState.isStremioMode.value && !activeTunnel.isNullOrBlank()) {
            proxyBase = activeTunnel
        }

        val headerParams = h?.entries?.joinToString("") { (key, value) ->
            "&h_${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
        } ?: ""

        val encodedUrl = java.net.URLEncoder.encode(rawUrl, "UTF-8")
        val finalUrl = "$proxyBase/proxy/subtitle?url=$encodedUrl$headerParams"
        
        val id = lang.lowercase().replace(" ", "_") + "_" + rawUrl.hashCode().toString(16)

        StremioSubtitle(
            id = id,
            lang = lang,
            url = finalUrl
        )
    } catch (e: Exception) {
        null
    }
}

private class MetadataOnlyWrapper(private val plugin: SitePlugin) : MainApiWrapper {
    override val name: String get() = plugin.name
    override val internalName: String get() = plugin.internalName
    override val supportedTypes: List<String> get() = plugin.tvTypes ?: listOf("movie")
    override suspend fun getMainPageSections(): List<String> = emptyList()
    override suspend fun search(query: String): List<SearchResult> = emptyList()
    override suspend fun getMainPage(page: Int, type: String, sectionName: String?): List<SearchResult> = emptyList()
    override suspend fun load(url: String): MediaInfo? = null
    override suspend fun loadLinks(dataUrl: String): List<StremioStream> = emptyList()
}

private class DirectMainApiWrapper(
    private val api: com.lagradost.cloudstream3.MainAPI,
    private val plugin: SitePlugin
) : MainApiWrapper {
    override val name: String get() = api.name
    override val internalName: String get() = plugin.internalName + "_" + StremioServer.publicNameSlug(api.name)
    override val supportedTypes: List<String> get() {
        val hasLive = api.supportedTypes.any { it.name.equals("Live", ignoreCase = true) }
        val isOnlyLive = api.supportedTypes.size == 1 && hasLive
        return when {
            isOnlyLive -> listOf("tv")
            hasLive -> listOf("movie", "tv")
            else -> listOf("movie")
        }
    }
    
    private var dynamicSectionsCache: List<String>? = null
 
    override fun clearCache() {
        dynamicSectionsCache = null
    }

    override suspend fun getMainPageSections(): List<String> {
        if (dynamicSectionsCache != null) return dynamicSectionsCache!!

        val staticSections = api.mainPage.map { it.name }

        if (staticSections.size <= 1 && staticSections.firstOrNull().isNullOrBlank()) {
            try {
                val type = supportedTypes.firstOrNull() ?: "tv"
                val results = getMainPage(1, type, null)
                val dynamicSections = results.mapNotNull { it.sectionName }.distinct()
                if (dynamicSections.isNotEmpty()) {
                    dynamicSectionsCache = dynamicSections
                    return dynamicSections
                }
            } catch (e: Exception) { }
        }

        dynamicSectionsCache = staticSections
        return staticSections
    }

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            var rawResults: List<*>? = null
            try {
                rawResults = api.search(query)
            } catch (e: NotImplementedError) {
                try {
                    val suspendResult = kotlin.coroutines.suspendCoroutine<Any?> { cont ->
                        try {
                            val method = api.javaClass.getMethod("search", String::class.java, Int::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java)
                            method.invoke(api, query, 1, cont)
                        } catch (ex: Exception) {
                            cont.resumeWith(Result.failure(ex))
                        }
                    }
                    rawResults = if (suspendResult is List<*>) {
                        suspendResult
                    } else if (suspendResult != null && suspendResult.javaClass.simpleName == "SearchResponseList") {
                        try {
                            val itemsMethod = suspendResult.javaClass.getMethod("getItems")
                            itemsMethod.invoke(suspendResult) as? List<*>
                        } catch (e: Exception) {
                            try {
                                val itemsField = suspendResult.javaClass.getDeclaredField("items")
                                itemsField.isAccessible = true
                                itemsField.get(suspendResult) as? List<*>
                            } catch (e2: Exception) { null }
                        }
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    rawResults = null
                }
            }
            
            if (rawResults != null) {
                rawResults.mapNotNull { (it as Any).reflectToSearchResult() }
            } else {
                emptyList()
            }
        } catch (e: Throwable) {
            ServerState.warn("Search error (${plugin.name}): ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, type: String, sectionName: String?): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val mainPageDataList = api.mainPage
            val allResults = mutableListOf<SearchResult>()
            val isDynamic = mainPageDataList.size <= 1 && mainPageDataList.firstOrNull()?.name.isNullOrBlank()
            
            for (data in mainPageDataList) {
                if (!isDynamic && sectionName != null && data.name != sectionName) continue
                try {
                    val request = com.lagradost.cloudstream3.MainPageRequest(
                        name = data.name, 
                        data = data.data, 
                        horizontalImages = data.horizontalImages
                    )
                    
                    val response = api.getMainPage(page, request)
                    val items = response?.items ?: emptyList()
                    
                    for (homePageList in items) {
                        val isHorizontal = try {
                            homePageList.javaClass.getMethod("isHorizontalImages").invoke(homePageList) as? Boolean
                                ?: (homePageList.javaClass.getDeclaredField("isHorizontalImages").also { it.isAccessible = true }.get(homePageList) as? Boolean)
                                ?: data.horizontalImages
                        } catch (_: Exception) { data.horizontalImages }
                        val actualSectionName = try {
                            homePageList.javaClass.getMethod("getName").invoke(homePageList) as? String
                                ?: (homePageList.javaClass.getDeclaredField("name").also { it.isAccessible = true }.get(homePageList) as? String)
                        } catch (_: Exception) { null } ?: data.name

                        if (sectionName != null && actualSectionName != sectionName) continue

                        val list = homePageList.list ?: emptyList()
                        val mapped = list.mapNotNull {
                            (it as Any).reflectToSearchResult()
                                ?.copy(isHorizontal = isHorizontal, sectionName = actualSectionName)
                        }
                        allResults.addAll(mapped)
                    }
                } catch (e: Throwable) {
                    ServerState.warn("getMainPage section '${data.name}' error (${plugin.name}): ${e.message}")
                }
            }
            
            val allNamesBlank = allResults.isNotEmpty() && allResults.all { it.name.length <= 6 && it.name.all { c -> c.isDigit() } }
            
            if ((allResults.isEmpty() || allNamesBlank) && page == 1) {
                ServerState.info("getMainPage: all items had no titles (${plugin.name}), falling back to search")
                search("")
            } else {
                allResults.distinctBy { it.url }
            }
        } catch (e: Throwable) {
            ServerState.warn("getMainPage error (${plugin.name}): ${e.message}")
            if (page == 1) search("") else emptyList()
        }
    }

    override suspend fun load(url: String): MediaInfo? = withContext(Dispatchers.IO) {
        try {
            val resp = api.load(url) ?: return@withContext null
            (resp as Any).reflectToMediaInfo(url)
        } catch (e: Throwable) {
            ServerState.warn("Load error (${plugin.name}): ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(dataUrl: String): List<StremioStream> = withContext(Dispatchers.IO) {
        val streams = mutableListOf<StremioStream>()
        val subtitles = mutableListOf<StremioSubtitle>()
        ServerState.info("loadLinks called: plugin=${plugin.name} dataUrl=$dataUrl")
        var callbackCount = 0
        val seenSubtitleUrls = mutableSetOf<String>()
        val seenStreamUrls = mutableSetOf<String>()
        try {
            kotlinx.coroutines.withTimeoutOrNull(20_000) {
                api.loadLinks(dataUrl, false, { sub ->
                    val stremioSub = (sub as Any).reflectToSubtitle(plugin.name, api)
                    if (stremioSub != null && seenSubtitleUrls.add(stremioSub.url)) {
                        ServerState.info("subtitle plugin=${plugin.name} lang=${stremioSub.lang} url=${stremioSub.url}")
                        subtitles.add(stremioSub)
                    }
                }, { link ->
                    callbackCount++
                    val newStreams = (link as Any).reflectToStreams(plugin.name, api)
                    newStreams.forEach { st ->
                        val u = st.url ?: st.hashCode().toString()
                        if (seenStreamUrls.add(u)) {
                            ServerState.info("stream plugin=${plugin.name} name=${st.name} url=${st.url}")
                            streams.add(st)
                        }
                    }
                })
            }
        } catch (e: Throwable) {
            val cause = e.cause?.message ?: e.message
            ServerState.warn("LoadLinks error (${plugin.name}): $cause | dataUrl=$dataUrl")
        }
        
        val uniqueSubtitles = subtitles.distinctBy { it.url }
        val uniqueStreams = streams.distinctBy { it.url ?: it.hashCode().toString() }
        val finalStreams = if (uniqueSubtitles.isNotEmpty()) {
            uniqueStreams.map { it.copy(subtitles = uniqueSubtitles.toList()) }
        } else {
            uniqueStreams
        }
        
        ServerState.info("loadLinks result: plugin=${plugin.name} callbackCount=$callbackCount streams=${finalStreams.size} subtitles=${subtitles.size}")
        finalStreams
    }
}




