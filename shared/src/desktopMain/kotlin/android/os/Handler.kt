package android.os

import java.util.concurrent.TimeUnit

/**
 * android.os stubs. Handler/Looper are FUNCTIONAL: plugins post UI/async work
 * through them, so posts run on a shared single-thread "main" executor.
 */
class Looper private constructor() {
    val thread: Thread = Thread(MAIN_THREAD_NAME)

    fun quit() {}

    companion object {
        private const val MAIN_THREAD_NAME = "desktop-main"

        private val mainLooperInstance: Looper = Looper()

        @JvmStatic
        fun getMainLooper(): Looper = mainLooperInstance

        @JvmStatic
        fun myLooper(): Looper? = mainLooperInstance

        @JvmStatic
        fun prepare() {}

        @JvmStatic
        fun loop() {}
    }
}

class Handler {
    interface Callback {
        fun handleMessage(msg: Message?): Boolean
    }

    private val looper: Looper

    constructor() { looper = Looper.getMainLooper() }
    constructor(looper: Looper?) { this.looper = looper ?: Looper.getMainLooper() }
    constructor(callback: Callback?) { looper = Looper.getMainLooper() }
    constructor(looper: Looper?, callback: Callback?) { this.looper = looper ?: Looper.getMainLooper() }

    fun post(runnable: Runnable?): Boolean {
        runnable ?: return false
        MainExecutor.execute(runnable)
        return true
    }

    fun postDelayed(runnable: Runnable?, delayMillis: Long): Boolean {
        runnable ?: return false
        MainExecutor.schedule(runnable, delayMillis)
        return true
    }

    fun postAtFrontOfQueue(runnable: Runnable?): Boolean = post(runnable)

    fun removeCallbacks(runnable: Runnable?) { MainExecutor.remove(runnable) }
    fun removeCallbacksAndMessages(token: Any?) { MainExecutor.cancelAll() }

    fun sendEmptyMessage(what: Int): Boolean = true
    fun sendMessage(msg: Message?): Boolean = true
    fun handleMessage(msg: Message?) {}

    companion object {
        /**
         * Cancels every pending task posted by code from [loaders] and refuses
         * any they post later. Called when plugins are unloaded: self-reposting
         * runnables (e.g. a plugin's periodic monitor) would otherwise keep the
         * old plugin classloader — and everything it references — alive forever.
         */
        @JvmStatic
        fun cancelTasksFrom(loaders: Collection<ClassLoader>) = MainExecutor.cancelFrom(loaders)

        internal object MainExecutor {
            private val executor = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
                Thread(r, "desktop-main").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }

            /** Pending futures per posted runnable, so removeCallbacks can cancel them. */
            private val tasks = java.util.concurrent.ConcurrentHashMap<Runnable, java.util.concurrent.Future<*>>()

            /** Classloaders of unloaded plugins; their posts are dropped (weak so they can be GC'd). */
            private val deadLoaders: MutableSet<ClassLoader> =
                java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap()))

            private fun isDead(r: Runnable): Boolean {
                val cl = r.javaClass.classLoader ?: return false
                return deadLoaders.contains(cl)
            }

            fun execute(r: Runnable) {
                if (isDead(r)) return
                tasks[r] = executor.submit { tasks.remove(r); r.run() }
            }

            fun schedule(r: Runnable, delayMs: Long) {
                if (isDead(r)) return
                tasks[r] = executor.schedule({ tasks.remove(r); r.run() }, delayMs, TimeUnit.MILLISECONDS)
            }

            fun remove(r: Runnable?) {
                r ?: return
                tasks.remove(r)?.cancel(false)
            }

            fun cancelAll() {
                tasks.values.forEach { it.cancel(false) }
                tasks.clear()
            }

            fun cancelFrom(loaders: Collection<ClassLoader>) {
                if (loaders.isEmpty()) return
                deadLoaders.addAll(loaders)
                val iter = tasks.entries.iterator()
                while (iter.hasNext()) {
                    val (r, future) = iter.next()
                    if (r.javaClass.classLoader in loaders) {
                        future.cancel(false)
                        iter.remove()
                    }
                }
                executor.purge()
            }
        }
    }
}

class Message {
    var what: Int = 0
    var arg1: Int = 0
    var arg2: Int = 0
    var obj: Any? = null

    companion object {
        @JvmStatic
        fun obtain(): Message = Message()
    }
}
