package com.saltbridge.core

/**
 * 歌词异步令牌。
 *
 * **失效标识只有 `(generation, staleToken)`**：曲目字段补全会让内部的
 * `trackRevision` 变化，但歌词过期周期与元数据无关，不能被它判废。
 */
data class LyricsQuery(val generation: Long, val staleToken: Long)

/**
 * 歌词状态机：归属判定、版本、stale 过期周期与回查。
 *
 * 关键规则（协议 4.6）：
 * 1. 没有当前代次的加载意图时，发布记为 `UNCONFIRMED`，**正文不对外**；
 * 2. 已有时间轴后收到空发布 → `STALE`，固定 `staleUntilMs`，不因重复空事件延长；
 * 3. 回查非空立即恢复 `AVAILABLE`，即使内容与缓存完全一致；
 * 4. 到期仍为空 → `UNAVAILABLE` 并清空；旧令牌的定时器不得改回状态。
 */
class LyricsState(
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val config: EngineConfig,
    private val requestRecheck: (LyricsQuery) -> Unit = {},
) {

    var status: LyricsStatus = LyricsStatus.UNKNOWN
        private set
    var association: Association = Association.NONE
        private set
    var lines: List<LyricLine> = emptyList()
        private set
    var version: Long = 0
        private set
    var staleSinceMs: Long? = null
        private set
    var staleUntilMs: Long? = null
        private set
    var timingSanity: TimingSanity? = null
        private set
    var updatedAtMs: Long? = null
        private set

    private var staleToken: Long = 0
    private var ownerGeneration: Long = 0
    private var loadIntentGeneration: Long = -1

    /** 切歌/无曲目时调用：内容与过期周期一起作废，`version` 保持实例内单调。 */
    fun reset(generation: Long) {
        status = LyricsStatus.UNKNOWN
        association = Association.NONE
        lines = emptyList()
        staleSinceMs = null
        staleUntilMs = null
        timingSanity = null
        loadIntentGeneration = -1
        ownerGeneration = generation
        staleToken += 1
    }

    fun onLoadIntent(generation: Long) {
        if (generation != ownerGeneration) return
        loadIntentGeneration = generation
    }

    /**
     * `onLyricsLinesUpdated`。
     *
     * @param trackAvailable 没有曲目时不建立版本（协议 4.6）
     */
    fun onPublished(generation: Long, newLines: List<LyricLine>, trackAvailable: Boolean) {
        if (!trackAvailable || generation == 0L) return
        if (generation != ownerGeneration) return

        if (loadIntentGeneration != generation) {
            association = Association.UNCONFIRMED
            status = LyricsStatus.UNKNOWN
            return
        }

        association = Association.ASSOCIATED
        accept(newLines)
    }

    fun currentQuery(): LyricsQuery? =
        if (ownerGeneration == 0L) null else LyricsQuery(ownerGeneration, staleToken)

    /** 回填只按代次失效：字段补全不得取消回填。 */
    fun completeBackfill(query: LyricsQuery, newLines: List<LyricLine>) {
        if (query.generation != ownerGeneration) return

        if (newLines.isEmpty()) {
            // 空回填不建立关联：它只能说明“当前读不到时间轴”，
            // 而且不能让已有内容因为一次空读就进入 stale。
            if (status == LyricsStatus.AVAILABLE || status == LyricsStatus.STALE) return
            accept(newLines)
            return
        }

        if (status == LyricsStatus.AVAILABLE && newLines == lines) return
        association = Association.ASSOCIATED
        loadIntentGeneration = ownerGeneration
        accept(newLines)
    }

    /** 回查结果：读取完成后**再次**校验令牌（协议 4.6 的两段式校验）。 */
    fun completeRecheck(query: LyricsQuery, newLines: List<LyricLine>) {
        if (!isCurrent(query)) return
        if (status != LyricsStatus.STALE) return

        if (newLines.isNotEmpty()) {
            accept(newLines)
            return
        }

        val until = staleUntilMs ?: return
        if (clock.nowMs() >= until) becomeUnavailable()
    }

    fun toSnapshot(): LyricsSnapshot = LyricsSnapshot(
        status = status,
        association = association,
        version = version,
        lineCount = lines.size,
        staleSinceMs = staleSinceMs,
        staleUntilMs = staleUntilMs,
        timingSanity = timingSanity,
        updatedAtMs = updatedAtMs,
    )

    // --- 内部 ---

    private fun isCurrent(query: LyricsQuery): Boolean =
        query.generation == ownerGeneration && query.staleToken == staleToken

    private fun accept(newLines: List<LyricLine>) {
        version += 1
        updatedAtMs = clock.nowMs()

        if (newLines.isNotEmpty()) {
            status = LyricsStatus.AVAILABLE
            staleSinceMs = null
            staleUntilMs = null
            timingSanity = sanityOf(newLines)
            lines = newLines
            staleToken += 1 // 让本周期内所有未执行的回查/到期任务失效
            return
        }

        when (status) {
            LyricsStatus.AVAILABLE -> startStalePeriod()
            // 重复空发布：只递增版本，不清除、不延期、不重排任务
            LyricsStatus.STALE -> Unit
            else -> becomeUnavailable()
        }
    }

    private fun startStalePeriod() {
        val now = clock.nowMs()
        status = LyricsStatus.STALE
        staleSinceMs = now
        val until = now + config.lyricsStaleGraceMs
        staleUntilMs = until
        staleToken += 1
        val token = staleToken
        val generation = ownerGeneration

        config.lyricsRecheckDelaysMs.forEach { delay ->
            scheduler.scheduleAt(now + delay) {
                if (isCurrent(LyricsQuery(generation, token)) && status == LyricsStatus.STALE) {
                    requestRecheck(LyricsQuery(generation, token))
                }
            }
        }

        scheduler.scheduleAt(until) { onDeadline(LyricsQuery(generation, token)) }
    }

    /** 到期任务：在截止时刻再读一次；结果为空时由 [completeRecheck] 转 `UNAVAILABLE`。 */
    private fun onDeadline(query: LyricsQuery) {
        if (!isCurrent(query)) return
        if (status != LyricsStatus.STALE) return
        val until = staleUntilMs ?: return
        if (clock.nowMs() < until) return
        requestRecheck(query)
    }

    private fun becomeUnavailable() {
        status = LyricsStatus.UNAVAILABLE
        lines = emptyList()
        staleSinceMs = null
        staleUntilMs = null
        timingSanity = null
        staleToken += 1
    }

    private fun sanityOf(candidate: List<LyricLine>): TimingSanity {
        if (candidate.isEmpty()) return TimingSanity.OK
        var suspect = false
        var previousStart = Long.MIN_VALUE
        for (line in candidate) {
            if (line.startMs < 0 || line.endMs < line.startMs) suspect = true
            if (line.startMs < previousStart) suspect = true
            previousStart = line.startMs
            for (cell in line.cells) {
                if (cell.startMs < 0 || cell.endMs < cell.startMs) suspect = true
            }
        }
        return if (suspect) TimingSanity.SUSPECT else TimingSanity.OK
    }
}
