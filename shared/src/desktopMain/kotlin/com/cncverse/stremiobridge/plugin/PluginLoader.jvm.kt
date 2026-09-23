package com.cncverse.stremiobridge.plugin

import com.cncverse.stremiobridge.model.SitePlugin
import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.model.StreamBehaviorHints
import com.cncverse.stremiobridge.model.ProxyHeaders
import com.cncverse.stremiobridge.model.StremioSubtitle
import com.cncverse.stremiobridge.model.toLoadedPluginInfo
import com.cncverse.stremiobridge.model.cs3TvTypeToStremio
import com.cncverse.stremiobridge.network.AdaptiveHostDns
import com.cncverse.stremiobridge.server.MainApiWrapper
import com.cncverse.stremiobridge.server.MediaInfo
import com.cncverse.stremiobridge.server.MediaInfoEpisode
import com.cncverse.stremiobridge.server.SearchResult
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.state.LoadedPluginInfo
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.tunnel.CloudflaredManager
import com.googlecode.dex2jar.tools.Dex2jarCmd
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

private val manifestJson = Json { ignoreUnknownKeys = true }
private val looseJson = Json { ignoreUnknownKeys = true }

/**
 * JVM/Desktop actual implementation of [PluginLoader].
 *
 * .cs3 files contain DEX bytecode, so we convert them to JVM jars with dex2jar
 * and load them via [URLClassLoader]. The parent classloader carries the
 * cloudstream-api.jar, NiceHttp, OkHttp, etc., so plugin classes resolve the
 * shared CloudStream types from the host — which means registered providers
 * are real [MainAPI] instances and can be called directly (no reflection).
 */
/** Cache/transformer version — bump when PluginBytecodeTransformer behavior changes. */
private const val TRANSFORMER_VERSION = PluginBytecodeTransformer.VERSION

