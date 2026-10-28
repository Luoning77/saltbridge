package com.saltbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsyncTokenTest {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val backfills = mutableListOf<LyricsQuery>()

    private val trackA = track()
    private val trackB = track(id = "t-2", title = "另一首", album = "另一张", pathHash = "h2")

    private fun newEngine(): StateEngine = StateEngine(
        instanceId = "inst-1",
        clock = clock,
        scheduler = scheduler,
        onBackfillRequested = { backfills += it },
    )

    @Test
    fun `poll result submitted before track change is dropped`() {
        val engine = newEngine()
        engine.onLyricsLoadIntent(trackA)
        assertEquals(1, engine.currentGeneration)

        val token = engine.nextPollToken() // 提交时 generation = 1

        engine.onLyricsLoadIntent(trackB) // 期间切歌 → generation = 2
        assertEquals(2, engine.currentGeneration)

        engine.onPollResult(token, trackA) // 旧结果返回

        val snapshot = engine.snapshot()
        assertEquals(2, snapshot.generation, "旧结果不得把代次推回")
        assertEquals("t-2", snapshot.track?.id, "旧结果不得覆盖当前曲目")
    }

    @Test
    fun `out of order poll results keep the newest`() {
        val engine = newEngine()
        engine.onLyricsLoadIntent(trackA)

        val first = engine.nextPollToken()
        val second = engine.nextPollToken()

        engine.onPollResult(second, trackA.copy(album = "新专辑"))
        val revisionAfterNewest = engine.currentTrackRevision
        assertEquals("新专辑", engine.snapshot().track?.album)

        engine.onPollResult(first, trackA.copy(album = "旧专辑"))

        assertEquals(
            revisionAfterNewest,
            engine.currentTrackRevision,
            "更早提交的结果必须被丢弃",
        )
        assertEquals("新专辑", engine.snapshot().track?.album)
    }

    @Test
    fun `poll result from another instance is dropped`() {
        val engine = newEngine()
        engine.onLyricsLoadIntent(trackA)
        val revision = engine.currentTrackRevision

        val foreign = PollToken(
            instanceId = "other-instance",
            epoch = engine.epoch,
            pollSeq = 99,
            generationAtSubmit = engine.currentGeneration,
        )
        engine.onPollResult(foreign, trackB)

        assertEquals(revision, engine.currentTrackRevision)
        assertEquals("t-1", engine.snapshot().track?.id)
    }

    @Test
    fun `poll result from another epoch is dropped`() {
        val engine = newEngine()
        engine.onLyricsLoadIntent(trackA)
        val revision = engine.currentTrackRevision

        val staleEpoch = PollToken(
            instanceId = "inst-1",
            epoch = engine.epoch + 1,
            pollSeq = 99,
            generationAtSubmit = engine.currentGeneration,
        )
        engine.onPollResult(staleEpoch, trackB)

        assertEquals(revision, engine.currentTrackRevision)
    }

    @Test
    fun `backfill survives track revision changes`() {
        val engine = newEngine()
        engine.onLyricsLoadIntent(track(durationMs = null))
        assertTrue(backfills.isNotEmpty(), "身份建立后必须安排回填")
        val query = backfills.last()

        // 同曲轮询补全时长 → trackRevision 变化
        val token = engine.nextPollToken()
        engine.onPollResult(token, track(durationMs = 300_000))

        engine.completeLyricsBackfill(query, lines(20))

        val lyrics = engine.snapshot().lyrics
        assertEquals(Association.ASSOCIATED, lyrics.association)
        assertEquals(LyricsStatus.AVAILABLE, lyrics.status)
        assertEquals(20, lyrics.lineCount)
    }

    @Test
    fun `backfill from a previous generation is dropped`() {
        val engine = newEngine()
        engine.onLyricsLoadIntent(track(durationMs = null))
        val query = backfills.last()

        engine.onLyricsLoadIntent(trackB)
        engine.completeLyricsBackfill(query, lines(20))

        val lyrics = engine.snapshot().lyrics
        assertEquals(LyricsStatus.UNKNOWN, lyrics.status)
        assertEquals(0, lyrics.lineCount)
    }
}
