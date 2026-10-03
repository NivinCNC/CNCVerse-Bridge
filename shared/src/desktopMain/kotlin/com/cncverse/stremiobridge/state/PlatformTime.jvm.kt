package com.cncverse.stremiobridge.state

import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

/**
 * Desktop log sink: writes every ServerState entry (stdout only from a terminal) into
 * ~/.cncverse_bridge/app.log so issues are diagnosable when the packaged exe
 * runs without a console.
 *
 * Writes happen on ONE background thread. Callers (request handlers, plugin
 * callbacks) only enqueue and never block: stdout can stall (journald rate
 * limiting, a full pipe) and concurrent println on System.out has deadlocked
 * the whole server before. When the queue is full, messages are dropped.
 */
private object AsyncLogSink {
    private const val MAX_LOG_BYTES = 50L * 1024 * 1024
    private val queue = ArrayBlockingQueue<String>(10_000)
    private val dropped = AtomicLong(0)
    private val tsFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val logFile = File(System.getProperty("user.home"), ".cncverse_bridge/app.log")

    // Captured once at startup so later System.setOut calls can't redirect us.
    // Under systemd (no console) every line was stored three times — app.log, journald,
    // rsyslog — so stdout is only mirrored from a terminal or with CNC_LOG_STDOUT=1.
    private val stdout: PrintStream? = System.out.takeIf {
        System.console() != null || System.getenv("CNC_LOG_STDOUT") == "1"
    }
    private var fileOut: PrintStream? = null

    init {
        Thread(::drain, "log-writer").apply { isDaemon = true }.start()
    }

    fun enqueue(line: String) {
        if (!queue.offer(line)) dropped.incrementAndGet()
    }

    private fun openFile(): PrintStream? = runCatching {
        logFile.parentFile?.mkdirs()
        PrintStream(FileOutputStream(logFile, true), false, Charsets.UTF_8)
    }.getOrNull()

    private fun drain() {
        val batch = ArrayList<String>(256)
        while (true) {
            try {
                batch.add(queue.take())
                queue.drainTo(batch, 255)
                val lost = dropped.getAndSet(0)
                if (lost > 0) batch.add("[WARN] log queue full — dropped $lost message(s)")

                val ts = LocalDateTime.now().format(tsFormat)
                if (fileOut == null || logFile.length() > MAX_LOG_BYTES) {
                    fileOut?.close()
                    if (logFile.length() > MAX_LOG_BYTES) runCatching { logFile.writeText("") }
                    fileOut = openFile()
                }
                for (line in batch) {
                    stdout?.println(line)
                    fileOut?.println("$ts $line")
                }
                stdout?.flush()
                fileOut?.flush()
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                // Never let the writer die; reopen the file on the next batch
                runCatching { fileOut?.close() }
                fileOut = null
            } finally {
                batch.clear()
            }
        }
    }
}

actual fun platformLog(level: LogLevel, message: String) {
    AsyncLogSink.enqueue("[${level.name}] $message")
}
