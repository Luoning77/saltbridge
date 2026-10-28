package com.saltbridge.http

import com.saltbridge.core.LyricLine
import com.saltbridge.core.TrackInfo
import com.saltbridge.core.lines
import com.saltbridge.core.track
import com.saltbridge.plugin.BridgeCore
import com.saltbridge.plugin.HostPort
import com.saltbridge.plugin.PollOutcome
import com.xuncorp.spw.workshop.api.Channel
import com.xuncorp.spw.workshop.api.PluginContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实 socket 集成测试：验证 NanoHTTPD 适配层、有界线程池与 Host 校验确实工作。
 *
 * 用随机端口（0），避免与开发机上的 8737 冲突。
 */
class HttpServerIntegrationTest {

    private class FakePort(
        @Volatile var track: TrackInfo? = null,
        @Volatile var lyrics: List<LyricLine> = emptyList(),
    ) : HostPort {
        override fun poll(): PollOutcome = PollOutcome.Available(track)
        override fun lyricsLines(): List<LyricLine> = lyrics
        override fun cover(trackId: String): ByteArray? = null
        override fun isLibraryReadGranted(): Boolean = true

        override fun play() = Unit
        override fun pause() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seekTo(positionMs: Long) = Unit
    }

    private fun context() = PluginContext(
        pluginId = "com.saltbridge",
        pluginVersion = "0.1.0",
        pluginPath = "/tmp/saltbridge-it",
        spwVersion = "1.19.2",
        spwChannel = Channel.Steam,
    )

    private fun newCore(): BridgeCore = BridgeCore(
        context = context(),
        epoch = 1,
        dataDir = null,
        hostPort = FakePort(track = track(), lyrics = lines(3)),
        pollIntervalMs = 20,
        httpConfig = HttpConfig(port = 0, requestsPerSecond = 1_000),
    )

    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun get(url: String, token: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url)).GET()
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `serves health without token`() {
        val core = newCore()
        core.start()
        try {
            assertTrue(awaitUntil { core.httpStarted() })
            val response = get("http://127.0.0.1:${core.httpPort()}/api/v1/health")

            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"ok\":true"))
            assertTrue(response.body().contains("\"authRequired\":true"))
        } finally {
            core.stop()
        }
    }

    @Test
    fun `status requires token then returns snapshot`() {
        val core = newCore()
        core.start()
        try {
            assertTrue(awaitUntil { core.httpStarted() && core.token().startsWith("sb_") })
            val base = "http://127.0.0.1:${core.httpPort()}/api/v1/status"

            val unauthorized = get(base)
            assertEquals(401, unauthorized.statusCode())
            assertEquals("Bearer", unauthorized.headers().firstValue("WWW-Authenticate").orElse(""))

            val authorized = get(base, core.token())
            assertEquals(200, authorized.statusCode())
            assertTrue(authorized.body().contains("\"protocolVersion\":\"1.0\""))
            assertTrue(authorized.body().contains("\"trackStatus\":\"available\""))

            // 等曲目真的建立
            assertTrue(awaitUntil { core.snapshot()?.track?.id == "t-1" })
        } finally {
            core.stop()
        }
    }

    @Test
    fun `unknown endpoint returns 404 and server survives`() {
        val core = newCore()
        core.start()
        try {
            assertTrue(awaitUntil { core.httpStarted() })
            val base = "http://127.0.0.1:${core.httpPort()}/api/v1"

            assertEquals(404, get("$base/nope").statusCode())
            // 连续请求不应把服务器打挂
            assertEquals(200, get("$base/health").statusCode())
        } finally {
            core.stop()
        }
    }

    @Test
    fun `stopping the core releases the port`() {
        val core = newCore()
        core.start()
        assertTrue(awaitUntil { core.httpStarted() })
        val port = core.httpPort()
        core.stop()

        assertTrue(port > 0)
        assertEquals(0, core.httpPort())

        // 端口应可被重新绑定（新建实例用同一端口）
        val second = BridgeCore(
            context = context(),
            epoch = 2,
            dataDir = null,
            hostPort = FakePort(),
            pollIntervalMs = 1_000,
            httpConfig = HttpConfig(port = port, requestsPerSecond = 1_000),
        )
        second.start()
        try {
            assertTrue(awaitUntil { second.httpStarted() }, "同一端口应能重新绑定")
            assertEquals(port, second.httpPort())
        } finally {
            second.stop()
        }
    }
}
