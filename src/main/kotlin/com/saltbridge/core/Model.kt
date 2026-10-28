package com.saltbridge.core

/**
 * M3 纯状态核心的数据模型。
 *
 * 这里**不引用** `com.xuncorp.spw.workshop.api`：宿主类型在插件层转换成这些类型，
 * 核心只依赖 Kotlin 标准库，因此可以用可控时钟完整测试。
 */

enum class HostState { IDLE, BUFFERING, READY, ENDED }

enum class PlaybackState { UNKNOWN, STOPPED, BUFFERING, PLAYING, PAUSED, ENDED }

enum class TrackStatus { UNKNOWN, AVAILABLE, NONE, PERMISSION_DENIED }

enum class PositionSource { NONE, EVENT }

enum class Activity { UNKNOWN, ADVANCING, STATIONARY }

enum class AnimationMode { CONFIRMED, INFERRED, DISABLED }

enum class AnimationReason {
    IS_PLAYING,
    PROGRESS_INFERRED,
    PAUSED,
    BUFFERING,
    ENDED,
    STOPPED,
    NOT_ADVANCING,
    STALE_POSITION,
    NO_POSITION,
}

enum class LyricsStatus { UNKNOWN, AVAILABLE, STALE, UNAVAILABLE }

enum class Association { NONE, UNCONFIRMED, ASSOCIATED }

enum class TimingSanity { OK, SUSPECT }

/** 曲目元数据。`pathHash` 是路径的哈希，真实路径永不出现在核心与日志里。 */
data class TrackInfo(
    val id: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val durationMs: Long? = null,
    val pathHash: String = "",
)

data class LyricCell(val startMs: Long, val endMs: Long, val text: String)

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String? = null,
    val cells: List<LyricCell> = emptyList(),
) {
    val timing: String get() = if (cells.isEmpty()) "line" else "word"
}

data class Position(
    val reportedMs: Long? = null,
    val reportedAtMs: Long? = null,
    val source: PositionSource = PositionSource.NONE,
)

data class Animation(
    val mode: AnimationMode,
    val reason: AnimationReason,
    val maxMs: Long,
)

data class Playback(
    val state: PlaybackState,
    val isPlaying: Boolean?,
    val position: Position,
    val activity: Activity,
    val activityUpdatedAtMs: Long?,
    val animation: Animation,
    val lastEventAtMs: Long?,
)

data class LyricsSnapshot(
    val status: LyricsStatus,
    val association: Association,
    val version: Long,
    val lineCount: Int,
    val staleSinceMs: Long?,
    val staleUntilMs: Long?,
    val timingSanity: TimingSanity?,
    val updatedAtMs: Long?,
)

/** 在同一把状态锁内读取的曲目快照与歌词正文。 */
data class LyricsRead(val snapshot: Snapshot, val payload: LyricsPayload)

/** 歌词正文出口：只在 `/lyrics` 使用，`/status` 只给摘要。 */
data class LyricsPayload(
    val generation: Long,
    val status: LyricsStatus,
    val association: Association,
    val version: Long,
    val staleSinceMs: Long?,
    val staleUntilMs: Long?,
    val timingSanity: TimingSanity?,
    val lines: List<LyricLine>,
)

/** 对外只暴露不可变快照；`servedAtMs` 由响应层在序列化时取当前时钟。 */
data class Snapshot(
    val instanceId: String,
    val seq: Long,
    val servedAtMs: Long,
    val generation: Long,
    val trackStatus: TrackStatus,
    val track: TrackInfo?,
    val playback: Playback,
    val lyrics: LyricsSnapshot,
)

/** 引擎参数。默认值与 `docs/PROTOCOL.md` 的 `capabilities.limits` 一致。 */
data class EngineConfig(
    val positionStaleAfterMs: Long = 3_000,
    val confirmedAnimationMaxMs: Long = 3_000,
    val inferredAnimationMaxMs: Long = 1_500,
    val noTrackDebounceMs: Long = 4_000,
    val lyricsStaleGraceMs: Long = 2_000,
    val lyricsRecheckDelaysMs: List<Long> = listOf(300, 1_000),
    val activityMinDeltaMs: Long = 300,
    val activityMaxDeltaMs: Long = 2_500,
    val activityMaxGapMs: Long = 2_500,
    val activityMinRate: Double = 0.5,
    val activityMaxRate: Double = 1.5,
    val stationaryMinGapMs: Long = 1_000,
)
