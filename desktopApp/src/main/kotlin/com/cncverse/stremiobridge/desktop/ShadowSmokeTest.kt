package com.cncverse.stremiobridge.desktop

import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import com.cncverse.stremiobridge.model.SitePlugin
import com.cncverse.stremiobridge.plugin.PluginLoader
import com.cncverse.stremiobridge.shadowui.ShadowUi
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Headless smoke test: loads CineStream + StreamPlay .cs3 plugins, runs their
 * openSettings against the recording stubs, and verifies the shadow dialog
 * stack captures the full UI. No Compose — pure recording-layer verification.
 *
 * Usage: shadowTest <dir-with-.cs3-files> <InternalName> [InternalName2 ...]
 */
fun main(args: Array<String>) {
    val dir = args.getOrNull(0) ?: run {
        System.err.println("Usage: shadowTest <cs3-dir> <internalName> [more...]")
        exitProcess(2)
    }
    val pluginNames = args.drop(1).ifEmpty { listOf("CineStream", "StreamPlay") }

    val loader = PluginLoader()
    var failures = 0

    for (internalName in pluginNames) {
        println("\n════════════════════════════════════════════════════")
        println("Testing settings UI for: $internalName")
        println("════════════════════════════════════════════════════")

        val cs3 = File(dir, "$internalName.cs3")
        if (!cs3.exists()) {
            println("✗ MISSING .cs3 file: ${cs3.absolutePath}")
            failures++
            continue
        }

        ShadowUi.endSession()

        val sitePlugin = SitePlugin(
            url = "file://${cs3.absolutePath}",
            name = internalName,
            internalName = internalName,
            description = "smoke test",
        )

        try {
            val infos = runBlocking { loader.loadPlugins(listOf(sitePlugin), mapOf(internalName to cs3)) }
            println("Loaded ${infos.size} plugin info(s)")
        } catch (t: Throwable) {
            println("✗ Load failed: ${t::class.simpleName}: ${t.message}")
            t.printStackTrace()
            failures++
            continue
        }

        // Debug: inflate the fragment layout like the plugin does
        if (System.getenv("SHADOW_DEBUG_LAYOUT") != null) {
            try {
                val resField = loader.getPluginInstance(internalName)?.javaClass
                var resObj: Any? = null
                var cls: Class<*>? = resField
                while (cls != null && resObj == null) {
                    runCatching {
                        val f = cls!!.getDeclaredField("resources")
                        f.isAccessible = true
                        resObj = f.get(loader.getPluginInstance(internalName))
                    }
                    cls = cls?.superclass
                }
                if (resObj != null) {
                    val getIdentifier = resObj.javaClass.getMethod("getIdentifier", String::class.java, String::class.java, String::class.java)
                    val getLayout = resObj.javaClass.getMethod("getLayout", Int::class.java)
                    val layoutName = System.getenv("SHADOW_DEBUG_LAYOUT")!!
                    val layoutId = getIdentifier.invoke(resObj, layoutName, "layout", "com.phisher98") as Int
                    println("[LAYOUT-DBG] layout '$layoutName' id=0x${layoutId.toString(16)}")
                    val parser = getLayout.invoke(resObj, layoutId)
                    println("[LAYOUT-DBG] parser=$parser")
                    if (parser != null) {
                        val inflater = android.view.LayoutInflater.from(null)
                        val view = inflater.inflate(parser as org.xmlpull.v1.XmlPullParser, null, false)
                        println("[LAYOUT-DBG] inflated root=${view?.javaClass?.simpleName}")
                        fun dump(v: android.view.View, depth: Int) {
                            val idStr = if (v.id != 0) "0x${v.id.toString(16)}" else "-"
                            println("[LAYOUT-DBG] " + "  ".repeat(depth) + "${v.javaClass.simpleName} id=$idStr" +
                                (if (v is android.widget.TextView && v.text.isNotEmpty()) " text=\"${v.text.toString().take(30)}\"" else "") +
                                (if (v is android.widget.CompoundButton) " checked=${v.isChecked}" else ""))
                            if (v is android.view.ViewGroup) v.children.forEach { dump(it, depth + 1) }
                        }
                        view?.let { dump(it, 1) }
                        // Verify the fragment's findView ids resolve into this tree
                        for (lookup in listOf("loginCard", "wyzieCard", "featureCard", "toggleproviders",
                                 "languagechange", "stremioaddons", "stremioaddonstreams", "performance",
                                 "saveIcon", "loginRow", "wyzieRow", "featureRow", "toggleprovidersRow",
                                 "languageRow", "stremioaddonsRow", "stremioaddonstreamsRow", "performanceRow")) {
                            val gid = resObj.javaClass.getMethod("getIdentifier", String::class.java, String::class.java, String::class.java)
                            val id = gid.invoke(resObj, lookup, "id", "com.phisher98") as Int
                            val found = view?.findViewById(id)
                            println("[LAYOUT-DBG] lookup '$lookup' id=0x${id.toString(16)} → ${if (found != null) "FOUND ${found.javaClass.simpleName}" else "NULL"}")
                        }
                    }
                } else {
                    println("[LAYOUT-DBG] plugin resources field not found/attached")
                }
            } catch (t: Throwable) {
                println("[LAYOUT-DBG] failed: ${t::class.simpleName}: ${t.message}")
                t.printStackTrace()
            }
        }

        // Run the plugin's own settings UI code
        try {
            loader.openPluginSettings(internalName, null)
        } catch (t: Throwable) {
            println("✗ openPluginSettings threw: ${t::class.simpleName}: ${t.message}")
        }

        // Let async Handler.post work drain
        Thread.sleep(400)
        ShadowUi.finishSessionDelayed(50)
        Thread.sleep(200)

        val dialogs = ShadowUi.dialogs.value
        if (dialogs.isEmpty()) {
            println("✗ NO dialogs captured")
            failures++
            continue
        }

        println("✓ Dialogs captured: ${dialogs.size}")
        dialogs.forEachIndexed { i, d ->
            val title = when (val p = d.platform) {
                is android.app.Dialog -> p.title?.toString() ?: "(no title)"
                is androidx.appcompat.app.AlertDialog -> p.title?.toString() ?: "(no title)"
                is androidx.fragment.app.Fragment -> "Fragment ${d.fragmentTag ?: p::class.simpleName}"
                else -> p::class.simpleName ?: "?"
            }
            println("  [$i] $title  view=${d.view?.javaClass?.simpleName}")
        }

        val top = dialogs.last()
        val view = top.view
        if (view == null) {
            println("✗ top dialog has no view")
            failures++
            continue
        }

        // Walk the tree
        var totalViews = 0
        var switches = 0
        var texts = 0
        var buttons = 0
        var editFields = 0
        var clickables = 0

        fun walk(v: View, depth: Int) {
            totalViews++
            when (v) {
                is CompoundButton -> switches++
                is android.widget.EditText -> editFields++
                is TextView -> texts++
                is android.widget.Button -> buttons++
            }
            if (v.clickListener != null) clickables++
            if (v is ViewGroup) {
                v.children.forEach { walk(it, depth + 1) }
            }
        }
        walk(view, 0)

        println("✓ View tree: total=$totalViews texts=$texts switches=$switches buttons=$buttons editTexts=$editFields clickables=$clickables")

        // Print top-level structure for visual confirmation
        fun describe(v: View, depth: Int): String {
            val sb = StringBuilder()
            repeat(depth) { sb.append("  ") }
            val label = when (v) {
                is TextView -> "TextView(\"${v.text.toString().take(48)}\")"
                is android.widget.Button -> "Button(\"${v.text}\")"
                is android.widget.EditText -> "EditText(hint=\"${v.hint}\", value=\"${v.text.toString().take(20)}\")"
                is CompoundButton -> "Check(checked=${v.isChecked}, text=\"${v.text}\")"
                is android.widget.ImageView -> "ImageView(${(v.imageDrawable as? android.graphics.drawable.NamedDrawable)?.name})"
                is android.widget.ProgressBar -> "ProgressBar(${v.progress}/${v.max})"
                is androidx.recyclerview.widget.RecyclerView -> "RecyclerView(items=${v.adapter?.getItemCount()})"
                is android.widget.ListView -> "ListView(items=${v.adapter?.getCount()})"
                else -> v.javaClass.simpleName
            }
            sb.append(label)
            if (v is ViewGroup && v.children.isNotEmpty()) {
                sb.append(" {")
                v.children.take(3).forEach { sb.append("\n").append(describe(it, depth + 1)) }
                if (v.children.size > 3) sb.append("\n").append("  …(${v.children.size} children)")
                sb.append("\n")
                repeat(depth) { sb.append("  ") }
                sb.append("}")
            }
            return sb.toString()
        }
        println("── Tree preview ──")
        println(describe(view, 1))

        if (totalViews < 5) {
            println("✗ view tree suspiciously small ($totalViews)")
            failures++
        }

        // ── Interaction replay test ──────────────────────────────────────
        // Click the first clickable with a row-like id (StreamPlay menu) or
        // a provider toggle (CineStream) and verify the plugin reacts.
        val stackBefore = ShadowUi.dialogs.value.size
        var clicked: View? = null
        fun findClickable(v: View): View? {
            if (v.clickListener != null) return v
            if (v is ViewGroup) {
                v.children.forEach { c -> findClickable(c)?.let { return it } }
            }
            return null
        }
        findClickable(view)?.let { clicked = it }

        if (clicked != null) {
            println("── Interaction: clicking ${clicked!!.javaClass.simpleName} ──")
            ShadowUi.dispatch("smoke-click") { clicked!!.performClick() }
            ShadowUi.executor.submit { }.get()  // wait for the click to process
            Thread.sleep(600)
            val stackAfter = ShadowUi.dialogs.value.size
            println("✓ Click dispatched. Dialogs: $stackBefore → $stackAfter")
            if (internalName == "StreamPlay" && stackAfter <= stackBefore) {
                println("✗ StreamPlay row click did not open a sub-screen")
                failures++
            }
            if (internalName == "StreamPlay" && stackAfter > stackBefore) {
                // Show the new top dialog's structure
                val top = ShadowUi.dialogs.value.last()
                top.view?.let { sub ->
                    var count = 0
                    fun walk(v: View) {
                        count++
                        if (v is ViewGroup) v.children.forEach { walk(it) }
                    }
                    walk(sub)
                    println("✓ Sub-screen '${top.fragmentTag}' opened: $count views")
                }
                // Dismiss it (plugin's own code) and go back
                ShadowUi.dispatch("smoke-dismiss") { top.platform.let { (it as? androidx.fragment.app.Fragment)?.dismiss() } }
                ShadowUi.executor.submit { }.get()
                Thread.sleep(300)
            }
            if (internalName == "CineStream") {
                // Find a switch and toggle it through the plugin's listener
                var sw: CompoundButton? = null
                fun findSwitch(v: View) {
                    if (v is CompoundButton && sw == null) sw = v
                    if (v is ViewGroup) v.children.forEach { findSwitch(it) }
                }
                findSwitch(view)
                sw?.let {
                    val before = it.isChecked
                    ShadowUi.dispatch("smoke-toggle") { it.isChecked = !before }
                    ShadowUi.executor.submit { }.get()
                    Thread.sleep(300)
                    val after = it.isChecked
                    println("✓ Switch toggle: $before → $after (listener path executed)")
                }
            }
        } else {
            println("(no clickable found — skipping interaction test)")
        }
    }

    println("\n════════════════════════════════════════════════════")
    println(if (failures == 0) "SMOKE TEST PASSED" else "SMOKE TEST FAILED: $failures failure(s)")
    println("════════════════════════════════════════════════════")
    exitProcess(if (failures == 0) 0 else 1)
}
