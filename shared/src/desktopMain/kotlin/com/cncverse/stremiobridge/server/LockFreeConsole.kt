package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.state.LogLevel
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.state.platformLog
import java.io.OutputStream
import java.io.PrintStream
import java.util.Locale

/**
 * System.out / System.err of the desktop app. Extensions print freely (NiceHttp, page dumps,
 * stack traces), and a PrintStream locks on every print. On a server running the same JVM code
 * a StackOverflowError inside an extension's println left the stdout lock held; another
 * extension then waited on it while holding the outer lock, and 300 threads — every load() slot
 * among them — hung behind the deadlock until a restart: no meta, no streams.
 *
 * This stream overrides every write and print method without calling the PrintStream ones, so
 * it never takes a lock: nothing an extension prints can block or deadlock the bridge. The text
 * is dropped; with CNC_LOG_VERBOSE=1 each print goes to app.log through the bridge's own
 * non-blocking log queue. The JVM's own fatal messages (out of memory…) are written to the
 * file descriptor directly, not through System.err, so they still reach a console.
 */
class LockFreeConsole private constructor(private val tag: String) : PrintStream(OutputStream.nullOutputStream(), false) {

    private fun emit(text: String?) {
        if (!ServerState.verbose) return
        val line = text?.trimEnd('\n', '\r') ?: "null"
        if (line.isNotEmpty()) platformLog(LogLevel.INFO, "[$tag] $line")
    }

    override fun write(b: Int) {}
    override fun write(buf: ByteArray, off: Int, len: Int) {
        if (ServerState.verbose && len > 0) emit(String(buf, off, len, Charsets.UTF_8))
    }
    override fun write(buf: ByteArray) = write(buf, 0, buf.size)
    override fun writeBytes(buf: ByteArray) = write(buf, 0, buf.size)
    override fun flush() {}
    override fun close() {}
    override fun checkError(): Boolean = false

    override fun print(b: Boolean) = emit(b.toString())
    override fun print(c: Char) = emit(c.toString())
    override fun print(i: Int) = emit(i.toString())
    override fun print(l: Long) = emit(l.toString())
    override fun print(f: Float) = emit(f.toString())
    override fun print(d: Double) = emit(d.toString())
    override fun print(s: CharArray) = emit(String(s))
    override fun print(s: String?) = emit(s)
    override fun print(obj: Any?) = emit(obj.toString())

    override fun println() {}
    override fun println(x: Boolean) = emit(x.toString())
    override fun println(x: Char) = emit(x.toString())
    override fun println(x: Int) = emit(x.toString())
    override fun println(x: Long) = emit(x.toString())
    override fun println(x: Float) = emit(x.toString())
    override fun println(x: Double) = emit(x.toString())
    override fun println(x: CharArray) = emit(String(x))
    override fun println(x: String?) = emit(x)
    override fun println(x: Any?) = emit(x.toString())

    override fun printf(format: String, vararg args: Any?): PrintStream = format(format, *args)
    override fun printf(l: Locale?, format: String, vararg args: Any?): PrintStream = format(l, format, *args)
    override fun format(format: String, vararg args: Any?): PrintStream {
        if (ServerState.verbose) emit(runCatching { String.format(format, *args) }.getOrDefault(format))
        return this
    }
    override fun format(l: Locale?, format: String, vararg args: Any?): PrintStream {
        if (ServerState.verbose) emit(runCatching { String.format(l, format, *args) }.getOrDefault(format))
        return this
    }

    override fun append(csq: CharSequence?): PrintStream { emit(csq.toString()); return this }
    override fun append(csq: CharSequence?, start: Int, end: Int): PrintStream {
        emit((csq ?: "null").subSequence(start, end).toString()); return this
    }
    override fun append(c: Char): PrintStream { emit(c.toString()); return this }

    companion object {
        /** Call after the first log line, so the log writer has already kept the real stdout. */
        fun install() {
            System.setOut(LockFreeConsole("out"))
            System.setErr(LockFreeConsole("err"))
        }
    }
}
