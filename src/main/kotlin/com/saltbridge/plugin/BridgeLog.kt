package com.saltbridge.plugin

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 适配层日志：追加模式、逐行 flush、写失败不影响插件。
 *
 * 明确不记录：令牌、真实路径、完整歌词文本。
 */
class BridgeLog(file: Path?) : AutoCloseable {

    private val lock = Any()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val writer: BufferedWriter? = file?.let { path ->
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.newBufferedWriter(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }.getOrNull()
    }

    fun line(kind: String, detail: String = "") {
        val target = writer ?: return
        val text = buildString {
            append(LocalTime.now().format(timeFormat))
            append(" | ").append(Thread.currentThread().name)
            append(" | ").append(kind)
            if (detail.isNotEmpty()) append(" | ").append(detail)
        }
        synchronized(lock) {
            runCatching {
                target.write(text)
                target.newLine()
                target.flush()
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
