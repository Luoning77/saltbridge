package com.saltbridge.http

import com.saltbridge.core.Clock
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** 不包含本地路径；游标使用宿主曲库 ID。 */
data class LibraryTrack(val id: String, val title: String, val artist: String, val album: String, val durationMs: Long?)
data class LibraryPage(val tracks: List<LibraryTrack>, val nextCursor: String?)
sealed interface LibraryResult {
    data object Pending : LibraryResult
    data class Ready(val page: LibraryPage) : LibraryResult
    data class Failed(val forbidden: Boolean) : LibraryResult
}

/** 有界、短时缓存；HTTP 线程不等待曲库查询。 */
class LibraryService(private val clock: Clock, private val executor: Executor,
                     private val loader: (String?, Int) -> List<LibraryTrack>) {
    private data class Key(val afterId: String?, val limit: Int)
    private data class Entry(val at: Long, val result: LibraryResult)
    private val lock = Any()
    private val cache = LinkedHashMap<Key, Entry>()
    private val pending = HashSet<Key>()
    private var epoch = 0L

    fun get(afterId: String?, limit: Int): LibraryResult {
        val key = Key(afterId, limit)
        val ticket: Long
        synchronized(lock) {
            cache.entries.removeIf { clock.nowMs() - it.value.at >= 10_000 }
            cache[key]?.let { return it.result }
            if (key in pending || pending.size >= 4) return LibraryResult.Pending
            pending.add(key)
            ticket = epoch
        }
        try {
            executor.execute {
                if (synchronized(lock) { ticket != epoch }) return@execute
                val result = try {
                    val tracks = loader(afterId, limit + 1)
                    val page = tracks.take(limit)
                    LibraryResult.Ready(LibraryPage(page, if (tracks.size > limit) page.lastOrNull()?.id else null))
                } catch (e: Exception) {
                    var cause: Throwable? = e
                    while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
                    LibraryResult.Failed(cause is com.xuncorp.spw.workshop.api.PluginPermissionDeniedException)
                }
                synchronized(lock) {
                    if (ticket != epoch) return@synchronized
                    pending.remove(key)
                    cache[key] = Entry(clock.nowMs(), result)
                    while (cache.size > 8) cache.remove(cache.keys.first())
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { if (ticket == epoch) pending.remove(key) }
        }
        return LibraryResult.Pending
    }

    fun clear() = synchronized(lock) { epoch++; cache.clear(); pending.clear() }
}
