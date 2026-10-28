package com.saltbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsAssociationTest {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val engine = StateEngine("inst-1", clock, scheduler)

    @Test
    fun `publish without load intent is unconfirmed and not exposed`() {
        val token = engine.nextPollToken()
        engine.onPollResult(token, track()) // 身份由轮询建立，没有加载意图

        engine.onLyricsPublished(lines(10))

        val lyrics = engine.snapshot().lyrics
        assertEquals(Association.UNCONFIRMED, lyrics.association)
        assertEquals(LyricsStatus.UNKNOWN, lyrics.status)
        assertEquals(0, lyrics.version, "unconfirmed 的发布不计入版本")
        assertEquals(0, lyrics.lineCount)
    }

    @Test
    fun `publish after load intent is associated`() {
        engine.onLyricsLoadIntent(track())
        engine.onLyricsPublished(lines(10))

        val lyrics = engine.snapshot().lyrics
        assertEquals(Association.ASSOCIATED, lyrics.association)
        assertEquals(LyricsStatus.AVAILABLE, lyrics.status)
        assertEquals(10, lyrics.lineCount)
        assertEquals(1, lyrics.version)
    }

    @Test
    fun `unconfirmed then intent then publish recovers`() {
        val token = engine.nextPollToken()
        engine.onPollResult(token, track())
        engine.onLyricsPublished(lines(10))
        assertEquals(Association.UNCONFIRMED, engine.snapshot().lyrics.association)

        engine.onLyricsLoadIntent(track()) // 同曲，不增代次
        engine.onLyricsPublished(lines(10))

        val lyrics = engine.snapshot().lyrics
        assertEquals(Association.ASSOCIATED, lyrics.association)
        assertEquals(LyricsStatus.AVAILABLE, lyrics.status)
    }

    @Test
    fun `timing sanity flags broken timeline but keeps data`() {
        engine.onLyricsLoadIntent(track())
        val broken = listOf(
            LyricLine(startMs = 5_000, endMs = 4_000, text = "bad"),
            LyricLine(startMs = 1_000, endMs = 2_000, text = "backwards"),
        )
        engine.onLyricsPublished(broken)

        val lyrics = engine.snapshot().lyrics
        assertEquals(TimingSanity.SUSPECT, lyrics.timingSanity)
        assertEquals(2, lyrics.lineCount, "原始数据必须保留，服务端不修补")
    }
}
