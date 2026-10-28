package com.saltbridge.probe

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.State

/**
 * M2 探针的事件记录实现，**已不再注册为扩展**（`@Extension` 已移除）。
 *
 * 保留原因：它是唯一一份“宿主回调 → 事件文本”的实测代码，M4 的映射可对照它。
 * 探针二进制（`plugin-com.saltbridge.probe-0.1.0.spmod`）已单独构建，不受此处影响。
 * 如果保留 `@Extension`，PF4J 会同时实例化两个 `PlaybackExtensionPoint`，
 * 宿主事件会被消费两次（虽然只是记录，但没有意义且会干扰日志）。
 */
class PlaybackProbe : PlaybackExtensionPoint {

    private fun session(): ProbeSession? = ProbeController.current()

    override fun onStateChanged(state: State) {
        session()?.accept("onStateChanged", "state=$state")
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        session()?.accept("onIsPlayingChanged", "isPlaying=$isPlaying")
    }

    override fun onSeekTo(position: Long) {
        session()?.accept("onSeekTo", "positionMs=$position")
    }

    override fun onBeforeLoadLyrics(mediaItem: MediaItem): String? {
        logMedia("onBeforeLoadLyrics", mediaItem)
        return null
    }

    override fun onAfterLoadLyrics(mediaItem: MediaItem): String? {
        logMedia("onAfterLoadLyrics", mediaItem)
        return null
    }

    override fun onLyricsLineUpdated(lyricsLine: LyricsLine?) {
        val detail = if (lyricsLine == null) {
            "line=null"
        } else {
            "startMs=${lyricsLine.startTime} endMs=${lyricsLine.endTime} " +
                "textLength=${lyricsLine.pureMainText.length} " +
                "hasTranslation=${lyricsLine.pureSubText != null} " +
                "cells=${lyricsLine.lyricsCells.size}"
        }
        session()?.accept("onLyricsLineUpdated", detail)
    }

    override fun onLyricsLinesUpdated(lyricsLines: List<LyricsLine>) {
        val first = lyricsLines.firstOrNull()
        val last = lyricsLines.lastOrNull()
        val cellCount = lyricsLines.sumOf { it.lyricsCells.size }
        session()?.accept(
            "onLyricsLinesUpdated",
            "count=${lyricsLines.size} cells=$cellCount " +
                "firstStartMs=${first?.startTime} firstEndMs=${first?.endTime} " +
                "lastStartMs=${last?.startTime} " +
                "monotonic=${isMonotonic(lyricsLines)}"
        )
    }

    override fun onPositionUpdated(position: Long) {
        session()?.accept("onPositionUpdated", "positionMs=$position")
    }

    private fun logMedia(kind: String, mediaItem: MediaItem) {
        session()?.accept(
            kind,
            "id=${mediaItem.id.ifEmpty { "-" }} " +
                "title=${mediaItem.title} artist=${mediaItem.artist} " +
                "album=${mediaItem.album} duration=${mediaItem.duration}"
        )
    }

    private fun isMonotonic(lines: List<LyricsLine>): Boolean {
        var previous = Long.MIN_VALUE
        for (line in lines) {
            if (line.startTime < previous) return false
            previous = line.startTime
        }
        return true
    }
}
