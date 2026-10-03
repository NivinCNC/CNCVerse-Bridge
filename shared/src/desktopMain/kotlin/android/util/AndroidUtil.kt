package android.util

import com.cncverse.stremiobridge.state.ServerState
import java.util.Base64 as JBase64

/**
 * android.util stubs.
 * Base64 and Log are FUNCTIONAL — plugins call them from streaming code paths.
 */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    private fun decodeBytes(input: ByteArray, flags: Int): ByteArray {
        val urlSafe = flags and URL_SAFE != 0
        val normalized = if (urlSafe) {
            input.map { byte ->
                when (byte) {
                    '-'.code.toByte() -> '+'.code.toByte()
                    '_'.code.toByte() -> '/'.code.toByte()
                    else -> byte
                }
            }.toByteArray()
        } else input

        val cleaned = normalized.filter { it != '\n'.code.toByte() && it != '\r'.code.toByte() }.toByteArray()
        val padded = cleaned + ByteArray((4 - cleaned.size % 4) % 4) { '='.code.toByte() }
        return JBase64.getDecoder().decode(padded)
    }

    @JvmStatic
    fun decode(str: String?, flags: Int): ByteArray = decodeBytes((str ?: "").toByteArray(Charsets.UTF_8), flags)

    @JvmStatic
    fun decode(input: ByteArray?, flags: Int): ByteArray = decodeBytes(input ?: ByteArray(0), flags)

    @JvmStatic
    fun encode(input: ByteArray?, flags: Int): ByteArray = encodeToString(input, flags).toByteArray(Charsets.UTF_8)

    @JvmStatic
    fun encodeToString(input: ByteArray?, flags: Int): String {
        val bytes = input ?: ByteArray(0)
        val urlSafe = flags and URL_SAFE != 0
        val noPad = flags and NO_PADDING != 0
        val encoder = when {
            urlSafe && noPad -> JBase64.getUrlEncoder().withoutPadding()
            urlSafe -> JBase64.getUrlEncoder()
            noPad -> JBase64.getEncoder().withoutPadding()
            else -> JBase64.getEncoder()
        }
        var result = encoder.encodeToString(bytes)
        if (flags and NO_WRAP == 0) {
            // Android wraps at 76 chars by default; chunk it the same way
            result = result.chunked(76).joinToString("\n")
        }
        return result
    }
}

object Log {
    @JvmStatic fun v(tag: String?, msg: String?): Int = 0
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun d(tag: String?, msg: String?): Int = 0
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = 0

    // Plugins log from loops (one line per channel, per retry…): the same tag+message is
    // written at most once a minute. Info is per-request chatter — debug only.
    private val lastLine = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun once(line: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = lastLine[line]
        if (prev != null && now - prev < 60_000L) return false
        lastLine[line] = now
        if (lastLine.size > 5_000) lastLine.entries.removeIf { now - it.value > 60_000L }
        return true
    }

    @JvmStatic
    fun i(tag: String?, msg: String?): Int {
        ServerState.debug("[$tag] $msg")
        return 0
    }

    @JvmStatic
    fun w(tag: String?, msg: String?): Int {
        val line = "[$tag] $msg"
        if (once(line)) ServerState.warn(line)
        return 0
    }

    @JvmStatic
    fun w(tag: String?, msg: String?, tr: Throwable?): Int {
        val line = "[$tag] $msg: ${tr?.message}"
        if (once(line)) ServerState.warn(line)
        return 0
    }

    @JvmStatic
    fun e(tag: String?, msg: String?): Int {
        val line = "[$tag] $msg"
        if (once(line)) ServerState.error(line)
        return 0
    }

    @JvmStatic
    fun e(tag: String?, msg: String?, tr: Throwable?): Int {
        val line = "[$tag] $msg: ${tr?.message}"
        if (once(line)) ServerState.error(line)
        return 0
    }

    @JvmStatic
    fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString() ?: ""
}

class DisplayMetrics {
    @JvmField var widthPixels: Int = 1920
    @JvmField var heightPixels: Int = 1080
    @JvmField var density: Float = 2.0f
    @JvmField var densityDpi: Int = 320
    @JvmField var scaledDensity: Float = 2.0f
    @JvmField var xdpi: Float = 320f
    @JvmField var ydpi: Float = 320f
}
