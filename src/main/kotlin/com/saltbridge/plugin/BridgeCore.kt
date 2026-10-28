package com.saltbridge.plugin

import com.saltbridge.core.Clock
import com.saltbridge.core.EngineConfig
import com.saltbridge.core.HostState
import com.saltbridge.core.Input
import com.saltbridge.core.LyricLine
import com.saltbridge.core.LyricsRead
import com.saltbridge.core.LyricsQuery
import com.saltbridge.core.ScheduledHandle
import com.saltbridge.core.Scheduler
import com.saltbridge.core.Snapshot
import com.saltbridge.core.StateEngine
import com.saltbridge.core.SystemClock
import com.saltbridge.core.TrackInfo
import com.saltbridge.core.TrackStatus
import com.saltbridge.http.ApiDeps
import com.saltbridge.http.ApiRouter
import com.saltbridge.http.BridgeHttpServer
import com.saltbridge.http.ControlAdmission
import com.saltbridge.http.ControlCommand
import com.saltbridge.http.ControlQueue
import com.saltbridge.http.ControlRequest
import com.saltbridge.http.CoverResult
import com.saltbridge.http.CoverService
import com.saltbridge.http.HttpConfig
import com.saltbridge.http.MemoryTokenStore
import com.saltbridge.http.QueuedControl
import com.saltbridge.http.TokenProvider
import com.saltbridge.http.TokenStore
import com.saltbridge.wire.SnapshotJson
import com.saltbridge.wire.StatusJson
import com.xuncorp.spw.workshop.api.PluginContext
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 一个启用周期内的运行时容器：状态引擎 + 四条线程 + 日志。
 *
 * 线程分工（与 `docs/ARCHITECTURE.md` 第 8 节一致）：
 * | 线程 | 职责 | 禁止 |
 * |---|---|---|
 * | `saltbridge-events` | 所有 `Input` 串行进入引擎、写日志 | 阻塞、调宿主查询 |
 * | `saltbridge-poll` | `getCurrentMediaItem()`，2 s 周期 | 触碰 HTTP |
 * | `saltbridge-io` | `getLyricsLines()` 回填与回查 | 改状态（必须回 events） |
 * | `saltbridge-timer` | 歌词 stale 定时 | 直接改状态（转发到 events） |
 *
 * 宿主回调线程只做 `events.execute`，不阻塞、不做 IO。
 */
