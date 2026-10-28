package com.saltbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityAndAnimationTest {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val engine = StateEngine("inst-1", clock, scheduler)

    private fun feedAdvancingSamples(startMs: Long = 8_000) {
        engine.onPositionUpdated(startMs)
        clock.advanceBy(1_000)
        engine.onPositionUpdated(startMs + 1_000)
        clock.advanceBy(1_000)
        engine.onPositionUpdated(startMs + 2_000)
    }

    @Test
    fun `three reasonable samples with isPlaying true give confirmed`() {
        engine.onIsPlayingChanged(true)
        feedAdvancingSamples()

        val snapshot = engine.snapshot()
        assertEquals(Activity.ADVANCING, snapshot.playback.activity)
        assertEquals(AnimationMode.CONFIRMED, snapshot.playback.animation.mode)
        assertEquals(AnimationReason.IS_PLAYING, snapshot.playback.animation.reason)
        assertEquals(3_000L, snapshot.playback.animation.maxMs)
        // isPlaying 已知但 hostState 未知：state 仍是 unknown，动画却可以确认
        assertEquals(PlaybackState.UNKNOWN, snapshot.playback.state)
        assertEquals(true, snapshot.playback.isPlaying)
    }

    @Test
    fun `missing isPlaying still allows inferred animation`() {
        feedAdvancingSamples()

        val snapshot = engine.snapshot()
        assertEquals(Activity.ADVANCING, snapshot.playback.activity)
        assertEquals(AnimationMode.INFERRED, snapshot.playback.animation.mode)
        assertEquals(AnimationReason.PROGRESS_INFERRED, snapshot.playback.animation.reason)
        assertEquals(1_500L, snapshot.playback.animation.maxMs)
        assertEquals(PlaybackState.UNKNOWN, snapshot.playback.state)
        assertNull(snapshot.playback.isPlaying)
    }

    @Test
    fun `two samples are not enough`() {
        engine.onPositionUpdated(8_000)
        clock.advanceBy(1_000)
        engine.onPositionUpdated(9_000)

        val snapshot = engine.snapshot()
        assertEquals(Activity.UNKNOWN, snapshot.playback.activity)
        assertEquals(AnimationMode.DISABLED, snapshot.playback.animation.mode)
        assertEquals(AnimationReason.NOT_ADVANCING, snapshot.playback.animation.reason)
        assertEquals(0L, snapshot.playback.animation.maxMs)
    }

    @Test
    fun `one second jump of thirty seconds is rejected by rate check`() {
        engine.onPositionUpdated(0)
        clock.advanceBy(1_000)
        engine.onPositionUpdated(1_000)
        clock.advanceBy(1_000)
        engine.onPositionUpdated(31_000)

        val snapshot = engine.snapshot()
        assertEquals(Activity.UNKNOWN, snapshot.playback.activity)
        assertEquals(AnimationMode.DISABLED, snapshot.playback.animation.mode)
    }

    @Test
    fun `stationary is not paused`() {
        engine.onPositionUpdated(10_000)
        clock.advanceBy(1_000)
        engine.onPositionUpdated(10_000)
        clock.advanceBy(1_000)
        engine.onPositionUpdated(10_000)

        val snapshot = engine.snapshot()
        assertEquals(Activity.STATIONARY, snapshot.playback.activity)
        assertEquals(AnimationMode.DISABLED, snapshot.playback.animation.mode)
        assertNull(snapshot.playback.isPlaying)
    }

    @Test
    fun `explicit pause beats advancing activity`() {
        feedAdvancingSamples()
        engine.onIsPlayingChanged(false)

        val snapshot = engine.snapshot()
        assertEquals(AnimationMode.DISABLED, snapshot.playback.animation.mode)
        assertEquals(AnimationReason.PAUSED, snapshot.playback.animation.reason)
        assertEquals(false, snapshot.playback.isPlaying)
        // 同样因为缺 hostState，state 仍是 unknown；暂停是 isPlaying 给出的事实
        assertEquals(PlaybackState.UNKNOWN, snapshot.playback.state)
    }

    @Test
    fun `buffering ended and idle all disable animation`() {
        feedAdvancingSamples()
        engine.onIsPlayingChanged(true)

        engine.onHostStateChanged(HostState.BUFFERING)
        assertEquals(AnimationReason.BUFFERING, engine.snapshot().playback.animation.reason)

        engine.onHostStateChanged(HostState.ENDED)
        assertEquals(AnimationReason.ENDED, engine.snapshot().playback.animation.reason)

        engine.onHostStateChanged(HostState.IDLE)
        assertEquals(AnimationReason.STOPPED, engine.snapshot().playback.animation.reason)
    }

    @Test
    fun `seek invalidates activity until three new samples`() {
        feedAdvancingSamples()
        engine.onIsPlayingChanged(true)
        assertEquals(Activity.ADVANCING, engine.snapshot().playback.activity)

        engine.onSeekTo(50_000)
        assertEquals(Activity.UNKNOWN, engine.snapshot().playback.activity)
        assertEquals(AnimationMode.DISABLED, engine.snapshot().playback.animation.mode)

        clock.advanceBy(1_000)
        engine.onPositionUpdated(51_000)
        clock.advanceBy(1_000)
        engine.onPositionUpdated(52_000)
        assertEquals(AnimationMode.DISABLED, engine.snapshot().playback.animation.mode)

        clock.advanceBy(1_000)
        engine.onPositionUpdated(53_000)
        assertEquals(Activity.ADVANCING, engine.snapshot().playback.activity)
        assertEquals(AnimationMode.CONFIRMED, engine.snapshot().playback.animation.mode)
    }

    @Test
    fun `stale position disables animation`() {
        feedAdvancingSamples()
        engine.onIsPlayingChanged(true)

        clock.set(clock.nowMs() + 3_001)
        val snapshot = engine.snapshot()
        assertEquals(Activity.UNKNOWN, snapshot.playback.activity)
        assertEquals(AnimationMode.DISABLED, snapshot.playback.animation.mode)
        assertEquals(AnimationReason.STALE_POSITION, snapshot.playback.animation.reason)
    }

    @Test
    fun `state is derived from hostState and isPlaying independently`() {
        assertEquals(PlaybackState.UNKNOWN, engine.snapshot().playback.state)

        engine.onIsPlayingChanged(true)
        assertEquals(PlaybackState.UNKNOWN, engine.snapshot().playback.state)

        engine.onHostStateChanged(HostState.READY)
        assertEquals(PlaybackState.PLAYING, engine.snapshot().playback.state)

        engine.onIsPlayingChanged(false)
        assertEquals(PlaybackState.PAUSED, engine.snapshot().playback.state)
    }
}
