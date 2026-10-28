package com.saltbridge.http

import com.saltbridge.core.Clock
import java.util.concurrent.Executor

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

    private val cache = object : LinkedHashMap<Key, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ByteArray>?): Boolean =
            size > config.coverCacheEntries
    }

    private val inFlight = HashSet<Key>()
    private val failedAt = HashMap<Key, Long>()

    fun get(generation: Long, trackId: String): CoverResult {
        val key = Key(generation, trackId)

        synchronized(lock) {
            cache[key]?.let { return CoverResult.Ready(it, CoverSniffer.contentType(it)) }

            val failed = failedAt[key]
            if (failed != null) {
                if (clock.nowMs() - failed < config.coverFailureTtlMs) {
                    return CoverResult.Unavailable
                }
                failedAt.remove(key)
            }

            if (!inFlight.add(key)) return CoverResult.Pending
        }

        executor.execute {
            val bytes = runCatching { loader(trackId) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size <= config.coverMaxBytes }

            synchronized(lock) {
                inFlight.remove(key)
                if (bytes == null) {
                    failedAt[key] = clock.nowMs()
                } else {
                    cache[key] = bytes
                    failedAt.remove(key)
                }
            }
        }

        return CoverResult.Pending
    }

    /** 换实例时整体丢弃。 */
    fun clear() {
        synchronized(lock) {
            cache.clear()
            inFlight.clear()
            failedAt.clear()
        }
    }
}
