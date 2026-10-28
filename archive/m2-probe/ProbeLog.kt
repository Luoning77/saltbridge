package com.saltbridge.probe

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 探针日志：单文件、逐行 flush、写失败不影响主流程。
 *
 * 明确不记录：令牌、文件路径、完整歌词文本。
 */
class ProbeLog(private val file: Path) : AutoCloseable {

    private val lock = Any()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val writer: BufferedWriter? = runCatching {
        Files.createDirectories(file.parent)
        Files.newBufferedWriter(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }.getOrNull()

    fun line(sessionMs: Long, thread: String, kind: String, detail: String) {
        val text = buildString {
            append(LocalTime.now().format(timeFormat))
            append(" | +").append(sessionMs).append("ms | ")
            append(thread).append(" | ").append(kind)
            if (detail.isNotEmpty()) append(" | ").append(detail)
        }
        synchronized(lock) {
            runCatching {
                writer?.apply {
                    write(text)
                    newLine()
                    flush()
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching {
                writer?.flush()
                writer?.close()
            }
        }
    }
}
