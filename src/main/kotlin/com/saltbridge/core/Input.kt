package com.saltbridge.core

/**
 * 曲库轮询令牌：**提交时**捕获，结果返回时逐项比对。
 *
 * 三个字段各挡一类问题：
 * - `instanceId` / `epoch`：跨实例的迟到结果；
 * - `pollSeq`：同一实例内乱序或晚到的结果；
 * - `generationAtSubmit`：提交后已有更权威的切歌证据（如歌词加载意图）。
 */
data class PollToken(
    val instanceId: String,
    val epoch: Long,
    val pollSeq: Long,
    val generationAtSubmit: Long,
)

sealed interface Input {
    data class HostStateChanged(val state: HostState) : Input
    data class IsPlayingChanged(val isPlaying: Boolean) : Input
    data class SeekTo(val positionMs: Long) : Input
    data class PositionUpdated(val positionMs: Long) : Input
    data class LyricsLoadIntent(val track: TrackInfo) : Input
    data class LyricsPublished(val lines: List<LyricLine>) : Input
    data class PollResult(val token: PollToken, val track: TrackInfo?) : Input
    data class PollPermissionDenied(val token: PollToken) : Input
    data object Tick : Input
}
