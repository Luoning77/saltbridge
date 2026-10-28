package com.saltbridge.core

data class PositionSample(val positionMs: Long, val atMs: Long)

/**
 * 活动推断：三个样本、两段连续增量，外加速率检查。
 *
 * 一次位置增加可能只是拖动或 seek，所以必须两段都合理；速率检查用来挡
 * “1 秒跳 30 秒”这类跳变。这里只描述**位置在怎么走**，不是播放状态。
 */
class ActivityTracker(private val config: EngineConfig) {

    private val samples = ArrayDeque<PositionSample>(4)

    fun onSample(sample: PositionSample) {
        samples.addLast(sample)
        while (samples.size > 3) samples.removeFirst()
    }

    /** 撤销推断并清空样本：切歌、seek、缓冲/结束/空闲、明确暂停都会调用。 */
    fun reset() {
        samples.clear()
    }

    fun lastSampleAtMs(): Long? = samples.lastOrNull()?.atMs

    fun compute(nowMs: Long): Activity {
        if (samples.size < 3) return Activity.UNKNOWN

        val oldest = samples[samples.size - 3]
        val middle = samples[samples.size - 2]
        val newest = samples[samples.size - 1]

        if (nowMs - newest.atMs > config.positionStaleAfterMs) return Activity.UNKNOWN

        val delta1 = middle.positionMs - oldest.positionMs
        val delta2 = newest.positionMs - middle.positionMs
        val gap1 = middle.atMs - oldest.atMs
        val gap2 = newest.atMs - middle.atMs

        if (gap1 <= 0 || gap2 <= 0) return Activity.UNKNOWN

        if (isReasonableDelta(delta1) && isReasonableDelta(delta2) &&
            gap1 <= config.activityMaxGapMs && gap2 <= config.activityMaxGapMs
        ) {
            val rate1 = delta1.toDouble() / gap1.toDouble()
            val rate2 = delta2.toDouble() / gap2.toDouble()
            if (rate1 in config.activityMinRate..config.activityMaxRate &&
                rate2 in config.activityMinRate..config.activityMaxRate
            ) {
                return Activity.ADVANCING
            }
        }

        if (middle.positionMs == newest.positionMs && gap2 >= config.stationaryMinGapMs) {
            return Activity.STATIONARY
        }

        return Activity.UNKNOWN
    }

    private fun isReasonableDelta(delta: Long): Boolean =
        delta >= config.activityMinDeltaMs && delta <= config.activityMaxDeltaMs
}
