@file:OptIn(UnstableSpwWorkshopApi::class)

package com.saltbridge.probe

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 探针总控：只负责采集事实，不实现完整 HTTP 服务或控制业务。
 */
object ProbeController {

    const val COMPILED_API_VERSION = "0.1.0-dev23"

    private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val sessionRef = AtomicReference<ProbeSession?>(null)
    private val lastSession = AtomicReference<ProbeSession?>(null)

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    fun current(): ProbeSession? = sessionRef.get()?.takeIf { it.active }

    fun start(context: PluginContext): Boolean {
        if (sessionRef.get()?.active == true) return true

        val dataDir = runCatching { dataDir() }.getOrNull() ?: return false
        val log = ProbeLog(dataDir.resolve("logs").resolve("probe.log"))
        val session = ProbeSession(
            instanceId = UUID.randomUUID().toString(),
            startedAtWall = OffsetDateTime.now().toString(),
            startNano = System.nanoTime(),
            host = HostInfo(
                pluginId = context.pluginId,
                pluginVersion = context.pluginVersion,
                spwVersion = context.spwVersion,
                channel = context.spwChannel.name,
                compiledApiVersion = COMPILED_API_VERSION
            ),
            log = log
        )
        sessionRef.set(session)
        lastSession.set(session)

        session.accept(
            "plugin.start",
            "plugin=${context.pluginId}@${context.pluginVersion} " +
                "spw=${context.spwVersion} channel=${context.spwChannel}"
        )
        session.accept(
            "runtime",
            "java=${System.getProperty("java.version")} " +
                "vendor=${System.getProperty("java.vendor")} " +
                "os=${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
                "arch=${System.getProperty("os.arch")}"
        )
        session.accept(
            "module.jdk.httpserver",
            "present=${ModuleLayer.boot().findModule("jdk.httpserver").isPresent}"
        )

        val sched = Executors.newScheduledThreadPool(2) { runnable ->
            Thread(runnable, "saltbridge-probe-sched").apply { isDaemon = true }
        }
        scheduler = sched

        sched.schedule({ runCatching { session.recordHttpProbe(HttpProbe.run()) } }, 2, TimeUnit.SECONDS)
        listOf(1000L, 3000L, 6000L).forEach { delay ->
            sched.schedule({ firstRead(session) }, delay, TimeUnit.MILLISECONDS)
        }

        return true
    }

    fun stop() {
        val session = sessionRef.getAndSet(null) ?: return
        session.accept("plugin.stop", "")
        scheduler?.shutdownNow()
        scheduler = null
        session.close()
    }

    fun update() {
        sessionRef.get()?.accept("plugin.update", "")
    }

    fun delete() {
        sessionRef.get()?.accept("plugin.delete", "")
    }

    fun exportReport() {
        val session = sessionRef.get() ?: lastSession.get()
        if (session == null) {
            toast("探针尚未启动，没有报告可导出", success = false)
            return
        }
        val dir = runCatching { dataDir() }.getOrNull()
        if (dir == null) {
            toast("无法获取插件数据目录", success = false)
            return
        }
        val file = session.writeReport(gson, dir.resolve("reports").resolve("probe-report.json"))
        if (file == null) {
            toast("报告写入失败，详见 logs/probe.log", success = false)
        } else {
            toast("报告已写入 reports/probe-report.json")
        }
    }

    fun insertLogMarker() {
        val session = sessionRef.get()
        if (session == null) {
            toast("探针未运行", success = false)
            return
        }
        session.accept("log.marker", "manual divider")
        toast("已写入分隔线")
    }

    fun probeHttpNow() {
        val session = sessionRef.get()
        if (session == null || !session.active) {
            toast("探针未运行", success = false)
            return
        }
        toast("已开始探测 HttpServer，结果见报告")
        val task = Runnable { runCatching { session.recordHttpProbe(HttpProbe.run()) } }
        val sched = scheduler
        if (sched != null) {
            runCatching { sched.execute(task) }
        } else {
            Thread(task, "saltbridge-probe-http-manual").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun firstRead(session: ProbeSession) {
        if (!session.active) return
        val at = session.nowMs()

        var media: MediaBrief? = null
        var note = "ok"
        val mediaStart = System.nanoTime()
        try {
            val item = WorkshopApi.playback.getCurrentMediaItem()
            if (item == null) {
                note = "null"
            } else {
                media = MediaBrief(
                    id = item.id.ifEmpty { null },
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    durationMs = item.duration.takeIf { it > 0 }
                )
            }
        } catch (denied: PluginPermissionDeniedException) {
            note = "permission_denied:${denied.permission.id}"
        } catch (t: Throwable) {
            note = "error:${t.javaClass.simpleName}"
        }
        val mediaElapsed = (System.nanoTime() - mediaStart) / 1_000_000

        val lyricsStart = System.nanoTime()
        val lines = runCatching { WorkshopApi.playback.getLyricsLines() }.getOrNull()
        val lyricsElapsed = (System.nanoTime() - lyricsStart) / 1_000_000
        val lyricsBrief = lines?.let {
            LyricsBrief(
                lineCount = it.size,
                firstStartMs = it.firstOrNull()?.startTime,
                lastStartMs = it.lastOrNull()?.startTime
            )
        }

        session.recordFirstRead(
            FirstRead(
                atMs = at,
                note = note,
                mediaElapsedMs = mediaElapsed,
                lyricsElapsedMs = lyricsElapsed,
                media = media,
                lyrics = lyricsBrief
            )
        )
    }

    private fun dataDir(): Path {
        val configPath = WorkshopApi.manager.createConfigManager().getConfig().getConfigPath()
        val parent = configPath.parent ?: error("config path has no parent")
        Files.createDirectories(parent)
        return parent
    }

    private fun toast(text: String, success: Boolean = true) {
        runCatching {
            WorkshopApi.ui.toast(
                text,
                if (success) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Error
            )
        }
    }
}
