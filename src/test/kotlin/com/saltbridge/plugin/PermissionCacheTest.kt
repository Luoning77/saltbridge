package com.saltbridge.plugin

import com.saltbridge.core.LyricLine
import com.saltbridge.core.TrackInfo
import com.saltbridge.http.HttpConfig
import com.xuncorp.spw.workshop.api.Channel
import com.xuncorp.spw.workshop.api.PluginContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 权限缓存专项测试。
 *
 * 修复前 `permissionCheckedAtMs = Long.MIN_VALUE`，`now - Long.MIN_VALUE` 溢出
 * 后 `< PERMISSION_CACHE_MS` 成立，首次查询直接命中缓存分支返回默认 false。
 * 修复后用 `Long?` 区分"尚未查询"与"已有缓存"。
 *
 * 覆盖路径：BridgeCore → ApiDeps → /capabilities 与 /cover 的权限字段。
 */
class PermissionCacheTest {

    private class FakePort(
        @Volatile var libraryRead: Boolean = true,
        @Volatile var coverBytes: ByteArray? = null,
    ) : HostPort {
        var permissionQueryCount = 0
            private set

        override fun poll(): PollOutcome = PollOutcome.Available(null)
        override fun lyricsLines(): List<LyricLine> = emptyList()
        override fun cover(trackId: String): ByteArray? = coverBytes

        override fun isLibraryReadGranted(): Boolean {
            permissionQueryCount++
            return libraryRead
        }

        override fun play() {}
        override fun pause() {}
        override fun next() {}
        override fun previous() {}
        override fun seekTo(positionMs: Long) {}
    }

    private fun context() = PluginContext(
        pluginId = "com.saltbridge",
        pluginVersion = "0.1.0",
        pluginPath = "/tmp/saltbridge-test",
        spwVersion = "1.19.2",
        spwChannel = Channel.Steam,
    )

    private fun core(port: FakePort) = BridgeCore(
        context = context(),
        epoch = 1,
        dataDir = null,
        hostPort = port,
        pollIntervalMs = 10_000,
        httpConfig = HttpConfig(port = 0),
    )

    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun httpGet(port: Int, path: String, token: String? = null): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return code to body
    }

    @Test
    fun `first query with permission granted returns true`() {
        val port = FakePort(libraryRead = true)
        val core = core(port)
        core.start()
        try {
            assertTrue(awaitUntil { core.httpStarted() })
            val token = core.token()
            val (code, body) = httpGet(core.httpPort(), "/api/v1/capabilities", token)

            assertEquals(200, code)
            assertTrue(body.contains("\"libraryRead\":true"), "首次已授权应返回 true，实际：$body")
            assertTrue(port.permissionQueryCount >= 1, "必须至少查询一次宿主")
        } finally {
            core.stop()
        }
    }

    @Test
    fun `first query with permission denied returns false`() {
        val port = FakePort(libraryRead = false)
        val core = core(port)
        core.start()
        try {
            assertTrue(awaitUntil { core.httpStarted() })
            val token = core.token()
            val (code, body) = httpGet(core.httpPort(), "/api/v1/capabilities", token)

            assertEquals(200, code)
            assertTrue(body.contains("\"libraryRead\":false"), "首次未授权应返回 false，实际：$body")
            assertTrue(port.permissionQueryCount >= 1, "必须至少查询一次宿主")
        } finally {
            core.stop()
        }
    }

    @Test
    fun `cache hit does not re-query host within ttl`() {
        val port = FakePort(libraryRead = true)
        val core = core(port)
        core.start()
        try {
            assertTrue(awaitUntil { core.httpStarted() })
            val token = core.token()

            // 第一次查询
            httpGet(core.httpPort(), "/api/v1/capabilities", token)
            val countAfterFirst = port.permissionQueryCount

            // 2 秒内再查 5 次，不应触发新的宿主查询
            repeat(5) { httpGet(core.httpPort(), "/api/v1/capabilities", token) }

            assertEquals(countAfterFirst, port.permissionQueryCount, "缓存有效期内不应重复查询宿主")
        } finally {
            core.stop()
        }
    }

    @Test
    fun `cache expiry picks up permission change`() {
        val port = FakePort(libraryRead = true)
        val core = core(port)
        core.start()
        try {
            assertTrue(awaitUntil { core.httpStarted() })
            val token = core.token()

            // 首次查询：true
            val (_, body1) = httpGet(core.httpPort(), "/api/v1/capabilities", token)
            assertTrue(body1.contains("\"libraryRead\":true"), "初始应为 true，实际：$body1")

            // 修改权限
            port.libraryRead = false

            // 缓存到期后（等 2.5 秒）再查
            Thread.sleep(2_500)
            val (_, body2) = httpGet(core.httpPort(), "/api/v1/capabilities", token)
            assertTrue(body2.contains("\"libraryRead\":false"), "缓存到期后应反映新值 false，实际：$body2")
        } finally {
            core.stop()
        }
    }

    @Test
    fun `cover with denied permission returns 403`() {
        val port = FakePort(libraryRead = false, coverBytes = byteArrayOf(1, 2, 3))
        val core = core(port)
        core.start()
        try {
            assertTrue(awaitUntil { core.httpStarted() })
            val token = core.token()
            val (code, body) = httpGet(core.httpPort(), "/api/v1/cover", token)

            assertEquals(403, code, "无权限时 /cover 应 403")
            assertTrue(body.contains("permission_denied"), "错误码应为 permission_denied，实际：$body")
        } finally {
            core.stop()
        }
    }

    @Test
    fun `overflow fix - initial query is not swallowed by Long MIN_VALUE`() {
        // 这个测试直接验证修复：在 clock.nowMs() 接近 0 时也不会溢出
        // 如果 permissionCheckedAtMs 仍是 Long.MIN_VALUE，now - MIN_VALUE 会溢出为负数
        val port = FakePort(libraryRead = true)
        val core = BridgeCore(
            context = context(),
            epoch = 1,
            dataDir = null,
            hostPort = port,
            pollIntervalMs = 10_000,
            httpConfig = HttpConfig(port = 0),
            clock = object : com.saltbridge.core.Clock {
                override fun nowMs(): Long = 0L // 模拟启动时刻
            },
        )
        core.start()
        try {
            assertTrue(awaitUntil { core.httpStarted() })
            val token = core.token()
            val (code, body) = httpGet(core.httpPort(), "/api/v1/capabilities", token)

            assertEquals(200, code)
            assertTrue(body.contains("\"libraryRead\":true"), "now=0 时首次查询不应被溢出吞掉，实际：$body")
            assertTrue(port.permissionQueryCount >= 1, "溢出修复后必须真正查询宿主")
        } finally {
            core.stop()
        }
    }
}
