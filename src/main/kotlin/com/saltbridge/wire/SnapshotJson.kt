package com.saltbridge.wire

import com.saltbridge.core.Activity
import com.saltbridge.core.AnimationMode
import com.saltbridge.core.AnimationReason
import com.saltbridge.core.Association
import com.saltbridge.core.LyricsStatus
import com.saltbridge.core.PlaybackState
import com.saltbridge.core.PositionSource
import com.saltbridge.core.Snapshot
import com.saltbridge.core.TimingSanity
import com.saltbridge.core.TrackStatus

/**
 * 把内部快照映射成协议 5.3 的字段形状。
 *
 * M5 的 HTTP 层会直接复用这里的 DTO 与序列化，避免“核心一套、接口又是一套”。
 */
object SnapshotJson {

    const val PROTOCOL_VERSION = "1.0"

    fun of(snapshot: Snapshot, servedAtMs: Long = snapshot.servedAtMs): StatusJson {
        val playback = snapshot.playback
        return StatusJson(
            instanceId = snapshot.instanceId,
            protocolVersion = PROTOCOL_VERSION,
            seq = snapshot.seq,
            servedAtMs = servedAtMs,
            generation = snapshot.generation,
            trackStatus = snapshot.trackStatus.protocolName,
            track = snapshot.track?.let { track ->
                TrackJson(
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    albumArtist = track.albumArtist,
                    durationMs = track.durationMs,
                    year = null,
                    genre = null,
                    trackNumber = null,
                    discNumber = null,
                    favorite = null,
                )
            },
            playback = PlaybackJson(
                state = playback.state.protocolName,
                isPlaying = playback.isPlaying,
                position = PositionJson(
                    reportedMs = playback.position.reportedMs,
                    reportedAtMs = playback.position.reportedAtMs,
                    source = playback.position.source.protocolName,
                ),
                activity = playback.activity.protocolName,
                activityUpdatedAtMs = playback.activityUpdatedAtMs,
                animation = AnimationJson(
                    mode = playback.animation.mode.protocolName,
                    reason = playback.animation.reason.protocolName,
                    maxMs = playback.animation.maxMs,
                ),
                lastEventAtMs = playback.lastEventAtMs,
            ),
            lyrics = LyricsJson(
                status = snapshot.lyrics.status.protocolName,
                association = snapshot.lyrics.association.protocolName,
                version = snapshot.lyrics.version,
                lineCount = snapshot.lyrics.lineCount,
                staleSinceMs = snapshot.lyrics.staleSinceMs,
                staleUntilMs = snapshot.lyrics.staleUntilMs,
                timingSanity = snapshot.lyrics.timingSanity?.protocolName,
                updatedAtMs = snapshot.lyrics.updatedAtMs,
            ),
            assumptions = emptyList(),
        )
    }
}

data class StatusJson(
    val instanceId: String,
    val protocolVersion: String,
    val seq: Long,
    val servedAtMs: Long,
    val generation: Long,
    val trackStatus: String,
    val track: TrackJson?,
    val playback: PlaybackJson,
    val lyrics: LyricsJson,
    val assumptions: List<String>,
)

data class TrackJson(
    val id: String?,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationMs: Long?,
    val year: Int?,
    val genre: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val favorite: Boolean?,
)

data class PlaybackJson(
    val state: String,
    val isPlaying: Boolean?,
    val position: PositionJson,
    val activity: String,
    val activityUpdatedAtMs: Long?,
    val animation: AnimationJson,
    val lastEventAtMs: Long?,
)

data class PositionJson(
    val reportedMs: Long?,
    val reportedAtMs: Long?,
    val source: String,
)

data class AnimationJson(
    val mode: String,
    val reason: String,
    val maxMs: Long,
)

data class LyricsJson(
    val status: String,
    val association: String,
    val version: Long,
    val lineCount: Int,
    val staleSinceMs: Long?,
    val staleUntilMs: Long?,
    val timingSanity: String?,
    val updatedAtMs: Long?,
)

val TrackStatus.protocolName: String
    get() = when (this) {
        TrackStatus.UNKNOWN -> "unknown"
        TrackStatus.AVAILABLE -> "available"
        TrackStatus.NONE -> "none"
        TrackStatus.PERMISSION_DENIED -> "permission_denied"
    }

val PlaybackState.protocolName: String
    get() = when (this) {
        PlaybackState.UNKNOWN -> "unknown"
        PlaybackState.STOPPED -> "stopped"
        PlaybackState.BUFFERING -> "buffering"
        PlaybackState.PLAYING -> "playing"
        PlaybackState.PAUSED -> "paused"
        PlaybackState.ENDED -> "ended"
    }

val PositionSource.protocolName: String
    get() = when (this) {
        PositionSource.NONE -> "none"
        PositionSource.EVENT -> "event"
    }

val Activity.protocolName: String
    get() = when (this) {
        Activity.UNKNOWN -> "unknown"
        Activity.ADVANCING -> "advancing"
        Activity.STATIONARY -> "stationary"
    }

val AnimationMode.protocolName: String
    get() = when (this) {
        AnimationMode.CONFIRMED -> "confirmed"
        AnimationMode.INFERRED -> "inferred"
        AnimationMode.DISABLED -> "disabled"
    }

val AnimationReason.protocolName: String
    get() = when (this) {
        AnimationReason.IS_PLAYING -> "is_playing"
        AnimationReason.PROGRESS_INFERRED -> "progress_inferred"
        AnimationReason.PAUSED -> "paused"
        AnimationReason.BUFFERING -> "buffering"
        AnimationReason.ENDED -> "ended"
        AnimationReason.STOPPED -> "stopped"
        AnimationReason.NOT_ADVANCING -> "not_advancing"
        AnimationReason.STALE_POSITION -> "stale_position"
        AnimationReason.NO_POSITION -> "no_position"
    }

val LyricsStatus.protocolName: String
    get() = when (this) {
        LyricsStatus.UNKNOWN -> "unknown"
        LyricsStatus.AVAILABLE -> "available"
        LyricsStatus.STALE -> "stale"
        LyricsStatus.UNAVAILABLE -> "unavailable"
    }

val Association.protocolName: String
    get() = when (this) {
        Association.NONE -> "none"
        Association.UNCONFIRMED -> "unconfirmed"
        Association.ASSOCIATED -> "associated"
    }

val TimingSanity.protocolName: String
    get() = when (this) {
        TimingSanity.OK -> "ok"
        TimingSanity.SUSPECT -> "suspect"
    }
