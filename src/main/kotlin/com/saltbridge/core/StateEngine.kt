package com.saltbridge.core

/**
 * 唯一写入口：所有宿主事件、轮询结果、定时 tick 都从这里进入，
 * 对外只输出不可变 [Snapshot]。
 *
 * 单线程语义由内部锁保证；宿主回调线程实测是分散的（AWT + 协程 worker），
 * 所以锁不是保险措施，而是必需项。
 */
class StateEngine(
    val instanceId: String,
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val config: EngineConfig = EngineConfig(),
    val epoch: Long = 1L,
    private val onBackfillRequested: (LyricsQuery) -> Unit = {},
    private val onRecheckRequested: (LyricsQuery) -> Unit = {},
) {

    private val lock = Any()
    private val activity = ActivityTracker(config)
    private val lyrics = LyricsState(clock, scheduler, config, onRecheckRequested)

    private var seq: Long = 0
    private var generation: Long = 0
    private var trackKey: TrackKey? = null
    private var track: TrackInfo? = null
    private var trackStatus: TrackStatus = TrackStatus.UNKNOWN
    private var trackRevision: Long = 0
    private var noTrackSinceMs: Long? = null

    private var pollSeq: Long = 0
    private var lastAppliedPollSeq: Long = 0

    private var hostState: HostState? = null
    private var isPlaying: Boolean? = null
    private var lastEventAtMs: Long? = null

    private var positionMs: Long? = null
    private var positionAtMs: Long? = null
    private var positionSource: PositionSource = PositionSource.NONE

    private var lastActivity: Activity = Activity.UNKNOWN

    val currentGeneration: Long get() = synchronized(lock) { generation }
    val currentTrackRevision: Long get() = synchronized(lock) { trackRevision }
    val currentTrackKey: TrackKey? get() = synchronized(lock) { trackKey }

    fun onInput(input: Input) {
        when (input) {
            is Input.HostStateChanged -> onHostStateChanged(input.state)
            is Input.IsPlayingChanged -> onIsPlayingChanged(input.isPlaying)
            is Input.SeekTo -> onSeekTo(input.positionMs)
            is Input.PositionUpdated -> onPositionUpdated(input.positionMs)
            is Input.LyricsLoadIntent -> onLyricsLoadIntent(input.track)
            is Input.LyricsPublished -> onLyricsPublished(input.lines)
            is Input.PollResult -> onPollResult(input.token, input.track)
            is Input.PollPermissionDenied -> onPollPermissionDenied(input.token)
            Input.Tick -> onTick()
        }
    }

    // --- 播放事件 ---

    fun onHostStateChanged(state: HostState) {
        synchronized(lock) {
            val now = clock.nowMs()
            hostState = state
            lastEventAtMs = now
            when (state) {
                HostState.BUFFERING, HostState.ENDED, HostState.IDLE -> activity.reset()
                HostState.READY -> Unit
            }
            bump()
        }
    }

    fun onIsPlayingChanged(isPlaying: Boolean) {
        synchronized(lock) {
            this.isPlaying = isPlaying
            lastEventAtMs = clock.nowMs()
            if (!isPlaying) activity.reset()
            bump()
        }
    }

    fun onPositionUpdated(positionMs: Long) {
        synchronized(lock) {
            val now = clock.nowMs()
            this.positionMs = positionMs
            this.positionAtMs = now
            this.positionSource = PositionSource.EVENT
            this.lastEventAtMs = now
            activity.onSample(PositionSample(positionMs, now))
            bump()
        }
    }

    /** seek 会跳变，绝不能当作“正常前进”；直接撤销活动推断。 */
    fun onSeekTo(positionMs: Long) {
        synchronized(lock) {
            val now = clock.nowMs()
            this.positionMs = positionMs
            this.positionAtMs = now
            this.positionSource = PositionSource.EVENT
            this.lastEventAtMs = now
            activity.reset()
            bump()
        }
    }

    // --- 歌词事件 ---

    fun onLyricsLoadIntent(item: TrackInfo) {
        synchronized(lock) {
            lastEventAtMs = clock.nowMs()
            adopt(item)
            lyrics.onLoadIntent(generation)
            bump()
        }
    }

    fun onLyricsPublished(lines: List<LyricLine>) {
        synchronized(lock) {
            lyrics.onPublished(generation, lines, trackStatus == TrackStatus.AVAILABLE)
            bump()
        }
    }

    fun completeLyricsRecheck(query: LyricsQuery, lines: List<LyricLine>) {
        synchronized(lock) {
            lyrics.completeRecheck(query, lines)
            bump()
        }
    }

    fun completeLyricsBackfill(query: LyricsQuery, lines: List<LyricLine>) {
        synchronized(lock) {
            lyrics.completeBackfill(query, lines)
            bump()
        }
    }

    // --- 轮询 ---

    /** 提交轮询前取令牌；`pollSeq` 在提交时递增。 */
    fun nextPollToken(): PollToken = synchronized(lock) {
        pollSeq += 1
        PollToken(
            instanceId = instanceId,
            epoch = epoch,
            pollSeq = pollSeq,
            generationAtSubmit = generation,
        )
    }

    fun onPollResult(token: PollToken, item: TrackInfo?) {
        synchronized(lock) {
            if (!acceptToken(token)) return
            val now = clock.nowMs()

            if (item != null) {
                noTrackSinceMs = null
                adopt(item)
            } else {
                val since = noTrackSinceMs ?: now.also { noTrackSinceMs = it }
                if (now - since >= config.noTrackDebounceMs && trackStatus != TrackStatus.NONE) {
                    trackStatus = TrackStatus.NONE
                    track = null
                    // trackKey 保留：同一首歌回来时 generation 不变（协议 4.5）
                    clearPlayback()
                }
            }
            bump()
        }
    }

    fun onPollPermissionDenied(token: PollToken) {
        synchronized(lock) {
            if (!acceptToken(token)) return
            if (track == null) {
                trackStatus = TrackStatus.PERMISSION_DENIED
                bump()
            }
        }
    }

    // --- 定时 ---

    fun onTick() {
        synchronized(lock) {
            val computed = activity.compute(clock.nowMs())
            if (computed != lastActivity) {
                lastActivity = computed
                bump()
            }
        }
    }

    // --- 快照 ---

    fun snapshot(): Snapshot = synchronized(lock) {
        val now = clock.nowMs()
        val currentActivity = activity.compute(now)
        if (currentActivity != lastActivity) {
            // 位置停更会让 activity 衰减，这同样是状态变化，必须体现在 seq 上
            lastActivity = currentActivity
            seq += 1
        }

        val position = Position(
            reportedMs = positionMs,
            reportedAtMs = positionAtMs,
            source = positionSource,
        )
        val animation = AnimationPolicy.evaluate(
            isPlaying = isPlaying,
            hostState = hostState,
            position = position,
            activity = currentActivity,
            nowMs = now,
            config = config,
        )

        Snapshot(
            instanceId = instanceId,
            seq = seq,
            servedAtMs = now,
            generation = generation,
            trackStatus = trackStatus,
            track = track,
            playback = Playback(
                state = deriveState(hostState, isPlaying),
                isPlaying = isPlaying,
                position = position,
                activity = currentActivity,
                activityUpdatedAtMs = activity.lastSampleAtMs(),
                animation = animation,
                lastEventAtMs = lastEventAtMs,
            ),
            lyrics = lyrics.toSnapshot(),
        )
    }

    fun lyricsRead(): LyricsRead = synchronized(lock) {
        LyricsRead(snapshot(), lyricsPayload())
    }

    fun lyricsQuery(): LyricsQuery? = synchronized(lock) { lyrics.currentQuery() }

    /** `/lyrics` 专用：正文与摘要必须来自同一份状态，不能分两次读。 */
    fun lyricsPayload(): LyricsPayload = synchronized(lock) {
        val summary = lyrics.toSnapshot()
        LyricsPayload(
            generation = generation,
            status = summary.status,
            association = summary.association,
            version = summary.version,
            staleSinceMs = summary.staleSinceMs,
            staleUntilMs = summary.staleUntilMs,
            timingSanity = summary.timingSanity,
            lines = lyrics.lines,
        )
    }

    // --- 内部 ---

    private fun bump() {
        seq += 1
    }

    private fun acceptToken(token: PollToken): Boolean {
        if (token.instanceId != instanceId || token.epoch != epoch) return false
        if (token.pollSeq <= lastAppliedPollSeq) return false
        if (token.generationAtSubmit != generation) return false
        lastAppliedPollSeq = token.pollSeq
        return true
    }

    /**
     * 统一 adopt 路径：切歌整体替换身份，同曲只做字段补全。
     *
     * 切歌分支**不允许**写成 `merge(currentKey, key)`——那会让新曲目继承旧 `id`。
     */
    private fun adopt(item: TrackInfo) {
        val key = TrackIdentity.keyOf(item)
        val current = trackKey

        if (current == null || !current.matches(key)) {
            generation += 1
            trackKey = key
            track = item
            trackStatus = TrackStatus.AVAILABLE
            trackRevision += 1
            noTrackSinceMs = null
            clearPlayback()
            requestBackfill()
            return
        }

        val wasUnavailable = track == null || trackStatus == TrackStatus.NONE
        val mergedKey = current.mergedWith(key)
        val mergedTrack = TrackIdentity.merge(track, item)

        if (mergedKey != current || mergedTrack != track) {
            trackKey = mergedKey
            track = mergedTrack
            trackRevision += 1
        }
        trackStatus = TrackStatus.AVAILABLE
        noTrackSinceMs = null

        if (wasUnavailable) {
            // 无曲目 → 同一首歌恢复：必须恢复 AVAILABLE 并重新安排回填
            clearPlayback()
            requestBackfill()
        }
    }

    private fun clearPlayback() {
        positionMs = null
        positionAtMs = null
        positionSource = PositionSource.NONE
        activity.reset()
        lyrics.reset(generation)
    }

    private fun requestBackfill() {
        val query = lyrics.currentQuery() ?: return
        onBackfillRequested(query)
    }

    private fun deriveState(hostState: HostState?, isPlaying: Boolean?): PlaybackState =
        when (hostState) {
            HostState.IDLE -> PlaybackState.STOPPED
            HostState.BUFFERING -> PlaybackState.BUFFERING
            HostState.ENDED -> PlaybackState.ENDED
            HostState.READY -> when (isPlaying) {
                true -> PlaybackState.PLAYING
                false -> PlaybackState.PAUSED
                null -> PlaybackState.UNKNOWN
            }
            null -> PlaybackState.UNKNOWN
        }
}
