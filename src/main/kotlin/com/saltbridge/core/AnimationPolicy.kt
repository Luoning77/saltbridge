package com.saltbridge.core

/**
 * 动画策略：把 `isPlaying`、`hostState`、位置锚点与 `activity` 合成一个明确结论。
 *
 * 顺序很重要：显式状态优先于推断。明确暂停/缓冲/结束/停止时，即使位置样本
 * 恰好还在前进，也不允许动画。
 */
object AnimationPolicy {

    fun evaluate(
        isPlaying: Boolean?,
        hostState: HostState?,
        position: Position,
        activity: Activity,
        nowMs: Long,
        config: EngineConfig,
    ): Animation {
        val reportedMs = position.reportedMs
        val reportedAtMs = position.reportedAtMs

        if (reportedMs == null || reportedAtMs == null || position.source == PositionSource.NONE) {
            return disabled(AnimationReason.NO_POSITION)
        }
        // 锚点来自未来：异常数据，宁可不动
        if (reportedAtMs > nowMs) return disabled(AnimationReason.NO_POSITION)

        when (hostState) {
            HostState.BUFFERING -> return disabled(AnimationReason.BUFFERING)
            HostState.ENDED -> return disabled(AnimationReason.ENDED)
            HostState.IDLE -> return disabled(AnimationReason.STOPPED)
            HostState.READY, null -> Unit
        }

        if (isPlaying == false) return disabled(AnimationReason.PAUSED)

        if (nowMs - reportedAtMs > config.positionStaleAfterMs) {
            return disabled(AnimationReason.STALE_POSITION)
        }

        if (activity != Activity.ADVANCING) return disabled(AnimationReason.NOT_ADVANCING)

        return if (isPlaying == true) {
            Animation(
                mode = AnimationMode.CONFIRMED,
                reason = AnimationReason.IS_PLAYING,
                maxMs = config.confirmedAnimationMaxMs,
            )
        } else {
            Animation(
                mode = AnimationMode.INFERRED,
                reason = AnimationReason.PROGRESS_INFERRED,
                maxMs = config.inferredAnimationMaxMs,
            )
        }
    }

    private fun disabled(reason: AnimationReason) =
        Animation(mode = AnimationMode.DISABLED, reason = reason, maxMs = 0)
}
