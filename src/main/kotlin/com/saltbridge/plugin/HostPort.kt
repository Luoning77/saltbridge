package com.saltbridge.plugin

import com.saltbridge.core.LyricLine
import com.saltbridge.core.TrackInfo
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.util.concurrent.TimeUnit

/** 一次轮询的结果。异常在适配层被翻译成明确的分支，核心不需要认识宿主异常类型。 */
sealed interface PollOutcome {
    data class Available(val track: TrackInfo?) : PollOutcome
    data object PermissionDenied : PollOutcome
    data class Failed(val type: String) : PollOutcome
}

/**
 * 宿主能力的最小抽象。
 *
 * 只有这里知道 `WorkshopApi` 是全局单例；[BridgeCore] 通过接口访问，
 * 因此可以在没有宿主的 JVM 里用假实现跑线程与调度测试。
 */
interface HostPort {
    fun libraryTracks(afterId: String?, limit: Int): List<com.saltbridge.http.LibraryTrack> =
        throw UnsupportedOperationException("Library listing unavailable")

    /** 阻塞调用，只能在轮询线程执行。 */
    fun poll(): PollOutcome

    /** 读取当前解析结果，只能在 IO 线程执行；失败时抛异常。 */
    fun lyricsLines(): List<LyricLine>

    /** 读取内嵌封面；只在封面线程调用，阻塞等待宿主完成。 */
    fun cover(trackId: String): ByteArray?

    /** 同步权限查询；实现方负责缓存，HTTP 线程不应频繁打宿主。 */
    fun isLibraryReadGranted(): Boolean

    /** 继续播放；异常直接抛给调用方处理。 */
    fun play()

    /** 暂停播放。 */
    fun pause()

    /** 下一首。 */
    fun next()

    /** 上一首。 */
    fun previous()

    /** 跳转到指定位置，单位毫秒。 */
    fun seekTo(positionMs: Long)
}

class WorkshopHostPort : HostPort {
    override fun libraryTracks(afterId: String?, limit: Int): List<com.saltbridge.http.LibraryTrack> =
        WorkshopApi.library.getTracks(afterId, limit).toCompletableFuture().get(3_000, TimeUnit.MILLISECONDS).map {
            com.saltbridge.http.LibraryTrack(it.id, it.title.take(1024), it.artist.take(1024), it.album.take(1024), it.duration.takeIf { d -> d > 0 })
        }


    override fun poll(): PollOutcome = try {
        PollOutcome.Available(
            WorkshopApi.playback.getCurrentMediaItem()?.let(MediaMapper::toTrack)
        )
    } catch (denied: PluginPermissionDeniedException) {
        PollOutcome.PermissionDenied
    } catch (t: Throwable) {
        PollOutcome.Failed(t.javaClass.simpleName)
    }

    override fun lyricsLines(): List<LyricLine> =
        MediaMapper.toLyrics(WorkshopApi.playback.getLyricsLines())

    override fun cover(trackId: String): ByteArray? = runCatching {
        WorkshopApi.library.getCoverById(trackId)
            .toCompletableFuture()
            .get(COVER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }.getOrNull()

    override fun isLibraryReadGranted(): Boolean = runCatching {
        WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)
    }.getOrDefault(false)

    override fun play() {
        WorkshopApi.playback.play()
    }

    override fun pause() {
        WorkshopApi.playback.pause()
    }

    override fun next() {
        WorkshopApi.playback.next()
    }

    override fun previous() {
        WorkshopApi.playback.previous()
    }

    override fun seekTo(positionMs: Long) {
        WorkshopApi.playback.seekTo(positionMs)
    }

    private companion object {
        const val COVER_TIMEOUT_MS = 3_000L
    }
}
