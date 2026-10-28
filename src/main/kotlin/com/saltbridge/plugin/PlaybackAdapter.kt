package com.saltbridge.plugin

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.State
import org.pf4j.Extension

/**
 * 宿主回调 → 核心输入。
 *
 * 两条硬约束：
 * 1. **不阻塞**：只做类型转换与 `events` 入队，不调用任何宿主查询；
 * 2. **不改变宿主行为**：两个歌词加载回调都返回 null，让 SPW 走默认逻辑。
 *
 * 回调线程实测是分散的（AWT、`DefaultDispatcher-worker-N`、`Workshop-stop-*`），
 * 因此这里不做任何线程假设，全部交给核心的串行入口。
 */
@Extension
class PlaybackAdapter : PlaybackExtensionPoint {

    override fun onStateChanged(state: State) {
        CoreRegistry.current()?.onHostState(MediaMapper.toCoreState(state))
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        CoreRegistry.current()?.onIsPlaying(isPlaying)
    }

    override fun onSeekTo(position: Long) {
        CoreRegistry.current()?.onSeekTo(position)
    }

    override fun onBeforeLoadLyrics(mediaItem: MediaItem): String? {
        CoreRegistry.current()?.onLyricsLoadIntent(MediaMapper.toTrack(mediaItem))
        return null
    }

    override fun onAfterLoadLyrics(mediaItem: MediaItem): String? = null

    override fun onLyricsLineUpdated(lyricsLine: LyricsLine?) {
        // M4 不消费当前行：协议只暴露完整时间轴与位置，避免多一条状态来源。
    }

    override fun onLyricsLinesUpdated(lyricsLines: List<LyricsLine>) {
        CoreRegistry.current()?.onLyricsPublished(MediaMapper.toLyrics(lyricsLines))
    }

    override fun onPositionUpdated(position: Long) {
        CoreRegistry.current()?.onPosition(position)
    }
}
