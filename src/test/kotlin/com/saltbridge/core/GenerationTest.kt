package com.saltbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenerationTest {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val backfills = mutableListOf<LyricsQuery>()

    private val engine = StateEngine(
        instanceId = "inst-1",
        clock = clock,
        scheduler = scheduler,
        onBackfillRequested = { backfills += it },
    )

    @Test
    fun `track disappears then same track returns keeps generation and restores`() {
        engine.onLyricsLoadIntent(track())
        engine.onPositionUpdated(1_000)
        val generation = engine.currentGeneration

        var token = engine.nextPollToken()
        engine.onPollResult(token, null)
        clock.advanceBy(4_000)
        token = engine.nextPollToken()
        engine.onPollResult(token, null)

        val none = engine.snapshot()
        assertEquals(TrackStatus.NONE, none.trackStatus)
        assertNull(none.track)
        assertEquals(generation, none.generation, "无曲目不得改变代次")
        assertNull(none.playback.position.reportedMs, "无曲目必须清空位置")
        assertEquals(AnimationMode.DISABLED, none.playback.animation.mode)

        val backfillsBefore = backfills.size
        token = engine.nextPollToken()
        engine.onPollResult(token, track())

        val restored = engine.snapshot()
        assertEquals(TrackStatus.AVAILABLE, restored.trackStatus, "同曲恢复必须回到 AVAILABLE")
        assertEquals("t-1", restored.track?.id)
        assertEquals(generation, restored.generation, "同曲恢复不得增代次")
        assertNull(restored.playback.position.reportedMs)
        assertTrue(backfills.size > backfillsBefore, "恢复时必须重新安排歌词回填")
    }

    @Test
    fun `id is completed without bumping generation`() {
        engine.onLyricsLoadIntent(track(id = null, durationMs = null))
        val generation = engine.currentGeneration
        assertNull(engine.snapshot().track?.id)

        val token = engine.nextPollToken()
        engine.onPollResult(token, track(id = "lib-9", durationMs = 300_000))

        val snapshot = engine.snapshot()
        assertEquals(generation, snapshot.generation, "补全 ID 不得增代次")
        assertEquals("lib-9", snapshot.track?.id)
        assertEquals(300_000L, snapshot.track?.durationMs)
    }

    @Test
    fun `different track bumps generation and clears playback`() {
        engine.onLyricsLoadIntent(track())
        engine.onPositionUpdated(5_000)
        engine.onIsPlayingChanged(true)
        val generation = engine.currentGeneration

        engine.onLyricsLoadIntent(track(id = "t-2", title = "另一首", pathHash = "h2"))

        val snapshot = engine.snapshot()
        assertEquals(generation + 1, snapshot.generation)
        assertNull(snapshot.playback.position.reportedMs)
        assertEquals(AnimationMode.DISABLED, snapshot.playback.animation.mode)
    }

    @Test
    fun `A to B to A bumps twice`() {
        engine.onLyricsLoadIntent(track())
        val first = engine.currentGeneration
        engine.onLyricsLoadIntent(track(id = "t-2", title = "B", pathHash = "h2"))
        val second = engine.currentGeneration
        engine.onLyricsLoadIntent(track())
        val third = engine.currentGeneration

        assertEquals(first + 1, second)
        assertEquals(first + 2, third)
    }

    @Test
    fun `permission denied without callback metadata`() {
        val token = engine.nextPollToken()
        engine.onPollPermissionDenied(token)

        assertEquals(TrackStatus.PERMISSION_DENIED, engine.snapshot().trackStatus)
    }

    @Test
    fun `permission denied keeps callback metadata`() {
        engine.onLyricsLoadIntent(track())
        val token = engine.nextPollToken()
        engine.onPollPermissionDenied(token)

        val snapshot = engine.snapshot()
        assertEquals(TrackStatus.AVAILABLE, snapshot.trackStatus)
        assertEquals("t-1", snapshot.track?.id)
    }

    @Test
    fun `no track debounce needs the full window`() {
        engine.onLyricsLoadIntent(track())

        var token = engine.nextPollToken()
        engine.onPollResult(token, null)

        clock.advanceBy(3_900)
        token = engine.nextPollToken()
        engine.onPollResult(token, null)
        assertEquals(TrackStatus.AVAILABLE, engine.snapshot().trackStatus, "不足 4 秒不得判定无曲目")

        clock.advanceBy(200)
        token = engine.nextPollToken()
        engine.onPollResult(token, null)
        assertEquals(TrackStatus.NONE, engine.snapshot().trackStatus)
    }
}
