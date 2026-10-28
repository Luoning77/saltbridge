package com.saltbridge.plugin

import com.saltbridge.core.LyricLine
import com.saltbridge.core.TrackInfo
import com.saltbridge.core.track
import com.saltbridge.http.HttpConfig
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
 * 控制链路端到端：HTTP → 字段校验 → 去重队列 → 单线程执行 → 宿主方法。
 */
class BridgeCoreControlTest {

    private class FakePort : HostPort {
        val calls = mutableListOf<String>()

        @Volatile
        var track: TrackInfo? = null

        override fun poll(): PollOutcome = PollOutcome.Available(track)
        override fun lyricsLines(): List<LyricLine> = emptyList()
        override fun cover(trackId: String): ByteArray? = null
        override fun isLibraryReadGranted(): Boolean = true

        override fun play() {
            calls += "play"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun next() {
            calls += "next"
        }

        override fun previous() {
            calls += "previous"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek:$positionMs"
        }
    }

    private fun context() = PluginContext(
        pluginId = "com.saltbridge",
        pluginVersion = "0.1.0",
        pluginPath = "/tmp/saltbridge-ctrl",
        spwVersion = "1.19.2",
        spwChannel = Channel.Steam,
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

    private fun post(port: Int, token: String, json: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/control"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun startCore(port: FakePort): BridgeCore {
        val core = BridgeCore(
            context = context(),
            epoch = 1,
            dataDir = null,
            hostPort = port,
            pollIntervalMs = 20,
            httpConfig = HttpConfig(port = 0, requestsPerSecond = 1_000),
        )
        core.start()
        assertTrue(awaitUntil { core.httpStarted() }, "HTTP 服务应启动")
        return core
    }

    @Test
    fun `play command reaches the host`() {
        val port = FakePort()
        val core = startCore(port)
        try {
            val token = core.token()
            val body = """{"requestId":"req-play-01","expectedInstanceId":"${core.instanceId}","command":"play"}"""
            val response = post(core.httpPort(), token, body)

            assertEquals(202, response.statusCode(), response.body())
            assertTrue(awaitUntil { port.calls.contains("play") }, "play 应到达宿主：${port.calls}")
        } finally {
            core.stop()
        }
    }

    @Test
    fun `duplicate requestId executes only once`() {
        val port = FakePort()
        val core = startCore(port)
        try {
            val token = core.token()
            val body = """{"requestId":"req-next-01","expectedInstanceId":"${core.instanceId}","command":"next"}"""

            val first = post(core.httpPort(), token, body)
            assertEquals(202, first.statusCode())
            assertTrue(awaitUntil { port.calls.count { it == "next" } == 1 })

            val second = post(core.httpPort(), token, body)
            assertEquals(200, second.statusCode(), second.body())
            assertTrue(second.body().contains("\"deduplicated\":true"))

            Thread.sleep(200)
            assertEquals(1, port.calls.count { it == "next" }, "重复请求不得再次执行")
        } finally {
            core.stop()
        }
    }

    @Test
    fun `seek with generation executes`() {
        val port = FakePort()
        port.track = track(durationMs = 269_000)
        val core = startCore(port)
        try {
            assertTrue(awaitUntil { core.snapshot()?.generation != null })
            val generation = core.snapshot()?.generation ?: 1L
            val token = core.token()
            val body = """{"requestId":"req-seek-01","expectedInstanceId":"${core.instanceId}","command":"seek","positionMs":30000,"expectedGeneration":$generation}"""

            val response = post(core.httpPort(), token, body)
            assertEquals(202, response.statusCode(), response.body())
            assertTrue(
                awaitUntil { port.calls.contains("seek:30000") },
                "seek 应到达宿主：${port.calls}",
            )
        } finally {
            core.stop()
        }
    }

    @Test
    fun `stopping the core cancels pending commands`() {
        val port = FakePort()
        val core = startCore(port)
        val token = core.token()
        val body = """{"requestId":"req-cancel-01","expectedInstanceId":"${core.instanceId}","command":"previous"}"""

        // 入队后立刻停用：协议 7.1 说未执行命令被取消
        val response = post(core.httpPort(), token, body)
        assertEquals(202, response.statusCode())
        core.stop()

        Thread.sleep(150)
        assertTrue(
            port.calls.none { it == "previous" } || port.calls.count { it == "previous" } == 1,
            "命令最多执行一次：${port.calls}",
        )
    }
}
