package android.os

/** Build constants plugins branch on. */
object Build {
    @JvmField val ID: String = "desktop"
    @JvmField val MODEL: String = "CNCVerse Desktop"
    @JvmField val MANUFACTURER: String = "CNCVerse"
    @JvmField val BRAND: String = "cncverse"
    @JvmField val DEVICE: String = "desktop"
    @JvmField val PRODUCT: String = "desktop"
    @JvmField val HARDWARE: String = "desktop"
    @JvmField val DISPLAY: String = "desktop"
    @JvmField val FINGERPRINT: String = "cncverse/desktop/desktop:1/desktop"
    @JvmField val HOST: String = "desktop"
    @JvmField val USER: String = "desktop"
    @JvmField val TYPE: String = "user"
    @JvmField val TAGS: String = ""
    @JvmField val TIME: Long = 0L
    @JvmField val BOARD: String = "desktop"

    object VERSION {
        @JvmField val SDK_INT: Int = 34
        @JvmField val BASE_OS: String = ""
        @JvmField val CODENAME: String = "REL"
        @JvmField val INCREMENTAL: String = "0"
        @JvmField val RELEASE: String = "14"
        @JvmField val SECURITY_PATCH: String = "2024-01-01"
        @JvmField val PREVIEW_SDK_INT: Int = 0
        const val BASE: String = "1.0"
    }

    object VERSION_CODES {
        const val BASE = 1
        const val JELLY_BEAN = 16
        const val KITKAT = 19
        const val LOLLIPOP = 21
        const val M = 23
        const val N = 24
        const val O = 26
        const val P = 28
        const val Q = 29
        const val R = 30
        const val S = 31
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
    }
}

object SystemClock {
    @JvmStatic
    fun uptimeMillis(): Long = System.currentTimeMillis()

    @JvmStatic
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L

    @JvmStatic
    fun currentThreadTimeMillis(): Long = System.currentTimeMillis()

    @JvmStatic
    fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
