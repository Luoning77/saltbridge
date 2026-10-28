package com.saltbridge.core

/** 曲目身份键：`id` 优先，缺失时退回元数据哈希。 */
data class TrackKey(val id: String?, val metaHash: Long) {

    /**
     * 双方都有 `id` 时只比 `id`；任一方没有 `id` 时比元数据哈希。
     *
     * 这样“回调没 ID、轮询有 ID”不会被误判成切歌，也不会因为补全 `id` 而多增代次。
     */
    fun matches(other: TrackKey): Boolean =
        if (id != null && other.id != null) id == other.id else metaHash == other.metaHash

    /** 同曲补全：保留已知 `id`，元数据哈希以新的为准。 */
    fun mergedWith(other: TrackKey): TrackKey =
        TrackKey(id = id ?: other.id, metaHash = other.metaHash)
}

object TrackIdentity {

    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L

    fun keyOf(track: TrackInfo): TrackKey =
        TrackKey(id = track.id?.takeIf { it.isNotBlank() }, metaHash = metaHash(track))

    /** 与协议 4.5 一致：`title + artist + album + pathHash`，真实路径不参与。 */
    fun metaHash(track: TrackInfo): Long {
        val joined = buildString {
            append(track.title).append('\u0001')
            append(track.artist).append('\u0001')
            append(track.album).append('\u0001')
            append(track.pathHash)
        }
        return fnv1a(joined)
    }

    private fun fnv1a(value: String): Long {
        var hash = FNV_OFFSET
        for (ch in value) {
            hash = hash xor ch.code.toLong()
            hash *= FNV_PRIME
        }
        return hash
    }

    /**
     * 同曲字段合并：**只补全，不降级**。
     *
     * 空串与 `null` 视为“这次没提供”，保留旧值；非空值覆盖旧值（宿主可能修正元数据）。
     */
    fun merge(old: TrackInfo?, new: TrackInfo): TrackInfo {
        if (old == null) return new
        return TrackInfo(
            id = old.id ?: new.id,
            title = new.title.ifEmpty { old.title },
            artist = new.artist.ifEmpty { old.artist },
            album = new.album.ifEmpty { old.album },
            albumArtist = new.albumArtist.ifEmpty { old.albumArtist },
            durationMs = new.durationMs ?: old.durationMs,
            pathHash = new.pathHash.ifEmpty { old.pathHash },
        )
    }
}
