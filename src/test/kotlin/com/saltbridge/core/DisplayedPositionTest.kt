package com.saltbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 位置显示语义：核心只给锚点与上限，客户端维护显示值。
 *
 * 这个测试锁死本轮最重要的一条：**外推到上限后收到同一锚点的过期响应，显示值不能倒退**。
 */
class DisplayedPositionTest {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val engine = StateEngine("inst-1", clock, scheduler)

    /** 建立三个合理样本：锚点 = 10000 @ t=2000。 */
    private fun establishAnchor() {
        engine.onIsPlayingChanged(true)
        engine.onPositionUpdated(8_000)
        clock.advanceBy(1_000)
        engine.onPositionUpdated(9_000)
        clock.advanceBy(1_000)
        engine.onPositionUpdated(10_000)
    }

    @Test
    fun `full sequence - extrapolate to cap then stale response must not rewind`() {
        establishAnchor()
        val display = DisplayedPosition(staleAfterMs = 3_000)

        // 1. 刚好到上限：elapsed = 3000，仍是 confirmed
        clock.set(5_000)
        val atCap = engine.snapshot()
        assertEquals(AnimationMode.CONFIRMED, atCap.playback.animation.mode)
        assertEquals(AnimationReason.IS_PLAYING, atCap.playback.animation.reason)
        assertEquals(13_000L, display.update(atCap, localReceivedAtMs = 5_000, localNowMs = 5_000))

        // 2. 同一锚点的过期响应：mode 变 disabled(stale_position)
        clock.set(5_001)
        val stale = engine.snapshot()
        assertEquals(AnimationMode.DISABLED, stale.playback.animation.mode)
        assertEquals(AnimationReason.STALE_POSITION, stale.playback.animation.reason)
        assertEquals(10_000L, stale.playback.position.reportedMs)
        assertEquals(
            13_000L,
            display.update(stale, localReceivedAtMs = 5_001, localNowMs = 5_001),
            "过期响应不得把显示位置倒回锚点",
        )

        // 3. 连续多次过期响应也不抖动
        clock.set(6_000)
        assertEquals(13_000L, display.update(engine.snapshot(), 6_000, 6_000))
        clock.set(7_000)
        assertEquals(13_000L, display.update(engine.snapshot(), 7_000, 7_000))

        // 4. 新锚点向前：允许前进
        clock.set(7_100)
        engine.onPositionUpdated(14_000)
        val moved = engine.snapshot()
        assertEquals(14_000L, display.update(moved, 7_100, 7_100))

        // 5. seek 向后：允许下调（明确回退证据）
        clock.set(7_200)
        engine.onSeekTo(5_000)
        val sought = engine.snapshot()
        assertEquals(5_000L, display.update(sought, 7_200, 7_200))

        // 6. 切歌：重置为空锚点
        clock.set(7_300)
        engine.onLyricsLoadIntent(track(id = "t-2", title = "另一首", pathHash = "h2"))
        val switched = engine.snapshot()
        assertNull(display.update(switched, 7_300, 7_300))
    }

    @Test
    fun `local elapsed time is part of the formula`() {
        establishAnchor()
        val display = DisplayedPosition(staleAfterMs = 3_000)

        clock.set(3_000) // servedAt - reportedAt = 1000
        val snapshot = engine.snapshot()
        // 响应到达后本地又过了 500 ms
        assertEquals(11_500L, display.update(snapshot, localReceivedAtMs = 3_000, localNowMs = 3_500))
    }

    @Test
    fun `cap is respected no matter how long we wait`() {
        establishAnchor()
        val display = DisplayedPosition(staleAfterMs = 3_000)

        clock.set(4_999) // elapsed = 2999
        assertEquals(12_999L, display.update(engine.snapshot(), 4_999, 4_999))
    }
}
