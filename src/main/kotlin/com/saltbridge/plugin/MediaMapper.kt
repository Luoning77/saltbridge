package com.saltbridge.plugin

import com.saltbridge.core.LyricCell
import com.saltbridge.core.LyricLine
import com.saltbridge.core.TrackInfo
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint as Host
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 宿主类型 → 核心类型。
 *
 * 这一层是**唯一**允许 import `com.xuncorp.spw.workshop.api` 的地方；
 * `com.saltbridge.core` 保持纯 Kotlin，可以用可控时钟完整测试。
 */
object MediaMapper {

    fun toTrack(item: Host.MediaItem): TrackInfo = TrackInfo(
        id = item.id.ifBlank { null },
        title = item.title,
        artist = item.artist,
        album = item.album,
        albumArtist = item.albumArtist,
        durationMs = item.duration.takeIf { it > 0 },
        pathHash = pathHash(item.path),
    )

    fun toLyrics(lines: List<Host.LyricsLine>): List<LyricLine> = lines.map { line ->
        LyricLine(
            startMs = line.startTime,
            endMs = line.endTime,
            text = line.pureMainText,
            translation = line.pureSubText,
            cells = line.lyricsCells.map { cell ->
                LyricCell(startMs = cell.startTime, endMs = cell.endTime, text = cell.text)
            },
        )
    }

    fun toCoreState(state: Host.State): com.saltbridge.core.HostState = when (state) {
        Host.State.Idle -> com.saltbridge.core.HostState.IDLE
        Host.State.Buffering -> com.saltbridge.core.HostState.BUFFERING
        Host.State.Ready -> com.saltbridge.core.HostState.READY
        Host.State.Ended -> com.saltbridge.core.HostState.ENDED
    }

    /**
     * 真实路径**永不离开这一层**：只保留 SHA-256 前 16 个十六进制字符，
     * 用于同曲识别（文件被移动/标签不变时仍能匹配到同一 hash 的概率极低）。
     */
    fun pathHash(path: String): String {
        if (path.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { byte -> "%02x".format(byte) }
        return hex.take(16)
    }
}
