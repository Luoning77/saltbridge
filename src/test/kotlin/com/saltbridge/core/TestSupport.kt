package com.saltbridge.core

/** 可控时钟：所有断言都基于它，禁止真实时间。 */
class FakeClock(private var current: Long = 0) : Clock {
    override fun nowMs(): Long = current
    fun set(ms: Long) {
        current = ms
    }

    fun advanceBy(delta: Long) {
        current += delta
    }
}

/** 手动推进的调度器：按 `atMs` 排序执行，测试完全掌控时间。 */
class FakeScheduler(private val clock: FakeClock) : Scheduler {

    private data class Task(val atMs: Long, val order: Long, val action: () -> Unit)

    private val tasks = mutableListOf<Task>()
    private var order = 0L

    override fun scheduleAt(atMs: Long, action: () -> Unit): ScheduledHandle {
        val task = Task(atMs, order++, action)
        tasks.add(task)
        return object : ScheduledHandle {
            override fun cancel() {
                tasks.remove(task)
            }
        }
    }

    fun advanceTo(target: Long) {
        while (true) {
            val next = tasks
                .filter { it.atMs <= target }
                .minWithOrNull(compareBy({ it.atMs }, { it.order }))
                ?: break
            tasks.remove(next)
            if (next.atMs > clock.nowMs()) clock.set(next.atMs)
            next.action()
        }
        if (target > clock.nowMs()) clock.set(target)
    }

    fun advanceBy(delta: Long) = advanceTo(clock.nowMs() + delta)

    fun pendingCount(): Int = tasks.size

    fun pendingAt(): List<Long> = tasks.map { it.atMs }.sorted()
}

/** 测试用曲目工厂。 */
fun track(
    id: String? = "t-1",
    title: String = "晴天",
    artist: String = "周杰伦",
    album: String = "叶惠美",
    albumArtist: String = "周杰伦",
    durationMs: Long? = 269_000,
    pathHash: String = "hash-1",
): TrackInfo = TrackInfo(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    durationMs = durationMs,
    pathHash = pathHash,
)

fun lines(count: Int, stepMs: Long = 1_000): List<LyricLine> =
    (0 until count).map { index ->
        LyricLine(
            startMs = index * stepMs,
            endMs = (index + 1) * stepMs,
            text = "line-$index",
        )
    }

/**
 * 模拟客户端显示位置（协议 4.4）。
 *
 * 这是**被测语义的一部分**：核心不产生估算值，但客户端算法必须保证
 * “过期切换不回退”。测试里用它验证核心提供的 `animation` 足够支撑该规则。
 */
class DisplayedPosition(private val staleAfterMs: Long) {

    private var instanceId: String? = null
    private var generation: Long? = null
    var displayedMs: Long? = null
        private set

    fun update(snapshot: Snapshot, localReceivedAtMs: Long, localNowMs: Long): Long? {
        val position = snapshot.playback.position
        val reported = position.reportedMs
        val reportedAt = position.reportedAtMs
        val animation = snapshot.playback.animation

        val elapsed = if (reportedAt != null) {
            (snapshot.servedAtMs - reportedAt) + (localNowMs - localReceivedAtMs)
        } else {
            0L
        }

        val target = when {
            reported == null -> null
            animation.mode != AnimationMode.DISABLED ->
                reported + elapsed.coerceIn(0L, animation.maxMs)
            else -> reported
        }

        val sameContext =
            instanceId == snapshot.instanceId && generation == snapshot.generation
        if (!sameContext) {
            instanceId = snapshot.instanceId
            generation = snapshot.generation
            displayedMs = target
            return displayedMs
        }

        val previous = displayedMs
        displayedMs = when {
            target == null -> previous
            previous == null -> target
            target >= previous -> target
            reported != null && reported < previous - staleAfterMs -> reported
            else -> previous
        }
        return displayedMs
    }
}