actual class PluginLoader {

    private val loadedClassLoaders = mutableListOf<URLClassLoader>()
    private val loadedPlugins: MutableMap<String, Any> = mutableMapOf()
    private val registeredApis: MutableList<Any> = mutableListOf()

    actual suspend fun loadPlugins(
        plugins: List<SitePlugin>,
        cs3Files: Map<String, File>,
    ): List<LoadedPluginInfo> = withContext(Dispatchers.IO) {
        configureAppClientNetwork()
        val loadedList = plugins.mapNotNull { plugin ->
            val cs3File = cs3Files[plugin.internalName] ?: run {
                ServerState.warn("No .cs3 for '${plugin.name}', skipping")
                return@mapNotNull plugin.toLoadedPluginInfo(apiRegistered = false)
            }
            val info = loadSinglePlugin(cs3File, plugin)
            // Patch plugin-specific classes AFTER the plugin's classloader is active
            configurePluginNetworkPostLoad()
            info
        }
        preWarmPluginHosts()
        loadedList
    }

    /** Reconfigures the global NiceHttp `app` client used by plugins with AdaptiveHostDns + CloudflareKiller. */
    private fun configureAppClientNetwork() {
        val cfKiller = com.lagradost.cloudstream3.network.CloudflareKiller()

        // ── 1. Patch the global `app` (MainActivityKt.getApp()) ──────────────
        try {
            val mainActivityKt = Class.forName("com.lagradost.cloudstream3.MainActivityKt")
            val currentNiceClient = mainActivityKt.getMethod("getApp").invoke(null) ?: return

            val okClientField = currentNiceClient.javaClass.declaredFields.firstOrNull {
                it.type.name.contains("OkHttpClient")
            } ?: return
            okClientField.isAccessible = true
            val existingOk = okClientField.get(currentNiceClient) as? okhttp3.OkHttpClient ?: return

            val newOk = existingOk.newBuilder()
                .addInterceptor(cfKiller)
                .dns(AdaptiveHostDns)
                .fastFallback(true)
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(okhttp3.ConnectionPool(50, 90, java.util.concurrent.TimeUnit.SECONDS))
                .eventListenerFactory(LeakSafeEventListener.FACTORY)
                .build()
            okClientField.set(currentNiceClient, newOk)
            ServerState.info("Configured AdaptiveHostDns + CloudflareKiller on app.client (global)")
        } catch (t: Throwable) {
            ServerState.warn("Failed to configure global app.client: ${t.message}")
        }

        // ── 2. Patch NiceHttp Requests.baseClient (companion/static) ─────────
        // Plugin-local `val app = Requests(...)` uses Requests.baseClient as the
        // underlying OkHttpClient. Patching the companion object field means ALL
        // Requests instances created after this point inherit the interceptor.
        try {
            val requestsClass = Class.forName("com.lagradost.nicehttp.Requests")
            // Try companion object first (Kotlin companion)
            val companionField = runCatching {
                requestsClass.getDeclaredField("Companion").also { it.isAccessible = true }
            }.getOrNull()
            val target = companionField?.get(null) ?: requestsClass

            val baseClientField = (target.javaClass.declaredFields + requestsClass.declaredFields)
                .firstOrNull { it.type.name.contains("OkHttpClient") }
                ?.also { it.isAccessible = true }

            if (baseClientField != null) {
                val existing = baseClientField.get(target) as? okhttp3.OkHttpClient
                    ?: baseClientField.get(null) as? okhttp3.OkHttpClient
                if (existing != null) {
                    // Only add if not already present
                    val alreadyHas = existing.interceptors.any { it is com.lagradost.cloudstream3.network.CloudflareKiller }
                    if (!alreadyHas) {
                        val patched = existing.newBuilder()
                            .addInterceptor(cfKiller)
                            .dns(AdaptiveHostDns)
                            .connectionPool(okhttp3.ConnectionPool(50, 90, java.util.concurrent.TimeUnit.SECONDS))
                            .eventListenerFactory(LeakSafeEventListener.FACTORY)
                            .build()
                        runCatching { baseClientField.set(target, patched) }
                        runCatching { baseClientField.set(null, patched) }
                        ServerState.info("Configured CloudflareKiller on NiceHttp Requests.baseClient")
                    }
                }
            } else {
                ServerState.warn("[CF-DBG] Could not find OkHttpClient field in NiceHttp Requests")
            }
        } catch (t: Throwable) {
            ServerState.warn("Failed to patch NiceHttp Requests.baseClient: ${t.message}")
        }

        // Steps 3 & 4 (plugin-specific patching) are deferred to
        // configurePluginNetworkPostLoad() which runs after each plugin's
        // classloader is set up.
    }

    /**
     * Called after each plugin's classloader is active.
     * Patches the plugin's own `app` (Requests) OkHttp client and injects
     * DesktopContext into NetflixMirrorProvider so WebView-based CF bypass works.
     */
    private fun configurePluginNetworkPostLoad() {
        val cfKiller = com.lagradost.cloudstream3.network.CloudflareKiller()

        // Search all loaded plugin classloaders for the CNC Verse plugin classes
        for (cl in loadedClassLoaders) {
            // ── 3. Patch plugin `val app = Requests(...)` ─────────────────────
            runCatching {
                val clazz = cl.loadClass("com.horis.cncverse.UtilsKt")
                val appField = clazz.declaredFields.firstOrNull { f ->
                    f.name == "app" || f.type.name.contains("Requests")
                }?.also { it.isAccessible = true } ?: return@runCatching
                val requests = appField.get(null) ?: return@runCatching
                val okField = requests.javaClass.declaredFields.firstOrNull {
                    it.type.name.contains("OkHttpClient")
                }?.also { it.isAccessible = true } ?: return@runCatching
                val existing = okField.get(requests) as? okhttp3.OkHttpClient ?: return@runCatching
                if (existing.interceptors.none { it is com.lagradost.cloudstream3.network.CloudflareKiller }) {
                    val patched = existing.newBuilder().addInterceptor(cfKiller).build()
                    okField.set(requests, patched)
                    ServerState.info("[CF] Patched CloudflareKiller into com.horis.cncverse.UtilsKt.app")
                }
            }

            // ── 4. Inject DesktopContext into NetflixMirrorProvider ───────────
            // Without a non-null context, solveCloudflareInWebView() returns null
            // immediately and the WebView stub's CDP bridge is never reached.
            runCatching {
                val providerClass = cl.loadClass("com.horis.cncverse.NetflixMirrorProvider")
                val companionField = runCatching {
                    providerClass.getDeclaredField("Companion").also { it.isAccessible = true }
                }.getOrNull()
                val companion = companionField?.get(null) ?: return@runCatching

                val ctxField = (companion.javaClass.declaredFields + providerClass.declaredFields)
                    .firstOrNull { it.name == "context" }
                    ?.also { it.isAccessible = true } ?: return@runCatching

                val current = runCatching { ctxField.get(companion) }.getOrNull()
                if (current == null) {
                    ctxField.set(companion, android.content.DesktopContext)
                    ServerState.info("[CF] Set NetflixMirrorProvider.context = DesktopContext")
                }
            }
        }
    }

    private suspend fun preWarmPluginHosts() {
        withContext(Dispatchers.IO) {
            try {
                registeredApis.forEach { api ->
                    val mainUrl = (api as? MainAPI)?.mainUrl ?: return@forEach
                    if (mainUrl.isNotBlank()) {
                        val host = runCatching { java.net.URI(mainUrl).host }.getOrNull()
                        if (!host.isNullOrBlank()) {
                            AdaptiveHostDns.preWarmHost(host)
                        }
                    }
                }
            } catch (t: Throwable) {
                ServerState.warn("Pre-warming plugin hosts encountered an error: ${t.message}")
            }
        }
    }

    private fun loadSinglePlugin(cs3File: File, data: SitePlugin): LoadedPluginInfo {
        return try {
            // ── Step 1: Convert .cs3 (DEX) → .jar using dex2jar ─────────────
            val jarFile = convertToJar(cs3File) ?: run {
                ServerState.error("dex2jar conversion failed for '${data.name}'")
                StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
                return data.toLoadedPluginInfo(apiRegistered = false)
            }

            // ── Step 2: Read manifest.json from inside the .cs3 (ZIP format) ─
            val manifest = readManifestFromCs3(cs3File) ?: run {
                ServerState.error("No manifest.json inside '${data.name}'")
                StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
                return data.toLoadedPluginInfo(apiRegistered = false)
            }
            val pluginClassName = manifest.pluginClassName ?: run {
                ServerState.error("No pluginClassName in manifest for '${data.name}'")
                StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
                return data.toLoadedPluginInfo(apiRegistered = false)
            }

            // ── Step 3: Build URLClassLoader with converted JAR ───────────────
            // The parent classloader carries the cloudstream-api, Ktor, OkHttp,
            // coroutines etc. shared with the plugin.
            val urls = arrayOf(jarFile.toURI().toURL())
            val classLoader = object : URLClassLoader(urls, PluginLoader::class.java.classLoader) {
                override fun findClass(name: String): Class<*> {
                    return try {
                        super.findClass(name)
                    } catch (e: ClassNotFoundException) {
                        if (name.startsWith("android.") || name.startsWith("androidx.") || name.startsWith("com.google.android.material.")) {
                            val bytes = generateStubClass(name)
                            defineClass(name, bytes, 0, bytes.size)
                        } else {
                            throw e
                        }
                    }
                }

                private fun generateStubClass(name: String): ByteArray {
                    val internalName = name.replace('.', '/')
                    val cw = org.objectweb.asm.ClassWriter(0)
                    cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)

                    // Default constructor ()V
                    var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
                    mv.visitCode()
                    mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                    mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                    mv.visitInsn(org.objectweb.asm.Opcodes.RETURN)
                    mv.visitMaxs(1, 1)
                    mv.visitEnd()

                    // Constructor (Context)V
                    mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(Landroid/content/Context;)V", null, null)
                    mv.visitCode()
                    mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                    mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                    mv.visitInsn(org.objectweb.asm.Opcodes.RETURN)
                    mv.visitMaxs(1, 2)
                    mv.visitEnd()

                    // Constructor (Context, AttributeSet)V
                    mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(Landroid/content/Context;Landroid/util/AttributeSet;)V", null, null)
                    mv.visitCode()
                    mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                    mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                    mv.visitInsn(org.objectweb.asm.Opcodes.RETURN)
                    mv.visitMaxs(1, 3)
                    mv.visitEnd()

                    cw.visitEnd()
                    return cw.toByteArray()
                }
            }
            loadedClassLoaders.add(classLoader)
            PluginCallContext.classLoaders[classLoader] = data.internalName

            // Snapshot providers BEFORE load() so the diff below only picks up
            // what this plugin registers (the old code diffed after load()).
            // getAllProviders is read reflectively: the jar's Kotlin metadata
            // does not expose it to direct Kotlin calls.
            val apisBefore = getApiHolderProviders().toSet()

            // ── Step 4: Instantiate plugin class ─────────────────────────────
            val pluginClass = classLoader.loadClass(pluginClassName)
            val pluginInstance = pluginClass.getDeclaredConstructor().newInstance()

            // Set filename field via reflection (BasePlugin.filename property)
            setFieldViaReflection(pluginInstance, "filename", cs3File.absolutePath)

            // Attach .cs3-backed resources so resource-based settings UIs work
            if (PluginResources.attachIfPresent(cs3File, classLoader, pluginInstance)) {
                // Expose them to the AXML inflater too (layout/settings fragments)
                var resCls: Class<*>? = pluginInstance.javaClass
                var pluginResources: PluginResources? = null
                while (resCls != null && pluginResources == null) {
                    runCatching {
                        val f = resCls!!.getDeclaredField("resources")
                        f.isAccessible = true
                        (f.get(pluginInstance) as? PluginResources)?.let { pluginResources = it }
                    }
                    resCls = resCls?.superclass
                }
                pluginResources?.let {
                    com.cncverse.stremiobridge.shadowui.ShadowResourceRegistry.register(data.internalName, it)
                }
            }

            // ── Step 5: Call load() — prefer load(Context), fall back to load() ─
            val loadMethodWithContext = runCatching {
                pluginClass.getMethod("load", android.content.Context::class.java)
            }.getOrNull()
                ?: findMethodInHierarchy(pluginClass, "load", android.content.Context::class.java)
            val noArgLoad = runCatching { pluginClass.getMethod("load") }.getOrNull()
                ?: findMethodInHierarchy(pluginClass, "load")

            if (loadMethodWithContext != null) {
                // load(Context) with our desktop context (an AppCompatActivity
                // stub, so plugin casts to Activity succeed). Spread is
                // required: passing the array directly would send it as one arg.
                loadMethodWithContext.invoke(pluginInstance, android.content.DesktopContext)
            } else {
                noArgLoad?.invoke(pluginInstance)
            }

            loadedPlugins[data.internalName] = pluginInstance

            // Settings are discoverable two ways: the plugin declares an openSettings
            // UI, or it reads its DataStore keys during load() (dynamically registered
            // in the schema registry via CloudStreamApp.getKey).
            val declaresSettings = getOpenSettings(pluginInstance) != null
            val hasSettings = declaresSettings ||
                com.cncverse.stremiobridge.settings.PluginSettingsSchemaRegistry.hasSettings(data.internalName)

            val newApis = getApiHolderProviders().filterNot { apisBefore.contains(it) }

            if (newApis.isEmpty()) {
                ServerState.warn("Plugin '${data.name}' loaded but registered no providers")
                StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
                return data.toLoadedPluginInfo(apiRegistered = false, hasSettings = hasSettings)
            }

            newApis.forEach { api ->
                registeredApis.add(api)
                StremioServer.loadedApis.add(DirectMainApiWrapper(api as MainAPI, data))
            }

            ServerState.info("✓ Loaded '${data.name}' on desktop (${newApis.size} API(s))")
            data.toLoadedPluginInfo(apiRegistered = true, hasSettings = hasSettings)
        } catch (e: Throwable) {
            var actualException = e
            while (actualException is InvocationTargetException && actualException.cause != null) {
                actualException = actualException.cause!!
            }
            val trace = actualException.stackTraceToString().take(150).replace("\n", " ")
            ServerState.error("✗ Failed to load '${data.name}': ${actualException::class.simpleName} - ${actualException.message} | $trace")
            StremioServer.loadedApis.add(MetadataOnlyWrapper(data))
            data.toLoadedPluginInfo(apiRegistered = false)
        }
    }

    // ── dex2jar conversion ────────────────────────────────────────────────────

    /**
     * Runs dex2jar while capturing System.out/err. Dex2jarCmd.doMain prints
     * internal exceptions to stderr and returns normally — in the packaged exe
     * stderr goes nowhere, so we tee it and surface it in ServerState.
     */
    private fun runDex2jarCaptured(dexFile: File, jarFile: File): String {
        val buffer = java.io.ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        val teeOut = java.io.PrintStream(TeeOutputStream(oldOut, buffer), true)
        val teeErr = java.io.PrintStream(TeeOutputStream(oldErr, buffer), true)
        return try {
            System.setOut(teeOut)
            System.setErr(teeErr)
            try {
                Dex2jarCmd().doMain("-f", dexFile.absolutePath, "-o", jarFile.absolutePath)
            } catch (e: Throwable) {
                "exception: ${e::class.java.name}: ${e.message}"
            }
            buffer.toString(Charsets.UTF_8.name())
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
            teeOut.flush(); teeErr.flush()
        }
    }

    /** Copies to a backing stream while buffering everything for later inspection. */
    private class TeeOutputStream(
        private val backing: java.io.OutputStream,
        private val buffer: java.io.ByteArrayOutputStream,
    ) : java.io.OutputStream() {
        @Synchronized
        override fun write(b: Int) {
            runCatching { backing.write(b) }
            buffer.write(b)
        }

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            runCatching { backing.write(b, off, len) }
            buffer.write(b, off, len)
        }

        override fun flush() {
            runCatching { backing.flush() }
        }
    }

    private fun convertToJar(cs3File: File): File? {
        // Cache name carries the transformer version — bump it whenever
        // PluginBytecodeTransformer changes so stale jars are never reused.
        val jarFile = File(cs3File.parentFile, "${cs3File.nameWithoutExtension}-jvm$TRANSFORMER_VERSION.jar")

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

            // Step 2: convert .dex → .jar using dex2jar (output captured so
            // failures aren't silent — dex2jar swallows exceptions to std err)
            val dex2jarOutput = runDex2jarCaptured(dexFile, jarFile)
            if (dex2jarOutput.isNotBlank() && (dex2jarOutput.contains("Exception") || dex2jarOutput.contains("Error"))) {
                ServerState.error("dex2jar reported for ${cs3File.name}: ${dex2jarOutput.take(600).replace("\n", " | ")}")
            }

            dexFile.delete() // clean up temp file

            if (jarFile.exists() && jarFile.length() > 0L) {
                // Neuter Android UI calls / fix inline-class names so the JVM accepts it
                runCatching { PluginBytecodeTransformer.transform(jarFile) }
                    .onFailure { ServerState.warn("Bytecode transform failed for ${cs3File.name}: ${it.message}") }
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

    // ── Reflection helpers ────────────────────────────────────────────────────

    /**
     * Reads APIHolder.allProviders reflectively. The provider list is private
     * in the jar's Kotlin metadata, so direct property access does not compile.
     */
    private fun getApiHolderProviders(): List<Any> {
        return try {
            val holderClass = Class.forName("com.lagradost.cloudstream3.APIHolder")
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

    @Suppress("UNCHECKED_CAST")
    private fun getOpenSettings(plugin: Any): ((android.content.Context) -> Unit)? {
        if (plugin is Plugin) return plugin.openSettings
        return runCatching {
            plugin.javaClass.getMethod("getOpenSettings").invoke(plugin) as? (android.content.Context) -> Unit
        }.getOrNull()
    }

    actual fun getRegisteredApis(): List<Any> = registeredApis.toList()

    /** Returns the loaded plugin instance for a given internal name (for settings). */
    fun getPluginInstance(internalName: String): Any? = loadedPlugins[internalName]

    actual fun openPluginSettings(internalName: String, activityContext: Any?) {
        // Full settings UI: run the plugin's openSettings lambda against the
        // recording stubs. Every AlertDialog.show()/DialogFragment.show() it
        // performs is captured by ShadowUi and rendered natively by Compose;
        // DataStore key reads still register in the schema registry as a
        // fallback for plugins without UI code.
        val pluginInstance = loadedPlugins[internalName] ?: run {
            ServerState.warn("Settings unavailable: '$internalName' is not loaded")
            return
        }
        val openSettings = getOpenSettings(pluginInstance) ?: run {
            ServerState.info("'$internalName' declares no settings UI — schema fallback only")
            return
        }
        com.cncverse.stremiobridge.shadowui.ShadowUi.beginSession(internalName)
        val context = activityContext as? android.content.Context ?: android.content.DesktopContext
        try {
            openSettings(context)
            ServerState.info("Executed settings UI code for '$internalName'")
        } catch (t: Throwable) {
            ServerState.warn("Settings UI execution for '$internalName' failed: ${t.message}")
        } finally {
            com.cncverse.stremiobridge.shadowui.ShadowUi.finishSessionDelayed()
        }
    }

    actual fun unloadAll() {
        StremioServer.loadedApis.clear()
        registeredApis.clear()
        loadedPlugins.clear()
        loadedClassLoaders.forEach { runCatching { it.close() } }
        loadedClassLoaders.clear()
        PluginCallContext.classLoaders.clear()
        ServerState.info("All plugins unloaded")
    }
}

/**
 * OkHttp EventListener that auto-closes any [Response] body left open when
 * a [Call] finishes (normally or with a failure). Plugins call NiceHttp inside
 * coroutine callbacks and sometimes exit before consuming/closing the body
 * (e.g. on timeout or early-return), which OkHttp then logs as a leak warning
 * and leaves the underlying connection checked-out from the pool indefinitely.
 *
 * This listener tracks the most-recent response per call (via WeakReference so
 * the Call itself can be GC'd without a strong reference cycle) and closes it
 * in [callEnd]/[callFailed] if OkHttp's internal isCanceled/consumed flag is
 * not already set.
 */
private class LeakSafeEventListener : EventListener() {

    // Map from Call to the latest Response received for that call.
    // ConcurrentHashMap + WeakReference so completed calls are GC-eligible.
    private val liveResponses = ConcurrentHashMap<Call, WeakReference<Response>>()

    override fun responseHeadersEnd(call: Call, response: Response) {
        liveResponses[call] = WeakReference(response)
    }

    override fun callEnd(call: Call) {
        closeIfLeaked(call)
    }

    override fun callFailed(call: Call, ioe: java.io.IOException) {
        closeIfLeaked(call)
    }

    private fun closeIfLeaked(call: Call) {
        val ref = liveResponses.remove(call) ?: return
        val response = ref.get() ?: return
        try {
            val body = response.body ?: return
            // Only close if the body source is not already exhausted.
            // Calling close() on an already-exhausted body is a no-op in OkHttp.
            body.close()
        } catch (_: Throwable) {
            // Best-effort: ignore errors closing an already-closed body.
        }
    }

    companion object {
        /** Singleton factory — one listener instance per call (stateful, not shared). */
        val FACTORY: Factory = Factory { LeakSafeEventListener() }
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

// ── Direct MainApiWrapper (typed calls against cloudstream-api.jar) ──────────

/**
 * Wraps a [MainAPI] instance loaded from a converted plugin jar.
 * The plugin classloader delegates CloudStream types to the host classpath,
 * so the instance shares our [MainAPI] class and can be called directly.
 */
private class DirectMainApiWrapper(
    private val api: MainAPI,
    private val plugin: SitePlugin,
) : MainApiWrapper {
    override val name: String get() = api.name
    override val internalName: String get() = plugin.internalName + "_" + StremioServer.publicNameSlug(api.name)
    override val pluginInternalName: String get() = plugin.internalName
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
            } catch (_: Exception) { }
        }

        dynamicSectionsCache = staticSections
        return staticSections
    }

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val rawResults: List<*>? = try {
                api.search(query)
            } catch (e: NotImplementedError) {
                // Provider only implements the paged overload
                runCatching { api.search(query, 1)?.items }.getOrNull()
            }
            rawResults?.mapNotNull { (it as Any).reflectToSearchResult() } ?: emptyList()
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
                    val request = MainPageRequest(
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
                api.loadLinks(dataUrl, false, { sub: SubtitleFile ->
                    val stremioSub = (sub as Any).reflectToSubtitle(plugin.name)
                    if (stremioSub != null && seenSubtitleUrls.add(stremioSub.url)) {
                        ServerState.info("subtitle plugin=${plugin.name} lang=${stremioSub.lang} url=${stremioSub.url}")
                        subtitles.add(stremioSub)
                    }
                }, { link: ExtractorLink ->
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

// ── Reflection mappers for CS3 response types (same as Android) ───────────────

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
            val derivedName = if (!url.isNullOrBlank()) {
                runCatching {
                    val json = looseJson.parseToJsonElement(url)
                    val obj = json as? kotlinx.serialization.json.JsonObject
                    (obj?.get("title") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                        ?: (obj?.get("t") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
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

private fun Any.reflectToStreams(pluginName: String, api: MainAPI? = null): List<StremioStream> {
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

        // Apply the provider's video interceptor (if any) so headers match what
        // the plugin's own player would send.
        if (api != null) {
            try {
                val interceptor = api.getVideoInterceptor(this as ExtractorLink)
                if (interceptor != null) {
                    val fakeRequest = Request.Builder().url(url).build()
                    val chainClass = okhttp3.Interceptor.Chain::class.java
                    val fakeChain = java.lang.reflect.Proxy.newProxyInstance(
                        chainClass.classLoader,
                        arrayOf(chainClass)
                    ) { _, method, args ->
                        when (method.name) {
                            "request" -> fakeRequest
                            "proceed" -> {
                                val modifiedReq = args[0]
                                val headersObj = (modifiedReq as Request).headers
                                for ((k, v) in headersObj) {
                                    finalHeaders[k] = v
                                }
                                throw RuntimeException("Fake chain abort")
                            }
                            else -> null
                        }
                    }

                    try {
                        interceptor.intercept(fakeChain as okhttp3.Interceptor.Chain)
                    } catch (e: Exception) {
                        if (e.cause?.message != "Fake chain abort" && e.message != "Fake chain abort") {
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
                fun toHex(input: String): String {
                    val stripped = input.trim().replace("-", "")
                    // If it looks like a plain hex string already, use it directly
                    if (stripped.length % 2 == 0 && stripped.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                        return stripped.lowercase()
                    }
                    val padded = input.replace('-', '+').replace('_', '/')
                    val withPadding = padded + "=".repeat((4 - padded.length % 4) % 4)
                    val bytes = Base64.getDecoder().decode(withPadding)
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
            val hostIp = CloudflaredManager.deviceIp.ifBlank { "127.0.0.1" }

            var proxyBase = ServerState.publicBaseUrl.ifBlank { "http://$hostIp:${ServerState.serverPort}" }
            val activeTunnel = ServerState.activeTunnelUrl.value
            if (ServerState.isStremioMode.value && !activeTunnel.isNullOrBlank()) {
                proxyBase = activeTunnel
            }
            val headerParams = finalHeaders.entries.joinToString("") { (key, value) ->
                "&h_${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
            }
            val clearKeyParam = if (!clearkeyHex.isNullOrBlank()) "&clearkey=$clearkeyHex" else ""
            finalUrl = "$proxyBase/proxy/mpd/manifest.m3u8?d=$encodedMpdUrl$clearKeyParam$headerParams"
            ServerState.info("Rewrote MPD to Proxy: $finalUrl")
        }

        // If we rewrote the URL (e.g. for MPD), our own proxy handles the headers,
        // so don't ask Stremio to proxy it again.
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

private fun Any.reflectToSubtitle(pluginName: String): StremioSubtitle? {
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

        val hostIp = CloudflaredManager.deviceIp.ifBlank { "127.0.0.1" }

        var proxyBase = ServerState.publicBaseUrl.ifBlank { "http://$hostIp:${ServerState.serverPort}" }
        val activeTunnel = ServerState.activeTunnelUrl.value
        if (ServerState.isStremioMode.value && !activeTunnel.isNullOrBlank()) {
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

// ── Metadata-only fallback ────────────────────────────────────────────────────

/**
 * Used when dex2jar conversion or plugin loading fails.
 * Shows the plugin in the UI but returns empty results from Stremio endpoints.
 */
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
