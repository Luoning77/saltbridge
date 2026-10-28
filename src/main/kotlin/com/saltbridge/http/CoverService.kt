package com.saltbridge.http

import com.saltbridge.core.Clock
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

sealed interface CoverResult {
    data class Ready(val bytes: ByteArray, val contentType: String) : CoverResult
    data object Pending : CoverResult
    data object Unavailable : CoverResult
}

/** 按魔数嗅探，不信任扩展名。 */
object CoverSniffer {

    fun contentType(bytes: ByteArray): String = when {
        isJpeg(bytes) -> "image/jpeg"
        isPng(bytes) -> "image/png"
        isGif(bytes) -> "image/gif"
        isWebp(bytes) -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun isJpeg(b: ByteArray): Boolean =
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

    private fun isPng(b: ByteArray): Boolean =
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() &&
            b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() &&
            b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()

    private fun isGif(b: ByteArray): Boolean =
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()

    private fun isWebp(b: ByteArray): Boolean =
        b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
}

/**
 * 封面缓存 + 单飞 + 失败 TTL。
 *
 * HTTP 线程只做一次缓存查找与一次任务提交，**绝不等宿主**（协议 5.5）。
 * 未命中就返回 `Pending`，客户端按固定节奏重试。
 */
class CoverService(
    private val config: HttpConfig,
    private val clock: Clock,
    private val executor: Executor,
    private val loader: (String) -> ByteArray?,
) {

    private data class Key(val generation: Long, val trackId: String)

    private val lock = Any()

    private val cache = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)
    private val inFlight = HashSet<Key>()
    private val failedAt = LinkedHashMap<Key, Long>()
    private var cacheBytes = 0L
    private var revision = 0L

    fun get(generation: Long, trackId: String): CoverResult {
        val key = Key(generation, trackId)
        val ticket: Long
        synchronized(lock) {
            val now = clock.nowMs()
            failedAt.entries.removeIf { now - it.value >= config.coverFailureTtlMs }
            cache[key]?.let { return CoverResult.Ready(it, CoverSniffer.contentType(it)) }
            if (failedAt.containsKey(key)) return CoverResult.Unavailable
            if (key in inFlight || inFlight.size >= config.coverPendingEntries) return CoverResult.Pending
            inFlight.add(key)
            ticket = revision
        }

        try {
            executor.execute {
                // clear() 后尚未执行的工作不再调用宿主。
                if (synchronized(lock) { ticket != revision }) return@execute
                val bytes = runCatching { loader(trackId) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() && it.size <= config.coverMaxBytes && it.size <= config.coverCacheMaxBytes }
                synchronized(lock) {
                    // 清理和交付在同一把锁内校验，避免旧任务重新填回缓存。
                    if (ticket != revision) return@synchronized
                    inFlight.remove(key)
                    if (bytes == null) {
                        failedAt[key] = clock.nowMs()
                        while (failedAt.size > config.coverFailureEntries) {
                            failedAt.remove(failedAt.keys.first())
                        }
                    } else {
                        cache.put(key, bytes)?.let { cacheBytes -= it.size }
                        cacheBytes += bytes.size
                        while (cache.size > config.coverCacheEntries || cacheBytes > config.coverCacheMaxBytes) {
                            val eldest = cache.entries.iterator()
                            cacheBytes -= eldest.next().value.size
                            eldest.remove()
                        }
                        failedAt.remove(key)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) {
                if (ticket == revision) inFlight.remove(key)
            }
        }
        return CoverResult.Pending
    }

    /** 换实例或停用时丢弃；迟到任务不能恢复缓存。 */
    fun clear() {
        synchronized(lock) {
            revision++
            cache.clear()
            cacheBytes = 0L
            inFlight.clear()
            failedAt.clear()
        }
    }
}
