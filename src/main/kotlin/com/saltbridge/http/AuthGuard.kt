package com.saltbridge.http

import com.saltbridge.core.Clock
import java.nio.charset.StandardCharsets

/**
 * 主机 / 来源 / 令牌检查。
 *
 * 三项都无状态，便于直接测试；顺序由 Router 固定为 Host → Origin → Token。
 */
object AuthGuard {

    fun checkHost(headers: Map<String, String>, port: Int): Rejection? {
        val host = headers["host"]?.trim()?.lowercase()
            ?: return Rejection(403, "forbidden_host", "Missing Host header")

        val allowed = setOf(
            "127.0.0.1:$port",
            "localhost:$port",
            "[::1]:$port",
        )
        return if (host in allowed) {
            null
        } else {
            Rejection(403, "forbidden_host", "Host not allowed: $host")
        }
    }

    /** 原生客户端不会带 `Origin`；带了就说明可能是浏览器，直接拒绝。 */
    fun checkOrigin(headers: Map<String, String>): Rejection? =
        if (headers.containsKey("origin")) {
            Rejection(403, "forbidden_origin", "Cross-origin requests are not supported")
        } else {
            null
        }

    fun checkToken(headers: Map<String, String>, expected: String): Rejection? {
        val authorization = headers["authorization"]
            ?: return Rejection(401, "unauthorized", "Missing Authorization header")

        if (!authorization.startsWith("Bearer ", ignoreCase = true)) {
            return Rejection(401, "unauthorized", "Only Bearer tokens are accepted")
        }

        val presented = authorization.substring("Bearer ".length).trim()
        return if (constantTimeEquals(presented, expected)) {
            null
        } else {
            Rejection(401, "unauthorized", "Invalid token")
        }
    }

    /** 定长比较：长度差也参与异或，避免提前返回。 */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val left = a.toByteArray(StandardCharsets.UTF_8)
        val right = b.toByteArray(StandardCharsets.UTF_8)
        var diff = left.size xor right.size
        val length = maxOf(left.size, right.size)
        for (index in 0 until length) {
            val x = if (index < left.size) left[index].toInt() else 0
            val y = if (index < right.size) right[index].toInt() else 0
            diff = diff or (x xor y)
        }
        return diff == 0
    }
}

/** 固定窗口限流：每秒最多 `perSecond` 次。 */
class RateLimiter(
    private val perSecond: Int,
    private val clock: Clock,
) {

    private val lock = Any()
    private var windowStartMs: Long = Long.MIN_VALUE
    private var count: Int = 0

    fun tryAcquire(): Boolean = synchronized(lock) {
        val now = clock.nowMs()
        if (windowStartMs == Long.MIN_VALUE || now - windowStartMs >= 1_000) {
            windowStartMs = now
            count = 0
        }
        if (count >= perSecond) {
            false
        } else {
            count += 1
            true
        }
    }
}
