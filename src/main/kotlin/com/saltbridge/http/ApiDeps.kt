package com.saltbridge.http

import com.saltbridge.core.Clock
import com.saltbridge.core.LyricsRead
import com.saltbridge.core.Snapshot

/**
 * Router 需要的一切都从这里取，不直接碰 `BridgeCore` 或宿主 API。
 *
 * 这样 HTTP 层可以完全用假实现测试，包括端口、令牌与权限状态。
 */
interface ApiDeps {
    val config: HttpConfig
    val clock: Clock

    /** 实际绑定端口；`Host` 校验要用它，不能用配置值（测试会用随机端口）。 */
    fun boundPort(): Int

    fun isReady(): Boolean

    fun instanceId(): String
    fun pluginId(): String
    fun pluginVersion(): String
    fun hostVersion(): String
    fun hostChannel(): String
    fun compiledApiVersion(): String

    /** 当前快照；服务正在停止时返回 null。 */
    fun snapshot(): Snapshot?

    /** 一次读取同一状态的曲目快照和歌词正文。 */
    fun lyrics(): LyricsRead?

    fun libraryReadGranted(): Boolean

    fun cover(generation: Long, trackId: String): CoverResult

    fun librarySupported(): Boolean = false

    fun libraryTracks(afterId: String?, limit: Int): LibraryResult = LibraryResult.Failed(false)

    fun token(): String

    /**
     * 控制入队。实现必须原子完成「查重 → 实例/代次校验 → 记录 → 入队」，
     * 并在入队失败时回滚记录（协议 5.6 ③④⑤、7.2）。
     */
    fun admitControl(request: ControlRequest): ControlAdmission

    /** M6 起为 true；`/capabilities.features.control` 用它。 */
    fun controlSupported(): Boolean
}
