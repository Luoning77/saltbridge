package com.saltbridge.plugin

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 有界异步日志：调用线程不做磁盘 IO，每秒批量刷新，最多保留当前文件和一份备份。 */
class BridgeLog(private val file: Path?) : AutoCloseable {
    private val queue = ArrayBlockingQueue<String>(256)
    private val running = AtomicBoolean(true)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val worker = file?.let {
        Thread({ writeLoop(it) }, "saltbridge-log").apply { isDaemon = true; start() }
    }

    fun line(kind: String, detail: String = "") {
        if (file == null || !running.get()) return
        // 满时丢弃诊断日志，不拖慢播放状态与宿主回调。
        queue.offer(buildString {
            append(LocalTime.now().format(timeFormat))
            append(" | ").append(Thread.currentThread().name)
            append(" | ").append(kind)
            if (detail.isNotEmpty()) append(" | ").append(detail)
        })
    }

    private fun writeLoop(path: Path) {
        var writer: BufferedWriter? = null
        var bytes = 0L
        var flushedAt = System.nanoTime()
        fun open(): BufferedWriter {
            path.parent?.let { Files.createDirectories(it) }
            bytes = if (Files.exists(path)) Files.size(path) else 0L
            return Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        try {
            while (running.get() || queue.isNotEmpty()) {
                val text = try { queue.poll(1, TimeUnit.SECONDS) } catch (_: InterruptedException) { null }
                if (text != null) {
                    if (writer == null) writer = open()
                    val size = text.toByteArray(Charsets.UTF_8).size + 2L
                    if (bytes + size > MAX_BYTES) {
                        writer.close()
                        Files.move(path, path.resolveSibling(path.fileName.toString() + ".1"), StandardCopyOption.REPLACE_EXISTING)
                        writer = open()
                    }
                    writer.write(text)
                    writer.newLine()
                    bytes += size
                }
                if (System.nanoTime() - flushedAt >= 1_000_000_000L) {
                    writer?.flush()
                    flushedAt = System.nanoTime()
                }
            }
        } catch (_: Exception) {
            // 日志失败不能影响插件功能。
        } finally {
            running.set(false)
            queue.clear()
            runCatching { writer?.close() }
        }
    }

    override fun close() {
        running.set(false)
        worker?.interrupt() // 后台排空队列并关闭文件，不让 UI 等磁盘。
    }

    private companion object { const val MAX_BYTES = 2L * 1024 * 1024 }
}