class BridgeCore(
    private val context: PluginContext,
    val epoch: Long,
    dataDir: Path?,
    private val hostPort: HostPort = WorkshopHostPort(),
    private val clock: Clock = SystemClock,
    private val pollIntervalMs: Long = 2_000,
    tokenStore: TokenStore = MemoryTokenStore(),
    private val httpConfig: HttpConfig = HttpConfig(),
) {

    val instanceId: String = UUID.randomUUID().toString()

    private val alive = AtomicBoolean(false)
    private val droppedEvents = AtomicLong()
    private var lastSummary: String? = null

    val isActive: Boolean get() = alive.get()

    private val log = BridgeLog(dataDir?.resolve("logs")?.resolve("bridge.log"))

    private val events = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(1024),
        threadFactory("saltbridge-events"),
        RejectedExecutionHandler { _, _ -> droppedEvents.incrementAndGet() },
    )

    private val io = Executors.newSingleThreadExecutor(threadFactory("saltbridge-io"))
    private val poller = Executors.newSingleThreadScheduledExecutor(threadFactory("saltbridge-poll"))
    private val timer = Executors.newSingleThreadScheduledExecutor(threadFactory("saltbridge-timer"))

    /** 歌词定时任务必须回到 `events` 线程执行，保证与其它输入串行。 */
    private val engineScheduler = object : Scheduler {
        override fun scheduleAt(atMs: Long, action: () -> Unit): ScheduledHandle {
            val delay = (atMs - clock.nowMs()).coerceAtLeast(0L)
            val future = timer.schedule(
                {
                    if (alive.get()) {
                        runCatching { events.execute { if (alive.get()) action() } }
                    }
                },
                delay,
                TimeUnit.MILLISECONDS,
            )
            return object : ScheduledHandle {
                override fun cancel() {
                    future.cancel(false)
                }
            }
        }
    }

    private val engine = StateEngine(
        instanceId = instanceId,
        clock = clock,
        scheduler = engineScheduler,
        config = EngineConfig(),
        epoch = epoch,
        onBackfillRequested = { query -> requestLyrics(query, ::completeBackfill) },
        onRecheckRequested = { query -> requestLyrics(query, ::completeRecheck) },
    )

    private val tokenProvider = TokenProvider(tokenStore)

    private val bootstrap = Executors.newSingleThreadExecutor(threadFactory("saltbridge-bootstrap"))
    private val coverExecutor = Executors.newSingleThreadExecutor(threadFactory("saltbridge-cover"))
    private val coverService = CoverService(httpConfig, clock, coverExecutor, hostPort::cover)

    /** 控制命令串行队列：HTTP 线程只做校验与入队，绝不直接调宿主。 */
    private val controlQueue = ControlQueue(
        capacity = httpConfig.controlQueue,
        dedupeCapacity = httpConfig.dedupeCapacity,
        dedupeWindowMs = httpConfig.dedupeWindowMs,
        clock = clock,
    )
    private val controlExecutor = Executors.newSingleThreadExecutor(threadFactory("saltbridge-control"))

    @Volatile
    private var httpServer: BridgeHttpServer? = null

    /** 保护 [httpServer] 注册与 [stop] 清理的互斥，避免启停竞态。 */
    private val httpLock = Any()

    /** 测试钩子：在 `server.start()` 之后、注册之前调用，用于可控同步点。 */
    internal var onHttpStarting: (() -> Unit)? = null

    @Volatile
    private var permissionCheckedAtMs: Long? = null

    @Volatile
    private var permissionGranted: Boolean = false

    // --- 生命周期 ---

    fun start() {
        if (!alive.compareAndSet(false, true)) return
        log.line(
            "core.start",
            "instance=$instanceId epoch=$epoch plugin=${context.pluginId}@${context.pluginVersion} " +
                "spw=${context.spwVersion} channel=${context.spwChannel} " +
                "java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} " +
                "pollMs=$pollIntervalMs",
        )
        poller.scheduleWithFixedDelay(
            {
                if (!alive.get()) return@scheduleWithFixedDelay
                runCatching { pollOnce() }
                    .onFailure { log.line("poll.crash", it.javaClass.simpleName) }
            },
            0L,
            pollIntervalMs,
            TimeUnit.MILLISECONDS,
        )

        // 绑定端口放到后台：`start()` 在宿主 UI 线程执行，不能等 socket
        bootstrap.execute {
            if (!alive.get()) return@execute
            runCatching { tokenProvider.current() }
                .onFailure { log.line("token.error", it.javaClass.simpleName) }
            startHttp()
        }

        controlExecutor.execute { controlLoop() }
    }

    fun stop() {
        if (!alive.compareAndSet(true, false)) return
        log.line("core.stop", "instance=$instanceId")

        // 与 startHttp() 的注册互斥：确保刚启动的服务器也被停止
        synchronized(httpLock) {
            httpServer?.stop()
            httpServer = null
        }

        // 协议 7.1：停用插件会取消尚未执行的命令，202 不承诺一定执行
        controlQueue.clear()
        controlExecutor.shutdownNow()
        coverService.clear()
        bootstrap.shutdownNow()
        coverExecutor.shutdownNow()
        poller.shutdownNow()
        timer.shutdownNow()
        io.shutdownNow()
        events.shutdown()
        runCatching { events.awaitTermination(200, TimeUnit.MILLISECONDS) }
        events.shutdownNow()
        log.close()
    }

    fun onUpdate() {
        log.line("core.update", "instance=$instanceId")
    }

    fun onDelete() {
        log.line("core.delete", "instance=$instanceId")
    }

    // --- 宿主事件入口（可在任意线程被调用） ---

    fun onHostState(state: HostState) = dispatch("host.state", "state=$state") {
        engine.onInput(Input.HostStateChanged(state))
    }

    fun onIsPlaying(isPlaying: Boolean) = dispatch("host.isPlaying", "value=$isPlaying") {
        engine.onInput(Input.IsPlayingChanged(isPlaying))
    }

    fun onPosition(positionMs: Long) = dispatch("host.position", "ms=$positionMs") {
        engine.onInput(Input.PositionUpdated(positionMs))
    }

    fun onSeekTo(positionMs: Long) = dispatch("host.seek", "ms=$positionMs") {
        engine.onInput(Input.SeekTo(positionMs))
    }

    fun onLyricsLoadIntent(track: TrackInfo) = dispatch(
        "host.lyricsIntent",
        "id=${track.id ?: "-"} title=${track.title}",
    ) {
        engine.onInput(Input.LyricsLoadIntent(track))
    }

    fun onLyricsPublished(lines: List<LyricLine>) = dispatch(
        "host.lyrics",
        "lines=${lines.size}",
    ) {
        engine.onInput(Input.LyricsPublished(lines))
    }

    // --- 对外 ---

    fun snapshot(): Snapshot? = if (alive.get()) engine.snapshot() else null

    fun snapshotJson(): StatusJson? = snapshot()?.let { SnapshotJson.of(it, clock.nowMs()) }

    fun httpPort(): Int = httpServer?.boundPort() ?: 0

    fun httpStarted(): Boolean = httpServer != null

    fun token(): String = tokenProvider.current()

    fun resetToken(): String = tokenProvider.reset().also { log.line("token.reset", "") }

    fun logMarker() {
        log.line("marker", "")
    }

    // --- 轮询 ---

    private fun pollOnce() {
        val token = engine.nextPollToken()
        when (val outcome = hostPort.poll()) {
            is PollOutcome.Available -> submit {
                engine.onInput(Input.PollResult(token, outcome.track))
                publish()
            }

            PollOutcome.PermissionDenied -> submit {
                engine.onInput(Input.PollPermissionDenied(token))
                publish()
            }

            is PollOutcome.Failed -> log.line("poll.failed", outcome.type)
        }
    }

    // --- 歌词异步 ---

    /**
     * 回填与回查共用一条 IO 路径：读宿主 → 回 `events` 线程 → 用**读取前的令牌**提交结果。
     * 令牌校验在核心内部完成，这里不重复判断新鲜度。
     */
    private fun requestLyrics(query: LyricsQuery, complete: (LyricsQuery, List<LyricLine>) -> Unit) {
        io.execute {
            if (!alive.get()) return@execute
            val lines = runCatching { hostPort.lyricsLines() }
                .onFailure { log.line("lyrics.readFailed", it.javaClass.simpleName) }
                .getOrNull() ?: return@execute
            submit {
                complete(query, lines)
                publish()
            }
        }
    }

    private fun completeBackfill(query: LyricsQuery, lines: List<LyricLine>) {
        engine.completeLyricsBackfill(query, lines)
    }

    private fun completeRecheck(query: LyricsQuery, lines: List<LyricLine>) {
        engine.completeLyricsRecheck(query, lines)
    }

    // --- 内部 ---

    /**
     * 控制执行循环：单线程串行，取出即执行，不并发。
     * `stop()` 用 `shutdownNow()` 中断它，并清空未执行命令。
     */
    private fun controlLoop() {
        while (alive.get()) {
            val command = runCatching { controlQueue.take(200) }.getOrNull() ?: continue
            executeControl(command)
        }
    }

    private fun executeControl(command: QueuedControl) {
        if (!alive.get()) return

        // 执行前复查代次（协议 5.6 ⑥）：把“作用到错曲目”的窗口再缩小一次
        val expected = command.expectedGeneration
        if (expected != null && expected != engine.currentGeneration) {
            log.line("control.dropped", "requestId=${command.requestId} reason=generation_changed")
            return
        }

        runCatching {
            when (command.command) {
                ControlCommand.PLAY -> hostPort.play()
                ControlCommand.PAUSE -> hostPort.pause()
                ControlCommand.NEXT -> hostPort.next()
                ControlCommand.PREVIOUS -> hostPort.previous()
                ControlCommand.SEEK -> command.positionMs?.let { hostPort.seekTo(it) }
            }
        }.onFailure {
            log.line("control.error", "requestId=${command.requestId} type=${it.javaClass.simpleName}")
        }.onSuccess {
            log.line("control.exec", "requestId=${command.requestId} cmd=${command.command.wireName}")
        }
    }

    /** 协议 5.6 ③④⑤ 全部在 [ControlQueue] 的一把锁内完成。 */
    private fun admitControl(request: ControlRequest): ControlAdmission {
        if (!alive.get()) {
            return ControlAdmission.Rejected(
                status = 503,
                code = "service_unavailable",
                message = "Service is stopping",
                detail = mapOf("reason" to "stopping"),
            )
        }
        val snapshot = engine.snapshot()
        return controlQueue.admit(
            request = request,
            currentInstanceId = instanceId,
            currentGeneration = snapshot.generation,
            trackAvailable = snapshot.trackStatus == TrackStatus.AVAILABLE,
            trackDurationMs = snapshot.track?.durationMs,
        )
    }

    private fun startHttp() {
        if (!alive.get()) return
        val server = BridgeHttpServer(httpConfig, ApiRouter(createDeps()))
        val started = server.start()

        // 测试钩子：可控同步点，让测试能在注册前插入 stop()
        // 钩子可能被 stop() 的 shutdownNow() 中断，必须仍走到清理分支
        try {
            onHttpStarting?.invoke()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            // 钩子异常不阻断清理
        }

        synchronized(httpLock) {
            if (!alive.get()) {
                // 清除中断标志：NanoHTTPD.stop() 内部 Thread.join() 被中断会打日志
                Thread.interrupted()
                server.stop()
                return
            }
            if (started) {
                httpServer = server
                log.line("http.start", "http://${httpConfig.host}:${server.boundPort()}/api/v1/health")
            } else {
                log.line("http.error", "bind failed on ${httpConfig.host}:${httpConfig.port}")
            }
        }
    }

    private fun createDeps(): ApiDeps = object : ApiDeps {
        override val config: HttpConfig get() = httpConfig
        override val clock: Clock get() = this@BridgeCore.clock

        override fun boundPort(): Int = httpServer?.boundPort() ?: httpConfig.port

        override fun isReady(): Boolean = alive.get()

        override fun instanceId(): String = this@BridgeCore.instanceId

        override fun pluginId(): String = context.pluginId

        override fun pluginVersion(): String = context.pluginVersion

        override fun hostVersion(): String = context.spwVersion

        override fun hostChannel(): String = context.spwChannel.name

        override fun compiledApiVersion(): String = COMPILED_API_VERSION

        override fun snapshot(): Snapshot? = this@BridgeCore.snapshot()

        override fun lyrics(): LyricsRead? = if (alive.get()) engine.lyricsRead() else null

        override fun libraryReadGranted(): Boolean = this@BridgeCore.libraryReadGranted()

        override fun cover(generation: Long, trackId: String): CoverResult =
            coverService.get(generation, trackId)

        override fun token(): String = tokenProvider.current()

        override fun admitControl(request: ControlRequest): ControlAdmission =
            this@BridgeCore.admitControl(request)

        override fun controlSupported(): Boolean = alive.get()
    }

    /** 权限查询缓存 2 s：`/capabilities` 与 `/cover` 高频调用，不能每次都打宿主。 */
    private fun libraryReadGranted(): Boolean {
        val now = clock.nowMs()
        val checkedAt = permissionCheckedAtMs
        // null 表示尚未查询过；不用哨兵值做减法避免溢出
        if (checkedAt != null && now - checkedAt < PERMISSION_CACHE_MS) return permissionGranted
        permissionGranted = runCatching { hostPort.isLibraryReadGranted() }.getOrDefault(false)
        permissionCheckedAtMs = now
        return permissionGranted
    }

    private fun dispatch(kind: String, detail: String = "", block: () -> Unit) {
        if (!alive.get()) return
        submit {
            log.line(kind, detail)
            block()
            publish()
        }
    }

    private inline fun submit(crossinline block: () -> Unit) {
        runCatching {
            events.execute {
                if (alive.get()) block()
            }
        }
    }

    private fun publish() {
        val snapshot = engine.snapshot()
        val summary = summaryOf(snapshot)
        if (summary == lastSummary) return
        lastSummary = summary
        log.line("state", summary)

        val dropped = droppedEvents.getAndSet(0)
        if (dropped > 0) log.line("events.dropped", "count=$dropped")
    }

    /** 摘要**不含** `seq` 与位置值：位置每秒变化不应把日志刷满。 */
    private fun summaryOf(s: Snapshot): String = buildString {
        append("gen=").append(s.generation)
        append(" track=").append(s.trackStatus.name)
        append('/').append(s.track?.title ?: "-")
        append(" state=").append(s.playback.state.name)
        append(" playing=").append(s.playback.isPlaying?.toString() ?: "null")
        append(" act=").append(s.playback.activity.name)
        append(" anim=").append(s.playback.animation.mode.name)
        append('/').append(s.playback.animation.reason.name)
        append(" lyr=").append(s.lyrics.status.name)
        append(" v").append(s.lyrics.version)
        append(" n").append(s.lyrics.lineCount)
        append(" assoc=").append(s.lyrics.association.name)
    }

    private fun threadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    private companion object {
        const val COMPILED_API_VERSION = "0.1.0-dev23"
        const val PERMISSION_CACHE_MS = 2_000L
    }
}
