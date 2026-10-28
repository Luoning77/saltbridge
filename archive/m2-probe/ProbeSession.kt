package com.saltbridge.probe

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class SessionInfo(
    val instanceId: String,
    val startedAt: String,
    val uptimeMs: Long,
    val active: Boolean
)

data class HostInfo(
    val pluginId: String,
    val pluginVersion: String,
    val spwVersion: String,
    val channel: String,
    val compiledApiVersion: String
)

data class RuntimeInfo(
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val javaVersion: String,
    val javaVendor: String,
    val jvmName: String
)

data class MediaBrief(
    val id: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?
)

data class LyricsBrief(
    val lineCount: Int,
    val firstStartMs: Long?,
    val lastStartMs: Long?
)

data class FirstRead(
    val atMs: Long,
    val note: String,
    val mediaElapsedMs: Long,
    val lyricsElapsedMs: Long,
    val media: MediaBrief?,
    val lyrics: LyricsBrief?
)

data class HttpProbeResult(
    val available: Boolean,
    val detail: String,
    val elapsedMs: Long,
    val port: Int?
)

data class ProbeReport(
    val generatedAt: String,
    val session: SessionInfo,
    val host: HostInfo,
    val runtime: RuntimeInfo,
    val counters: Map<String, Long>,
    val droppedEvents: Long,
    val firstReads: List<FirstRead>,
    val httpServer: HttpProbeResult?,
    val jdkHttpServerModule: Boolean
)

/**
 * 一次插件启用周期内的探针状态。
 *
 * 回调线程只做“入队”，文件写入由独立守护线程完成，避免把宿主回调拖慢。
 */
class ProbeSession(
    val instanceId: String,
    val startedAtWall: String,
    private val startNano: Long,
    val host: HostInfo,
    private val log: ProbeLog
) : AutoCloseable {

    @Volatile
    var active: Boolean = true
        private set

    private data class Event(val atMs: Long, val thread: String, val kind: String, val detail: String)

    private val events = ArrayBlockingQueue<Event>(2048)
    private val dropped = AtomicLong()
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val firstReads = CopyOnWriteArrayList<FirstRead>()
    private val httpProbe = AtomicReference<HttpProbeResult?>(null)
    private val closed = AtomicLong()

    private val writerThread = Thread({ drain() }, "saltbridge-probe-writer").apply {
        isDaemon = true
        start()
    }

    fun nowMs(): Long = (System.nanoTime() - startNano) / 1_000_000

    fun accept(kind: String, detail: String) {
        if (!active) return
        counts.computeIfAbsent(kind) { AtomicLong() }.incrementAndGet()
        val event = Event(nowMs(), Thread.currentThread().name, kind, detail)
        if (!events.offer(event)) {
            dropped.incrementAndGet()
        }
    }

    fun recordFirstRead(read: FirstRead) {
        if (!active) return
        firstReads += read
        accept("firstRead", "at=${read.atMs} note=${read.note} lines=${read.lyrics?.lineCount}")
    }

    fun recordHttpProbe(result: HttpProbeResult) {
        httpProbe.set(result)
        accept(
            "http.probe",
            "available=${result.available} elapsed=${result.elapsedMs}ms port=${result.port} detail=${result.detail}"
        )
    }

    private fun drain() {
        while (active || events.isNotEmpty()) {
            val event = try {
                events.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            log.line(event.atMs, event.thread, event.kind, event.detail)
        }
        log.line(nowMs(), "writer", "session.closed", "dropped=${dropped.get()}")
    }

    fun writeReport(gson: Gson, reportFile: Path): Path? = runCatching {
        val report = ProbeReport(
            generatedAt = java.time.OffsetDateTime.now().toString(),
            session = SessionInfo(instanceId, startedAtWall, nowMs(), active),
            host = host,
            runtime = RuntimeInfo(
                osName = System.getProperty("os.name").orEmpty(),
                osVersion = System.getProperty("os.version").orEmpty(),
                osArch = System.getProperty("os.arch").orEmpty(),
                javaVersion = System.getProperty("java.version").orEmpty(),
                javaVendor = System.getProperty("java.vendor").orEmpty(),
                jvmName = System.getProperty("java.vm.name").orEmpty()
            ),
            counters = counts.mapValues { it.value.get() }.toSortedMap(),
            droppedEvents = dropped.get(),
            firstReads = firstReads.toList(),
            httpServer = httpProbe.get(),
            jdkHttpServerModule = ModuleLayer.boot().findModule("jdk.httpserver").isPresent
        )
        Files.createDirectories(reportFile.parent)
        Files.writeString(
            reportFile,
            gson.toJson(report),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
        reportFile
    }.getOrNull()

    override fun close() {
        if (closed.getAndIncrement() > 0) return
        active = false
        writerThread.interrupt()
        runCatching { writerThread.join(800) }
        log.close()
    }
}
