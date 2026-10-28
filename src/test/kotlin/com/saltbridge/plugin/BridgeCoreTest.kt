package com.saltbridge.plugin

import com.saltbridge.core.Association
import com.saltbridge.core.FakeClock
import com.saltbridge.core.FakeScheduler
import com.saltbridge.core.LyricLine
import com.saltbridge.core.LyricsStatus
import com.saltbridge.core.StateEngine
import com.saltbridge.core.TrackInfo
import com.saltbridge.core.TrackStatus
import com.saltbridge.core.lines
import com.saltbridge.core.track
import com.saltbridge.http.HttpConfig
import com.saltbridge.wire.SnapshotJson
import com.xuncorp.spw.workshop.api.Channel
import com.xuncorp.spw.workshop.api.PluginContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 适配层行为测试。
 *
 * 这里**允许**真实线程与短等待：测的正是“回调线程 → events → 采样”的调度链路，
 * 不是时间语义。核心的时间语义已由 `com.saltbridge.core` 用可控时钟覆盖。
 *
 * HTTP 用 `port = 0` 随机端口，避免多个测试抢 8737。
 */
class BridgeCoreTest {

    private class FakePort(
        @Volatile var track: TrackInfo? = null,
        @Volatile var lyrics: List<LyricLine> = emptyList(),
        @Volatile var denied: Boolean = false,
        @Volatile var coverBytes: ByteArray? = null,
        @Volatile var libraryRead: Boolean = true,
    ) : HostPort {
        val calls = mutableListOf<String>()

        override fun poll(): PollOutcome =
            if (denied) PollOutcome.PermissionDenied else PollOutcome.Available(track)

        override fun lyricsLines(): List<LyricLine> = lyrics

        override fun cover(trackId: String): ByteArray? = coverBytes

        override fun isLibraryReadGranted(): Boolean = libraryRead

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
        pluginPath = "/tmp/saltbridge-test",
        spwVersion = "1.19.2",
        spwChannel = Channel.Steam,
    )

    private fun core(port: FakePort, pollIntervalMs: Long = 20) = BridgeCore(
        context = context(),
        epoch = 1,
        dataDir = null,
        hostPort = port,
        pollIntervalMs = pollIntervalMs,
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

    @Test
    fun `polling establishes track`() {
        val core = core(FakePort(track = track()))
        core.start()
        try {
            assertTrue(awaitUntil { core.snapshot()?.trackStatus == TrackStatus.AVAILABLE })
            assertEquals("t-1", core.snapshot()?.track?.id)
        } finally {
            core.stop()
        }
    }

    @Test
    fun `lyrics load intent triggers backfill through io thread`() {
        val core = core(FakePort(track = track(), lyrics = lines(5)), pollIntervalMs = 1_000)
        core.start()
        try {
            core.onLyricsLoadIntent(track())
            assertTrue(awaitUntil { core.snapshot()?.lyrics?.status == LyricsStatus.AVAILABLE })
            assertEquals(5, core.snapshot()?.lyrics?.lineCount)
            assertEquals(Association.ASSOCIATED, core.snapshot()?.lyrics?.association)
        } finally {
            core.stop()
        }
    }

    @Test
    fun `permission denied is surfaced to the snapshot`() {
        val core = core(FakePort(denied = true))
        core.start()
        try {
            assertTrue(awaitUntil { core.snapshot()?.trackStatus == TrackStatus.PERMISSION_DENIED })
        } finally {
            core.stop()
        }
    }

    @Test
    fun `position events reach the engine`() {
        val core = core(FakePort(track = track()), pollIntervalMs = 1_000)
        core.start()
        try {
            core.onPosition(42_000)
            assertTrue(awaitUntil { core.snapshot()?.playback?.position?.reportedMs == 42_000L })
        } finally {
            core.stop()
        }
    }

    @Test
    fun `snapshot json uses protocol names`() {
        val core = core(FakePort(track = track()))
        core.start()
        try {
            assertTrue(awaitUntil { core.snapshot()?.trackStatus == TrackStatus.AVAILABLE })
            val json = core.snapshotJson()
            assertEquals("1.0", json?.protocolVersion)
            assertEquals("available", json?.trackStatus)
            assertEquals("no_position", json?.playback?.animation?.reason)
            assertEquals("none", json?.lyrics?.association)
        } finally {
            core.stop()
        }
    }

    @Test
    fun `http server starts on random port and stops with the core`() {
        val core = core(FakePort(track = track()))
        core.start()
        try {
            assertTrue(awaitUntil { core.httpStarted() })
            assertTrue(core.httpPort() > 0, "应当绑定到一个实际端口")
        } finally {
            core.stop()
        }
        assertEquals(0, core.httpPort(), "停止后端口信息必须清空")
    }

    @Test
    fun `events after stop are ignored`() {
        val core = core(FakePort(track = track()))
        core.start()
        assertTrue(awaitUntil { core.snapshot() != null })

        core.stop()

        assertNull(core.snapshot())
        core.onIsPlaying(true) // 停用后宿主仍可能回调，不得抛异常
        core.onPosition(1_000)
        assertNull(core.snapshot())
    }

    @Test
    fun `token is stable within one instance and reset changes it`() {
        val core = core(FakePort())
        core.start()
        try {
            val first = core.token()
            assertEquals(first, core.token())
            assertTrue(first.startsWith("sb_"))

            val reset = core.resetToken()
            assertTrue(reset.startsWith("sb_"))
            assertTrue(reset != first)
            assertEquals(reset, core.token())
        } finally {
            core.stop()
        }
    }

    @Test
    fun `snapshot json maps empty engine`() {
        val clock = FakeClock()
        val engine = StateEngine("inst-1", clock, FakeScheduler(clock))
        val json = SnapshotJson.of(engine.snapshot())

        assertEquals("inst-1", json.instanceId)
        assertEquals("1.0", json.protocolVersion)
        assertEquals("unknown", json.trackStatus)
        assertNull(json.track)
        assertEquals("unknown", json.playback.state)
        assertNull(json.playback.isPlaying)
        assertEquals("disabled", json.playback.animation.mode)
        assertEquals("no_position", json.playback.animation.reason)
        assertEquals(0L, json.playback.animation.maxMs)
        assertEquals("none", json.playback.position.source)
        assertEquals("unknown", json.lyrics.status)
        assertEquals("none", json.lyrics.association)
        assertTrue(json.assumptions.isEmpty())
    }
}
