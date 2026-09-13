package com.cncverse.stremiobridge.shadowui

import android.view.View
import com.cncverse.stremiobridge.state.ServerState
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The heart of full extension-settings support on desktop.
 *
 * Plugins build their settings UI with real Android APIs (AlertDialog,
 * LinearLayout, Switch, DialogFragment…). On desktop those calls execute
 * against the recording stubs in `shared/desktopMain`, and every dialog they
 * `show()` is captured here as a [ShadowDialog]. The Compose renderer in
 * desktopApp observes [dialogs] and renders the stack natively; user
 * interactions are replayed through [dispatch] into the plugin's own Kotlin
 * listeners, which mutate the same stub objects — this bumps [version] and
 * the renderer re-renders. The result behaves like the Android UI because it
 * literally IS the plugin's own UI code running.
 */
object ShadowUi {

    /** One entry per visible dialog; last = topmost. */
    val dialogs = MutableStateFlow<List<ShadowDialog>>(emptyList())

    /** Bumped on every stub mutation so the renderer refreshes. */
    val version = MutableStateFlow(0)

    /** Plugin whose settings are open right now (null = no session). */
    val sessionPluginId = MutableStateFlow<String?>(null)

    /** True once the current session pushed at least one dialog. */
    val sessionProducedDialogs = MutableStateFlow(false)

    /** Session lifecycle for the gear-button fallback logic. */
    val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)

    /** Last toast text + timestamp (renderer shows a transient overlay). */
    val toast = MutableStateFlow<ToastEvent?>(null)

    /**
     * Single-threaded executor that replays plugin listeners off the UI and
     * off the loader threads. Serializing interactions keeps plugin state
     * consistent (the Android main thread plays the same role).
     */
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shadow-ui").apply { isDaemon = true }
    }

    private val dialogIds = AtomicLong(0)

    enum class SessionState { Idle, Running, Finished }

    data class ToastEvent(val text: String, val at: Long)

    // ── Session management ────────────────────────────────────────────────

    /** Called by the plugin loader before invoking a plugin's openSettings. */
    fun beginSession(pluginId: String) {
        sessionPluginId.value = pluginId
        sessionProducedDialogs.value = false
        sessionState.value = SessionState.Running
        // Clear any stale stack (e.g. a dialog the plugin leaked earlier).
        synchronized(dialogs) { dialogs.value = emptyList() }
    }

    /**
     * Marks the session complete. Delayed through [executor] so dialogs that
     * plugins show asynchronously (Handler.post) still make it before the
     * gear button falls back to the schema-registry dialog.
     */
    fun finishSessionDelayed(delayMillis: Long = 900) {
        executor.execute {
            try {
                Thread.sleep(delayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (sessionState.value == SessionState.Running) {
                sessionState.value = SessionState.Finished
            }
        }
    }

    fun endSession() {
        sessionState.value = SessionState.Idle
        sessionPluginId.value = null
        sessionProducedDialogs.value = false
        synchronized(dialogs) { dialogs.value = emptyList() }
        bump()
    }

    // ── Dialog stack ──────────────────────────────────────────────────────

    /** Registers a shown dialog. Re-showing an already-shown dialog is a no-op. */
    fun push(dialog: ShadowDialog) {
        synchronized(dialogs) {
            if (dialogs.value.any { it.platform === dialog.platform }) return
            dialogs.value = dialogs.value + dialog
        }
        sessionProducedDialogs.value = true
        if (sessionState.value == SessionState.Idle) {
            // A dialog shown outside a session (e.g. from a listener long after
            // the session finished) still belongs to the current plugin.
            sessionState.value = SessionState.Finished
        }
        bump()
    }

    /** Removes a dismissed/cancelled dialog. */
    fun pop(platform: Any?) {
        if (platform == null) return
        synchronized(dialogs) {
            dialogs.value = dialogs.value.filterNot { it.platform === platform }
        }
        bump()
    }

    fun isShowing(platform: Any?): Boolean =
        dialogs.value.any { it.platform === platform }

    /** True when the stack is empty and none is coming back. */
    fun stackEmptyAndFinished(): Boolean =
        sessionState.value == SessionState.Finished && dialogs.value.isEmpty()

    // ── Mutations & interactions ──────────────────────────────────────────

    /** Called by stub mutators; cheap and thread-safe. */
    fun bump() {
        version.value = version.value + 1
    }

    /**
     * Replays a plugin interaction (click/checked-change/text-change) on the
     * shadow executor. Exceptions are logged, never propagated to Compose.
     */
    fun dispatch(tag: String, block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (t: Throwable) {
                ServerState.warn("[$tag] ${t::class.simpleName}: ${t.message}")
            } finally {
                bump()
            }
        }
    }

    fun nextDialogId(): Long = dialogIds.incrementAndGet()

    fun toast(text: String) {
        toast.value = ToastEvent(text, System.currentTimeMillis())
    }
}

/**
 * A shown dialog. Holds a reference to the LIVE stub object (android
 * AlertDialog / androidx AlertDialog / android Dialog backing a fragment) —
 * the renderer re-reads its properties on every frame so plugin-side
 * mutations (title swaps, view edits) show up automatically.
 */
class ShadowDialog(
    /** The stub instance to read live state from. */
    @JvmField val platform: Any,
    /** Content view (fragment view or dialog custom view). */
    @JvmField val view: View?,
    /** True for fragment-backed dialogs (bottom sheets render as sheets). */
    @JvmField val fromFragment: Boolean = false,
    @JvmField val fragmentTag: String? = null,
    @JvmField val id: Long = ShadowUi.nextDialogId(),
) {
    fun sameAs(other: Any?): Boolean = other is ShadowDialog && other.platform === platform
}
