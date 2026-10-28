package com.saltbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsStaleTest {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val rechecks = mutableListOf<LyricsQuery>()
    private val backfills = mutableListOf<LyricsQuery>()

    private val engine = StateEngine(
        instanceId = "inst-1",
        clock = clock,
        scheduler = scheduler,
        onBackfillRequested = { backfills += it },
        onRecheckRequested = { rechecks += it },
    )

    private val trackA = track()

    private fun establishLyrics(count: Int = 39) {
        engine.onLyricsLoadIntent(trackA)
        engine.onLyricsPublished(lines(count))
    }

    @Test
    fun `empty publish marks stale and keeps lines`() {
        establishLyrics()
        val before = engine.snapshot().lyrics
        assertEquals(LyricsStatus.AVAILABLE, before.status)

        engine.onLyricsPublished(emptyList())
        val stale = engine.snapshot().lyrics

        assertEquals(LyricsStatus.STALE, stale.status)
        assertEquals(39, stale.lineCount)
        assertEquals(before.version + 1, stale.version)
        assertEquals(0L, stale.staleSinceMs)
        assertEquals(2_000L, stale.staleUntilMs)
        assertTrue(stale.staleUntilMs != null)
    }

    @Test
    fun `repeated empty publishes do not extend or clear`() {
        establishLyrics()
        engine.onLyricsPublished(emptyList())
        val first = engine.snapshot().lyrics

        clock.advanceBy(500)
        engine.onLyricsPublished(emptyList())
        clock.advanceBy(500)
        engine.onLyricsPublished(emptyList())
        val repeated = engine.snapshot().lyrics

        assertEquals(first.staleUntilMs, repeated.staleUntilMs, "截止时间不得延长")
        assertEquals(first.staleSinceMs, repeated.staleSinceMs)
        assertEquals(39, repeated.lineCount, "重复空事件不得清空")
        assertEquals(first.version + 2, repeated.version, "每次发布仍计数")
    }

    @Test
    fun `recheck with identical content restores available`() {
        establishLyrics()
        engine.onLyricsPublished(emptyList())

        scheduler.advanceTo(300)
        assertEquals(1, rechecks.size)
        val query = rechecks.last()

        clock.set(300)
        engine.completeLyricsRecheck(query, lines(39))

        val lyrics = engine.snapshot().lyrics
        assertEquals(LyricsStatus.AVAILABLE, lyrics.status, "内容相同也必须恢复")
        assertNull(lyrics.staleUntilMs)
        assertNull(lyrics.staleSinceMs)
        assertEquals(39, lyrics.lineCount)
    }

    @Test
    fun `deadline with empty content clears everything`() {
        establishLyrics()
        engine.onLyricsPublished(emptyList())

        scheduler.advanceTo(300)
        engine.completeLyricsRecheck(rechecks.last(), emptyList())
        assertEquals(LyricsStatus.STALE, engine.snapshot().lyrics.status)

        scheduler.advanceTo(1_000)
        engine.completeLyricsRecheck(rechecks.last(), emptyList())
        assertEquals(LyricsStatus.STALE, engine.snapshot().lyrics.status)

        scheduler.advanceTo(2_000)
        engine.completeLyricsRecheck(rechecks.last(), emptyList())

        val lyrics = engine.snapshot().lyrics
        assertEquals(LyricsStatus.UNAVAILABLE, lyrics.status)
        assertEquals(0, lyrics.lineCount)
        assertNull(lyrics.staleUntilMs)
    }

    @Test
    fun `metadata completion does not freeze stale deadline`() {
        // 初始意图没有时长，后续轮询补全 → trackRevision 变化
        engine.onLyricsLoadIntent(track(durationMs = null))
        engine.onLyricsPublished(lines(39))
        engine.onLyricsPublished(emptyList())

        val revisionBefore = engine.currentTrackRevision
        val token = engine.nextPollToken()
        engine.onPollResult(token, track(durationMs = 300_000))
        assertTrue(
            engine.currentTrackRevision > revisionBefore,
            "补全元数据必须真的推进 trackRevision，否则这个用例没有意义",
        )

        rechecks.clear()
        scheduler.advanceTo(2_000)
        assertTrue(rechecks.isNotEmpty(), "到期任务不得因元数据补全被丢弃")

        engine.completeLyricsRecheck(rechecks.last(), emptyList())
        assertEquals(LyricsStatus.UNAVAILABLE, engine.snapshot().lyrics.status)
    }

    @Test
    fun `metadata completion during stale still restores on non empty recheck`() {
        engine.onLyricsLoadIntent(track(durationMs = null))
        engine.onLyricsPublished(lines(39))
        engine.onLyricsPublished(emptyList())

        val token = engine.nextPollToken()
        engine.onPollResult(token, track(durationMs = 300_000))

        scheduler.advanceTo(300)
        engine.completeLyricsRecheck(rechecks.last(), lines(39))

        val lyrics = engine.snapshot().lyrics
        assertEquals(LyricsStatus.AVAILABLE, lyrics.status)
        assertNull(lyrics.staleUntilMs)
    }

    @Test
    fun `old token timers cannot hurt recovered state`() {
        establishLyrics()
        engine.onLyricsPublished(emptyList())

        scheduler.advanceTo(300)
        engine.completeLyricsRecheck(rechecks.last(), lines(39))
        assertEquals(LyricsStatus.AVAILABLE, engine.snapshot().lyrics.status)

        val countAfterRecovery = rechecks.size
        scheduler.advanceTo(2_000)

        assertEquals(
            countAfterRecovery,
            rechecks.size,
            "已恢复后，本周期剩余的回查与到期任务必须静默失效",
        )
        assertEquals(LyricsStatus.AVAILABLE, engine.snapshot().lyrics.status)
    }

    @Test
    fun `recheck finishing after track change is dropped`() {
        establishLyrics()
        engine.onLyricsPublished(emptyList())

        scheduler.advanceTo(300)
        val query = rechecks.last()

        engine.onLyricsLoadIntent(track(id = "t-2", title = "另一首", pathHash = "h2"))
        engine.completeLyricsRecheck(query, lines(39))

        val lyrics = engine.snapshot().lyrics
        assertEquals(LyricsStatus.UNKNOWN, lyrics.status, "新代次不得被旧检查结果写入")
        assertEquals(0, lyrics.lineCount)
    }

    @Test
    fun `empty publish without any timeline becomes unavailable immediately`() {
        engine.onLyricsLoadIntent(trackA)
        engine.onLyricsPublished(emptyList())

        val lyrics = engine.snapshot().lyrics
        assertEquals(LyricsStatus.UNAVAILABLE, lyrics.status)
        assertEquals(0, lyrics.lineCount)
        assertNull(lyrics.staleUntilMs)
    }
}
